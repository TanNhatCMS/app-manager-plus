package com.app.manager.data.repository

import com.app.manager.core.common.Result
import com.app.manager.core.di.NetworkModule.ApiClient
import com.app.manager.data.manager.AppManager
import com.app.manager.data.manager.RevancedPackageInstaller
import com.app.manager.data.manager.SimpleDownloadManager
import com.app.manager.domain.model.AppDownload
import com.app.manager.domain.model.AppStatus
import com.app.manager.domain.model.RevancedApp
import com.app.manager.domain.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepositoryImpl @Inject constructor(
    @ApiClient private val apiOkHttpClient: OkHttpClient,
    private val json: Json,
    private val appManager: AppManager,
    private val simpleDownloadManager: SimpleDownloadManager,
    private val packageInstaller: RevancedPackageInstaller
) : AppRepository {

    @Volatile
    private var cachedApps: List<RevancedApp> = emptyList()

    override fun getApps(forceRefresh: Boolean): Flow<Result<List<RevancedApp>>> = flow {
        emit(Result.Loading)

        if (!forceRefresh && cachedApps.isNotEmpty()) {
            emit(Result.Success(cachedApps))
            return@flow
        }

        runCatching { fetchRemoteApps() }
            .onSuccess { apps ->
                cachedApps = apps
                emit(Result.Success(apps))
            }
            .onFailure { error ->
                if (cachedApps.isNotEmpty()) {
                    emit(Result.Success(cachedApps))
                } else {
                    emit(Result.Error(error))
                }
            }
    }.flowOn(Dispatchers.IO)

    override fun getAppsFromCacheImmediately(): Flow<Result<List<RevancedApp>>> = flow {
        if (cachedApps.isEmpty()) {
            emit(Result.Loading)
        } else {
            emit(Result.Success(cachedApps))
        }
    }.flowOn(Dispatchers.IO)

    override fun backgroundRefreshApps(): Flow<Result<List<RevancedApp>>> = flow {
        emit(Result.Loading)
        runCatching { fetchRemoteApps() }
            .onSuccess { apps ->
                cachedApps = apps
                emit(Result.Success(apps))
            }
            .onFailure { error ->
                if (cachedApps.isNotEmpty()) {
                    emit(Result.Success(cachedApps))
                } else {
                    emit(Result.Error(error))
                }
            }
    }.flowOn(Dispatchers.IO)

    override fun getUpdatedApps(oldApps: List<RevancedApp>, newApps: List<RevancedApp>): List<RevancedApp> {
        val oldMap = oldApps.associateBy { it.packageName }
        return newApps.filter { newApp ->
            val oldApp = oldMap[newApp.packageName] ?: return@filter true
            oldApp.latestVersionCode != newApp.latestVersionCode ||
                oldApp.downloadUrl != newApp.downloadUrl ||
                oldApp.iconUrl != newApp.iconUrl
        }
    }

    override suspend fun getInstalledVersion(packageName: String): String? {
        return appManager.getInstalledVersion(packageName)
    }

    override fun downloadApp(packageName: String, downloadUrl: String): Flow<AppDownload> {
        return simpleDownloadManager.downloadApp(packageName, downloadUrl)
    }

    override suspend fun installApp(packageName: String, apkFilePath: String): Result<Boolean> {
        return runCatching { packageInstaller.installPackage(packageName, apkFilePath) }
            .fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it) }
            )
    }

    override suspend fun uninstallApp(packageName: String): Result<Boolean> {
        return runCatching { appManager.uninstallApp(packageName) }
            .fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it) }
            )
    }

    override suspend fun openApp(packageName: String): Result<Boolean> {
        return runCatching { appManager.openApp(packageName) }
            .fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it) }
            )
    }

    override suspend fun isAppInstalled(packageName: String): Boolean {
        return appManager.isAppInstalled(packageName)
    }

    private suspend fun fetchRemoteApps(): List<RevancedApp> {
        var lastError: Throwable = IllegalStateException("No supported endpoint returned app data")

        for (endpoint in APP_ENDPOINTS) {
            val responseBody = runCatching { fetchBodyAsJson(endpoint) }
            if (responseBody.isFailure) {
                lastError = responseBody.exceptionOrNull() ?: lastError
                continue
            }

            val parsed = parseApps(responseBody.getOrThrow())
            if (parsed.isNotEmpty()) {
                return parsed
            }
        }

        throw lastError
    }

    private fun parseApps(root: JsonElement): List<RevancedApp> {
        val appArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                when {
                    root["apps"] is JsonArray -> root["apps"] as JsonArray
                    root["data"] is JsonArray -> root["data"] as JsonArray
                    else -> JsonArray(emptyList())
                }
            }
            else -> JsonArray(emptyList())
        }

        return appArray.mapIndexedNotNull { index, node ->
            val obj = node as? JsonObject ?: return@mapIndexedNotNull null

            val packageName = obj.string("packageName", "package_name", "package", "id") ?: return@mapIndexedNotNull null
            val title = obj.string("title", "name") ?: packageName
            val versionCode = obj.long("versionCode", "latestVersion", "code") ?: return@mapIndexedNotNull null
            val versionName = obj.string("versionName", "version") ?: versionCode.toString() 
            val description = obj.string("description", "desc") ?: ""
            val iconUrl = obj.string("iconUrl", "icon_url", "icon")?.withBaseUrl() ?: ""
            val downloadUrl = obj.string("downloadUrl", "download_url", "url", "apk", "link")?.withBaseUrl()
                ?: return@mapIndexedNotNull null
            val requiresMicroG = obj.boolean("requiresMicroG", "requires_microg", "microg") ?: false
            val appIndex = obj.int("index", "order") ?: index
            val currentVersion = appManager.getInstalledVersion(packageName)

            RevancedApp(
                packageName = packageName,
                title = title,
                latestVersionCode = versionCode,
                latestVersionName = versionName,
                currentVersion = currentVersion,
                description = description,
                iconUrl = iconUrl,
                downloadUrl = downloadUrl,
                requiresMicroG = requiresMicroG,
                index = appIndex,
                status = AppStatus.UNKNOWN
            )
        }
    }

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val content = value.contentOrNull?.trim()
            if (!content.isNullOrEmpty()) return content
        }
        return null
    }

    private fun JsonObject.long(vararg keys: String): Long? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val longValue = value.contentOrNull?.trim()?.toLongOrNull()
            if (longValue != null) return longValue
        }
        return null
    }

    private fun JsonObject.boolean(vararg keys: String): Boolean? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val boolValue = value.booleanOrNull
            if (boolValue != null) return boolValue
            val text = value.contentOrNull?.trim()?.lowercase() ?: continue
            if (text == "true") return true
            if (text == "false") return false
        }
        return null
    }

    private fun JsonObject.int(vararg keys: String): Int? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val intValue = value.contentOrNull?.trim()?.toIntOrNull()
            if (intValue != null) return intValue
        }
        return null
    }

    private fun String.withBaseUrl(): String {
        return if (startsWith("http://") || startsWith("https://")) this else RAW_BASE_URL + trimStart('/')
    }

    private fun fetchBodyAsJson(endpoint: String): JsonElement {
        val url = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            endpoint
        } else {
            RAW_BASE_URL + endpoint.trimStart('/')
        }

        val request = Request.Builder().url(url).get().build()
        apiOkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for $url")
            }

            val bodyText = response.body?.string()?.trim()
            if (bodyText.isNullOrEmpty()) {
                throw IllegalStateException("Empty response for $url")
            }

            return json.parseToJsonElement(bodyText)
        }
    }

    companion object {
        private const val RAW_BASE_URL = "https://raw.githubusercontent.com/TanNhatCMS/app-manager-plus/main/"
        private val APP_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/TanNhatCMS/app-manager-plus/main/apps.json",
            "apps.json"
        )
    }
}

