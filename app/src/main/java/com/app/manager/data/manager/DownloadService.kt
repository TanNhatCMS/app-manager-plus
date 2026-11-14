package com.app.manager.data.manager

/**
 * Broadcast action/extra constants for download completion events.
 *
 * This used to be a registered `Service`, but it never started anything — downloads run in
 * [SimpleDownloadManager] flows and the receiver lives in [com.app.manager.presentation.bloc.AppBloc].
 * Only the constants are meaningful, so the class is a plain holder now.
 */
object DownloadService {
    const val ACTION_DOWNLOAD_COMPLETE = "com.app.manager.DOWNLOAD_COMPLETE"
    const val ACTION_ALL_DOWNLOADS_COMPLETE = "com.app.manager.ALL_DOWNLOADS_COMPLETE"
    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_FILE_PATH = "extra_file_path"
}
