package com.app.manager.data.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_DOWNLOAD_COMPLETE = "com.app.manager.DOWNLOAD_COMPLETE"
        const val ACTION_ALL_DOWNLOADS_COMPLETE = "com.app.manager.ALL_DOWNLOADS_COMPLETE"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}

