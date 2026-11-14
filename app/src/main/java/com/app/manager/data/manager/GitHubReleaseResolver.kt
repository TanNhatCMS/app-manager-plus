package com.app.manager.data.manager

import android.os.Build
import android.util.Log
import com.app.manager.core.di.NetworkModule.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One release asset: the APK file name and its direct download URL. */
data class ReleaseAsset(val name: String, val url: String)

/** One GitHub release: tag, prerelease flag, publish time and its APK assets. */
data class GitHubRelease(
    val tag: String,
    val prerelease: Boolean,
    val publishedAtMs: Long,
    val assets: List<ReleaseAsset>
)

/**
 * Pure asset selection for a MicroG-RE-style release. Kept free of Android and network code so it
 * can be unit tested.
 *
 * MorpheApp/MicroG-RE publishes six variants per release (see the release notes):
 *  - `microg-<tag>.apk` — universal
 *  - `microg-<tag>-noicon.apk` — universal, launcher icon hidden
 *  - `microg-<tag>-icon-arm64-v8a.apk` / `microg-<tag>-icon-armeabi-v7a.apk` — single-ABI builds
 *  - `microg-<tag>-noicon-arm64-v8a.apk` / `microg-<tag>-noicon-armeabi-v7a.apk`
 *
 * Single-ABI builds carry only that ABI's native libraries and are less than half the size. The
 * arm64 build requires a 64-bit ARM userspace; the armeabi-v7a build is for 32-bit userspace
 * devices. When neither ABI matches the device's preferred ABI (e.g. x86_64 emulators), fall back
 * to the universal APK.
 */
object ReleaseAssetPicker {

    /**
     * @param releases releases sorted newest-first (GitHub API order).
     * @param wantPrerelease true → newest pre-release (thử nghiệm); false → newest stable ("Latest").
     * @param abiTag the device's preferred ABI ("arm64-v8a" / "armeabi-v7a") or null → universal.
     * @param noicon true → the icon-hidden variant of the chosen build.
     * @return the chosen asset URL and the release it came from, or null when nothing matches.
     */
    fun pick(
        releases: List<GitHubRelease>,
        wantPrerelease: Boolean,
        abiTag: String?,
        noicon: Boolean
    ): Pair<GitHubRelease, ReleaseAsset>? {
        val release = releases.firstOrNull { it.prerelease == wantPrerelease && it.assets.isNotEmpty() }
            ?: return null

        val iconMarker = if (noicon) "-noicon" else "-icon"
        val abiAsset = abiTag?.let { abi ->
            release.assets.firstOrNull { asset -> asset.name.endsWith("$iconMarker-$abi.apk") }
        }
        if (abiAsset != null) return release to abiAsset

        // Universal: the bare `microg-<tag>.apk` (or its -noicon sibling).
        val universalName = if (noicon) "microg-${release.tag}-noicon.apk" else "microg-${release.tag}.apk"
        val universal = release.assets.firstOrNull { it.name == universalName }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("-arm64-v8a") && !it.name.contains("-armeabi-v7a") }
        return universal?.let { release to it }
    }
}

/**
 * Resolves a "Latest"/"Pre-release" build of a GitHub repository into a concrete APK download.
 *
 * Used for catalog entries marked `"source": "github-releases"` (currently the Morphe MicroG RE
 * builds) so the manager always offers the newest stable or pre-release build instead of a
 * version pinned in apps-v2.json. Results are cached per repo for [CACHE_TTL_MS]; a failed fetch
 * serves the stale cache, and the caller falls back to the catalog's static values on null — so
 * an offline launch still shows the last known release.
 */
@Singleton
class GitHubReleaseResolver @Inject constructor(
    @ApiClient private val okHttpClient: OkHttpClient,
    private val json: Json
) {

    data class ResolvedRelease(
        val versionName: String,
        val downloadUrl: String,
        val updatedAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<GitHubRelease>>>()

    suspend fun resolve(repo: String, wantPrerelease: Boolean, noicon: Boolean = false): ResolvedRelease? {
        val releases = fetchReleases(repo) ?: return null
        val abiTag = deviceAbiTag()
        val (release, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease, abiTag, noicon) ?: return null
        return ResolvedRelease(
            versionName = release.tag,
            downloadUrl = asset.url,
            updatedAtMs = release.publishedAtMs
        )
    }

    /**
     * The ABI whose single-ABI build fits this device, based on the preferred ABI: `arm64-v8a`
     * for a 64-bit ARM userspace, `armeabi-v7a` for a 32-bit one. Anything else (x86_64 emulator,
     * unknown) returns null → universal APK, which runs everywhere the others run.
     */
    private fun deviceAbiTag(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        "armeabi-v7a" -> "armeabi-v7a"
        else -> null
    }

    /** Releases newest-first; serves a ≤[CACHE_TTL_MS]-old cache, or the stale one on failure. */
    private suspend fun fetchReleases(repo: String): List<GitHubRelease>? {
        cache[repo]?.let { (fetchedAt, releases) ->
            if (System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS) return releases
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repo/releases?per_page=20")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) throw IllegalStateException("GitHub API returned ${it.code}")
                    val body = it.body?.string() ?: throw IllegalStateException("Empty GitHub API body")
                    parseReleases(json.parseToJsonElement(body).jsonArray)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Failed to fetch releases for $repo", error)
                cache[repo]?.second
            }?.let { releases ->
                cache[repo] = System.currentTimeMillis() to releases
                releases
            }
        }
    }

    internal fun parseReleases(array: JsonArray): List<GitHubRelease> {
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val draft = (obj["draft"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (draft) return@mapNotNull null
            val tag = (obj["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val prerelease = (obj["prerelease"] as? JsonPrimitive)?.booleanOrNull ?: false
            val publishedAt = parseIsoTimestamp((obj["published_at"] as? JsonPrimitive)?.contentOrNull)
            val assets = (obj["assets"] as? JsonArray)?.mapNotNull { assetElement ->
                val asset = assetElement as? JsonObject ?: return@mapNotNull null
                val name = (asset["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                ReleaseAsset(name, url)
            }.orEmpty()
            GitHubRelease(tag, prerelease, publishedAt, assets)
        }
    }

    private fun parseIsoTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private companion object {
        const val TAG = "GitHubReleases"

        /** GitHub's unauthenticated API allows 60 requests/hour per IP — cache well above that. */
        const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}
