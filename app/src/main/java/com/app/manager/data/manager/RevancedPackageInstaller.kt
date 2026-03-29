package com.app.manager.data.manager

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RevancedPackageInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _installationResults = MutableSharedFlow<InstallationResult>(extraBufferCapacity = 64)
    val installationResults: SharedFlow<InstallationResult> = _installationResults

    fun installPackage(packageName: String, apkFilePath: String): Boolean {
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) {
            _installationResults.tryEmit(
                InstallationResult.Failed(packageName, "APK file does not exist")
            )
            return false
        }

        return runCatching {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
            _installationResults.tryEmit(InstallationResult.PendingUserAction(packageName))
            true
        }.getOrElse { error ->
            _installationResults.tryEmit(
                InstallationResult.Failed(packageName, error.message ?: "Failed to launch installer")
            )
            false
        }
    }
}

