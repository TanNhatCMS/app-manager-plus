package com.revanced.net.revancedmanager.data.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_DOWNLOAD_COMPLETE = "com.revanced.net.revancedmanager.DOWNLOAD_COMPLETE"
        const val ACTION_ALL_DOWNLOADS_COMPLETE = "com.revanced.net.revancedmanager.ALL_DOWNLOADS_COMPLETE"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
