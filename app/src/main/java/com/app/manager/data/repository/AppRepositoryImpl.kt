package com.app.manager.data.repository

import android.util.Log
import com.app.manager.config.Config
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
import kotlinx.serialization.json.intOrNull
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
    private val packageInstaller: RevancedPackageInstaller,
    private val githubReleaseResolver: com.app.manager.data.manager.GitHubReleaseResolver,
    private val preferencesManager: com.app.manager.data.local.preferences.PreferencesManager
) : AppRepository {

    @Volatile
    private var cachedApps: List<RevancedApp> = emptyList()

    private val TAG = "AppRepositoryImpl"

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
        val oldMap = oldApps.associateBy { "${it.packageName}:${it.vendor}:${it.channel}:${it.variant}" }
        return newApps.filter { newApp ->
            val key = "${newApp.packageName}:${newApp.vendor}:${newApp.channel}:${newApp.variant}"
            val oldApp = oldMap[key] ?: return@filter true
            oldApp.latestVersionCode != newApp.latestVersionCode ||
                oldApp.latestVersionName != newApp.latestVersionName ||
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

    override suspend fun installApp(
        packageName: String,
        apkFilePath: String,
        requireUserAction: Boolean
    ): Result<Boolean> {
        return runCatching { packageInstaller.installPackage(packageName, apkFilePath, requireUserAction) }
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
                return resolveDynamicEntries(injectMorpheMicrog(parsed))
            }
        }

        throw lastError
    }

    /**
     * The Morphe MicroG build is tracked in code, not in apps-v2.json: this strips any catalog
     * MicroG entries of the Morphe vendor and appends the two manager-owned entries that follow
     * the MorpheApp/MicroG-RE release stream — `stable` = newest "Latest" release, `beta` =
     * newest pre-release (shown when the beta channel is enabled). The entries are resolved to a
     * concrete APK by [resolveDynamicEntries]; the static values below are the offline fallback.
     */
    private fun injectMorpheMicrog(apps: List<RevancedApp>): List<RevancedApp> {
        val withoutCatalogMicrog = apps.filterNot { app ->
            app.vendor == "morphe" && app.packageName == Config.MORPHE_MICROG_PACKAGE
        }
        val noicon = runCatching {
            preferencesManager.getAppConfig().microgIconHidden
        }.getOrDefault(false)
        val stable = RevancedApp(
            packageName = Config.MORPHE_MICROG_PACKAGE,
            title = "MicroG RE",
            latestVersionCode = 0L,
            latestVersionName = Config.MORPHE_MICROG_FALLBACK_STABLE_VERSION,
            currentVersion = appManager.getInstalledVersionName(Config.MORPHE_MICROG_PACKAGE),
            description = "MicroG Services (MorpheApp build) — follows the newest Latest release of MorpheApp/MicroG-RE; the APK variant is matched to this device.",
            iconUrl = Config.MORPHE_ICON_URL,
            downloadUrl = Config.MORPHE_MICROG_FALLBACK_STABLE_URL,
            requiresMicroG = false,
            index = 3,
            status = AppStatus.UNKNOWN,
            vendor = "morphe",
            channel = "stable",
            githubRepo = Config.MORPHE_MICROG_REPO,
            githubPrerelease = false,
            githubAssetNoicon = noicon,
            signingSha256 = Config.MORPHE_MICROG_FINGERPRINT
        )
        val beta = stable.copy(
            title = "MicroG RE Thử nghiệm",
            latestVersionName = Config.MORPHE_MICROG_FALLBACK_BETA_VERSION,
            description = "MicroG Services (MorpheApp build) — follows the newest Pre-release of MorpheApp/MicroG-RE; the APK variant is matched to this device.",
            downloadUrl = Config.MORPHE_MICROG_FALLBACK_BETA_URL,
            index = 4,
            channel = "beta",
            githubPrerelease = true
        )
        return withoutCatalogMicrog + stable + beta
    }

    /**
     * Resolve catalog entries backed by a GitHub repository (`"source": "github-releases"`) into
     * the repo's newest stable ("Latest") or pre-release build, with the APK variant matched to
     * this device. A failed or offline resolution keeps the catalog's static fallback values.
     */
    private suspend fun resolveDynamicEntries(apps: List<RevancedApp>): List<RevancedApp> {
        val dynamic = apps.filter { it.githubRepo != null }
        if (dynamic.isEmpty()) return apps

        return apps.map { app ->
            val repo = app.githubRepo ?: return@map app
            val resolved = runCatching {
                githubReleaseResolver.resolve(repo, app.githubPrerelease, app.githubAssetNoicon)
            }.getOrNull() ?: return@map app

            Log.d(TAG, "Resolved $repo (prerelease=${app.githubPrerelease}): v${resolved.versionName} → ${resolved.downloadUrl}")
            app.copy(
                latestVersionName = resolved.versionName,
                downloadUrl = resolved.downloadUrl,
                updatedAt = if (resolved.updatedAtMs > 0) resolved.updatedAtMs else app.updatedAt
            )
        }
    }

    private fun parseApps(root: JsonElement): List<RevancedApp> {
        // --- V2 format: {"version": 2, "manager": {...}, "vendors": {"morphe": {...}, "revanced": {...}}} ---
        if (root is JsonObject && root["version"]?.let {
            (it as? JsonPrimitive)?.intOrNull
        } == 2 && root["vendors"] is JsonObject) {
            return parseV2(root)
        }

        // --- V1 format: {"apps": [...]} or raw array [...] ---
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
            parseAppNode(node as? JsonObject ?: return@mapIndexedNotNull null, index)
        }
    }

    /**
     * Parse V2 format: shared manager + vendor-specific apps
     */
    private fun parseV2(root: JsonObject): List<RevancedApp> {
        val allApps = mutableListOf<RevancedApp>()

        // Shared manager app (always first)
        val managerNode = root["manager"] as? JsonObject
        if (managerNode != null) {
            val managerApp = parseAppNode(managerNode, 0)
            if (managerApp != null) {
                allApps.add(managerApp)
            }
        }

        // Vendor-specific apps
        val vendorsObj = root["vendors"] as? JsonObject
        if (vendorsObj != null) {
            for ((vendorKey, vendorValue) in vendorsObj) {
                val vendor = vendorValue as? JsonObject ?: continue
                val vendorApps = vendor["apps"] as? JsonArray ?: continue

                for ((i, node) in vendorApps.withIndex()) {
                    val obj = node as? JsonObject ?: continue
                    val app = parseAppNode(obj, i + 1, vendorKey)
                    if (app != null) {
                        allApps.add(app)
                    }
                }
            }
        }

        return allApps
    }

    /**
     * Parse a single app node into RevancedApp
     */
    private fun parseAppNode(obj: JsonObject, fallbackIndex: Int, vendor: String? = null): RevancedApp? {
        val packageName = obj.string("packageName", "package_name", "package", "id") ?: return null
        val title = obj.string("title", "name") ?: packageName
        val versionCode = obj.long("versionCode", "latestVersion", "code") ?: return null
        val versionName = obj.string("versionName", "version") ?: versionCode.toString()
        val description = obj.string("description", "desc") ?: ""
        val iconUrl = obj.string("iconUrl", "icon_url", "icon")?.withBaseUrl() ?: ""
        val downloadUrl = obj.string("downloadUrl", "download_url", "url", "apk", "link")?.withBaseUrl()
        val requiresMicroG = obj.boolean("requiresMicroG", "requires_microg", "microg") ?: false
        val appIndex = obj.int("index", "order") ?: fallbackIndex
        val currentVersion = appManager.getInstalledVersionName(packageName)
        val channel = obj.string("channel")
        val variant = obj.string("variant")
        val variantLabel = obj.string("variantLabel")
        // Optional v2.1 fields: when this provider rebuilt the app with newer patches only, the
        // patch-update check needs the patches version and the build date.
        val patchVersion = obj.string("patchVersion", "patch_version", "patches")
        val updatedAt = obj.string("updatedAt", "updated_at")?.let(::parseIsoTimestamp)
        // Optional v2.2 fields: a GitHub-releases-backed entry is resolved to the repo's newest
        // stable ("Latest") or pre-release build at load time, with the APK variant matched to
        // this device's ABI. The static values below are the offline fallback.
        val githubSource = obj.string("source")
        val githubRepo = if (githubSource == "github-releases") obj.string("repo") else null
        val githubPrerelease = obj.boolean("prerelease") ?: false
        val githubAssetNoicon = obj.boolean("noiconAsset") ?: false
        val signingSha256 = obj.string("signingSha256", "signing_sha256")

        return RevancedApp(
            packageName = packageName,
            title = title,
            latestVersionCode = versionCode,
            latestVersionName = versionName,
            currentVersion = currentVersion,
            description = description,
            iconUrl = iconUrl,
            downloadUrl = downloadUrl ?: "",
            requiresMicroG = requiresMicroG,
            index = appIndex,
            status = AppStatus.UNKNOWN,
            vendor = vendor,
            channel = channel,
            variant = variant,
            variantLabel = variantLabel,
            updatedAt = updatedAt,
            patchVersion = patchVersion,
            githubRepo = githubRepo,
            githubPrerelease = githubPrerelease,
            githubAssetNoicon = githubAssetNoicon,
            signingSha256 = signingSha256
        )
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

    /** ISO-8601 timestamp ("2026-11-10T00:00:00Z") → epoch millis, or null when unparseable. */
    private fun parseIsoTimestamp(value: String): Long? {
        return runCatching {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(value)?.time
        }.getOrNull()
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
            "https://raw.githubusercontent.com/TanNhatCMS/app-manager-plus/main/apps-v2.json",
            "apps-v2.json",
            "https://raw.githubusercontent.com/TanNhatCMS/app-manager-plus/main/apps.json",
            "apps.json"
        )
    }
}
