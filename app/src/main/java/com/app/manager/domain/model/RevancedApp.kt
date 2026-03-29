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
    val downloadProgress: Float = 0f
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
