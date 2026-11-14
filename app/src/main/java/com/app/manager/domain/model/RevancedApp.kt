package com.app.manager.domain.model

/**
 * Domain model representing a ReVanced application
 */
data class RevancedApp(
    val packageName: String,
    val title: String,
    // Thay thế latestVersion bằng versionCode và thêm versionName
    val latestVersionCode: Long, // Sử dụng Long cho VersionCode lớn
    val latestVersionName: String, // Tên phiên bản (e.g., "1.0.0", "21.13.163")
    val currentVersion: String?, // Giữ nguyên currentVersion (tên phiên bản cài đặt)
    val description: String,
    val iconUrl: String,
    val downloadUrl: String,
    val requiresMicroG: Boolean,
    val index: Int, // Giữ lại index nếu cần sắp xếp (hoặc xóa nếu bạn muốn xóa nó)
    val status: AppStatus,
    val downloadProgress: Float = 0f,
    val vendor: String? = null,       // "morphe" | "revanced" | null (manager/app chung)
    val channel: String? = null,      // "stable" | "beta"
    val variant: String? = null,      // microg variant: "standard" | "hw"
    val variantLabel: String? = null, // nhãn hiển thị cho variant
    /** Epoch millis the catalog build was published — drives the "Recent" sort and the
     *  patch-only update check. Null when the catalog does not publish a date. */
    val updatedAt: Long? = null,
    /** Patches version of this catalog build (e.g. "1.40.0"), when the provider publishes one. */
    val patchVersion: String? = null,
    /** True when this entry's update is patches-only (same app versionName, newer patches). */
    val isPatchUpdate: Boolean = false,
    /** Non-null when the entry tracks a GitHub repository's Latest/Pre-release build
     *  (`"source": "github-releases"` in the catalog); the repository resolves it at load time. */
    val githubRepo: String? = null,
    /** For [githubRepo] entries: track the newest pre-release instead of the newest stable. */
    val githubPrerelease: Boolean = false,
    /** For [githubRepo] entries: prefer the launcher-icon-hidden variant of the build. */
    val githubAssetNoicon: Boolean = false,
    /** Expected SHA-256 fingerprint of the build's signing certificate (colon-separated hex).
     *  Non-null → the downloaded APK is verified against it before installing. */
    val signingSha256: String? = null
)

/**
 * Enum representing the installation status of an app
 */
enum class AppStatus {
    NOT_INSTALLED,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    INSTALLING,
    UNINSTALLING,
    READY_TO_INSTALL,
    UNKNOWN
}

/**
 * Data class for app download information
 */
data class AppDownload(
    val packageName: String,
    val url: String,
    val filePath: String? = null,
    val progress: Float = 0f,
    val isComplete: Boolean = false
)
