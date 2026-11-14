package com.app.manager.data.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.app.manager.core.di.NetworkModule.DownloadClient
import com.app.manager.data.local.preferences.PreferencesManager
import com.app.manager.domain.model.AppDownload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadClient private val okHttpClient: OkHttpClient,
    private val preferencesManager: PreferencesManager
) {
    private val activeDownloads = ConcurrentHashMap<String, AppDownload>()

    /** Live OkHttp calls per package — cancelling one actually stops the transfer. */
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun getActiveDownloads(): Map<String, AppDownload> = activeDownloads.toMap()

    fun cancelDownload(packageName: String) {
        activeDownloads.remove(packageName)
        // Cancelling the call makes execute()/read throw an IOException in the download flow,
        // so the collecting job ends instead of the transfer silently running to completion.
        activeCalls.remove(packageName)?.cancel()
    }

    fun downloadApp(packageName: String, downloadUrl: String): Flow<AppDownload> = flow {
        val configuredPath = runCatching { preferencesManager.getAppConfig().downloadPath }.getOrDefault("")
        val internalDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val outputFile = File(internalDir, "$packageName.apk")
        val partFile = File(internalDir, "$packageName.apk.part")
        val metaFile = File(internalDir, "$packageName.apk.part.meta")

        // Single flight per package: a newer request replaces whatever is still running.
        activeCalls[packageName]?.let { previous ->
            previous.cancel()
            activeCalls.remove(packageName, previous)
        }

        // Bytes already in the .part file, resumed via an HTTP Range request.
        var received = readResumeBytes(partFile, metaFile)

        var attempt = 0
        var completedDownload: AppDownload? = null

        while (completedDownload == null) {
            val call = buildCall(downloadUrl, received)
            activeCalls[packageName] = call
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    throw IOException("Download failed with code ${response.code}")
                }
                val body = response.body ?: throw IOException("Response body is empty")

                val appending = response.code == 206 && received > 0L
                if (!appending) {
                    // The server ignored the Range (or nothing to resume): start over.
                    received = 0L
                    partFile.delete()
                    metaFile.delete()
                }

                val remaining = body.contentLength().coerceAtLeast(1L)
                val totalBytes = received + remaining
                val output = FileOutputStream(partFile, appending)

                // The stall watchdog lives on another thread and only cancels the call — the
                // copy loop below does every emit, so the Flow invariant is never broken.
                val lastProgressAt = AtomicLong(System.currentTimeMillis())
                val watchdog = Thread {
                    while (!call.isCanceled()) {
                        if (System.currentTimeMillis() - lastProgressAt.get() > STALL_TIMEOUT_MS) {
                            call.cancel()
                            return@Thread
                        }
                        try {
                            Thread.sleep(WATCHDOG_POLL_MS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }.apply { isDaemon = true; start() }

                try {
                    body.byteStream().use { input ->
                        output.use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                received += read
                                lastProgressAt.set(System.currentTimeMillis())
                                metaFile.writeText(received.toString())

                                val progress = (received.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
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
                } finally {
                    watchdog.interrupt()
                }

                if (received < totalBytes) {
                    // Server closed the connection early — the .part file is valid, so the
                    // retry below resumes instead of restarting from zero.
                    throw IOException("Download truncated: $received of $totalBytes bytes")
                }

                runCatching {
                    if (outputFile.exists()) outputFile.delete()
                    if (!partFile.renameTo(outputFile)) {
                        throw IOException("Could not move completed download into place")
                    }
                    metaFile.delete()
                }.onFailure { throw it }

                val completed = AppDownload(
                    packageName = packageName,
                    url = downloadUrl,
                    filePath = outputFile.absolutePath,
                    progress = 1f,
                    isComplete = true
                )
                activeDownloads[packageName] = completed
                emit(completed)
                completedDownload = completed
            } catch (e: IOException) {
                val userCancelled = !activeCalls.containsKey(packageName)
                if (userCancelled || call.isCanceled() && !activeCalls.containsKey(packageName)) {
                    throw IOException("Download cancelled: $packageName", e)
                }
                // Keep the .part file and its metadata — the next attempt resumes from there.
                metaFile.writeText(received.toString())
                if (attempt >= MAX_RETRIES) throw e
                attempt++
                delay(RETRY_BACKOFF_MS * attempt)
            } finally {
                activeCalls.remove(packageName, call)
            }
        }

        // Export a copy to user-configured folder (filesystem path or SAF tree URI).
        runCatching {
            exportDownloadedApk(outputFile, configuredPath, packageName)
        }

        val completedIntent = Intent(DownloadService.ACTION_DOWNLOAD_COMPLETE).apply {
            putExtra(DownloadService.EXTRA_PACKAGE_NAME, packageName)
            putExtra(DownloadService.EXTRA_FILE_PATH, outputFile.absolutePath)
        }
        context.sendBroadcast(completedIntent)
    }.flowOn(Dispatchers.IO)

    private fun buildCall(downloadUrl: String, resumeFrom: Long): Call {
        val request = if (resumeFrom > 0L) {
            Request.Builder().url(downloadUrl).header("Range", "bytes=$resumeFrom-").build()
        } else {
            Request.Builder().url(downloadUrl).build()
        }
        return okHttpClient.newCall(request)
    }

    /** Bytes recorded in the resume metadata — 0 when there is no usable .part file. */
    private fun readResumeBytes(partFile: File, metaFile: File): Long {
        if (!partFile.exists() || !metaFile.exists()) return 0L
        val recorded = metaFile.readText().trim().toLongOrNull() ?: return 0L
        if (recorded <= 0L || recorded > partFile.length()) return 0L
        return recorded
    }

    private fun resolveDownloadDirectory(configuredPath: String): File {
        val candidates = mutableListOf<File>()

        if (configuredPath.isNotBlank()) {
            candidates.add(File(configuredPath))
        } else {
            if (canWriteSharedStorage()) {
                candidates.add(File(Environment.getExternalStorageDirectory(), "AppManager"))
            }
            candidates.add(File(context.getExternalFilesDir(null), "AppManager"))
        }
        candidates.add(File(context.cacheDir, "downloads"))

        for (candidate in candidates) {
            if (ensureUsableDirectory(candidate)) {
                return candidate
            }
        }

        return File(context.cacheDir, "downloads").apply { mkdirs() }
    }

    private fun exportDownloadedApk(sourceFile: File, configuredPath: String, packageName: String) {
        if (configuredPath.startsWith("content://")) {
            exportToTreeUri(sourceFile, configuredPath, "$packageName.apk")
            return
        }

        val exportDir = resolveDownloadDirectory(configuredPath)
        val target = File(exportDir, "$packageName.apk")
        sourceFile.copyTo(target, overwrite = true)
    }

    private fun exportToTreeUri(sourceFile: File, treeUriString: String, fileName: String) {
        val treeUri = Uri.parse(treeUriString)
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return
        if (!tree.canWrite()) return

        tree.findFile(fileName)?.delete()
        val target = tree.createFile("application/vnd.android.package-archive", fileName) ?: return

        context.contentResolver.openOutputStream(target.uri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun canWriteSharedStorage(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun ensureUsableDirectory(directory: File): Boolean {
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                return false
            }
            if (!directory.isDirectory) {
                return false
            }
            val probe = File(directory, ".probe")
            if (probe.exists()) {
                probe.delete()
            }
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** A transfer that has not received a byte for this long is considered dead. */
        const val STALL_TIMEOUT_MS = 45_000L
        const val WATCHDOG_POLL_MS = 5_000L
        const val MAX_RETRIES = 3
        const val RETRY_BACKOFF_MS = 1_000L
    }
}
