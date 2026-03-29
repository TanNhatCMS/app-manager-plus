package com.revanced.net.revancedmanager.data.manager

import android.content.Context
import android.content.Intent
import com.revanced.net.revancedmanager.core.di.NetworkModule.DownloadClient
import com.revanced.net.revancedmanager.domain.model.AppDownload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadClient private val okHttpClient: OkHttpClient
) {
    private val activeDownloads = ConcurrentHashMap<String, AppDownload>()

    fun getActiveDownloads(): Map<String, AppDownload> = activeDownloads.toMap()

    fun cancelDownload(packageName: String) {
        activeDownloads.remove(packageName)
    }

    fun downloadApp(packageName: String, downloadUrl: String): Flow<AppDownload> = flow {
        val downloadsDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val outputFile = File(downloadsDir, "$packageName.apk")

        val request = Request.Builder().url(downloadUrl).build()
        val call = okHttpClient.newCall(request)
        val response = call.execute()

        if (!response.isSuccessful) {
            throw IOException("Download failed with code ${response.code}")
        }

        val body = response.body ?: throw IOException("Response body is empty")
        val totalBytes = body.contentLength().coerceAtLeast(1L)

        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesCopied = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytesCopied += read

                    val progress = (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    val item = AppDownload(
                        packageName = packageName,
                        url = downloadUrl,
                        filePath = outputFile.absolutePath,
                        progress = progress,
                        isComplete = false
                    )
                    activeDownloads[packageName] = item
                    emit(item)
                }
            }
        }

        val completed = AppDownload(
            packageName = packageName,
            url = downloadUrl,
            filePath = outputFile.absolutePath,
            progress = 1f,
            isComplete = true
        )

        activeDownloads[packageName] = completed
        emit(completed)

        val completedIntent = Intent(DownloadService.ACTION_DOWNLOAD_COMPLETE).apply {
            putExtra(DownloadService.EXTRA_PACKAGE_NAME, packageName)
            putExtra(DownloadService.EXTRA_FILE_PATH, outputFile.absolutePath)
        }
        context.sendBroadcast(completedIntent)

        activeDownloads.remove(packageName)
    }.flowOn(Dispatchers.IO)
}
