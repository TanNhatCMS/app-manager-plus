package com.revanced.net.revancedmanager.data.repository

import com.revanced.net.revancedmanager.core.common.Result
import com.revanced.net.revancedmanager.data.manager.AppManager
import com.revanced.net.revancedmanager.data.manager.RevancedPackageInstaller
import com.revanced.net.revancedmanager.data.manager.SimpleDownloadManager
import com.revanced.net.revancedmanager.data.remote.api.RevancedApiService
import com.revanced.net.revancedmanager.domain.model.AppDownload
import com.revanced.net.revancedmanager.domain.model.AppStatus
import com.revanced.net.revancedmanager.domain.model.RevancedApp
import com.revanced.net.revancedmanager.domain.repository.AppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepositoryImpl @Inject constructor(
    private val apiService: RevancedApiService,
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
    }

    override fun getAppsFromCacheImmediately(): Flow<Result<List<RevancedApp>>> = flow {
        if (cachedApps.isEmpty()) {
            emit(Result.Loading)
        } else {
            emit(Result.Success(cachedApps))
        }
    }

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
    }

    override fun getUpdatedApps(oldApps: List<RevancedApp>, newApps: List<RevancedApp>): List<RevancedApp> {
        val oldMap = oldApps.associateBy { it.packageName }
        return newApps.filter { newApp ->
            val oldApp = oldMap[newApp.packageName] ?: return@filter true
            oldApp.latestVersion != newApp.latestVersion ||
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
            val response = runCatching { apiService.getApps(endpoint) }
            if (response.isFailure) {
                lastError = response.exceptionOrNull() ?: lastError
                continue
            }

            val body = response.getOrThrow().body()
            if (body == null) {
                continue
            }

            val parsed = parseApps(body)
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
            val latestVersion = obj.string("latestVersion", "latest_version", "version") ?: "0"
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
                latestVersion = latestVersion,
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
        return if (startsWith("http://") || startsWith("https://")) this else "https://vanced.to/" + trimStart('/')
    }

    companion object {
        private val APP_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/TanNhatCMS/vanced-manager-plus/main/apps.json",
            "api/apps",
            "api/apps.json",
            "api/v1/apps",
            "api/v1/apps.json",
            "apps.json"
        )
    }
}
