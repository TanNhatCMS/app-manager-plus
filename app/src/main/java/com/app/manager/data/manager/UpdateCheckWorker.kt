package com.app.manager.data.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.manager.R
import com.app.manager.core.common.VersionComparator
import com.app.manager.data.local.preferences.PreferencesManager
import com.app.manager.domain.model.AppStatus
import com.app.manager.domain.repository.AppRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily background update check: fetches the catalog, compares the installed versionNames against
 * it, and posts one notification when the set of updatable apps changed since the last announce —
 * so upgrading does not re-notify for the same builds, and a newer patch of a known version still
 * does (the signature includes the patch version once the patch-update feature lands).
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val appRepository: AppRepository,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = preferencesManager.getAppConfig()
        if (!config.autoUpdateCheck) return Result.success()

        return runCatching {
            val result = appRepository.getApps(forceRefresh = true).first()
            val apps = (result as? com.app.manager.core.common.Result.Success)?.data
                ?: return Result.success()

            val updatable = apps.filter { app ->
                val installed = app.currentVersion ?: return@filter false
                VersionComparator.compare(installed, app.latestVersionName) < 0
            }
            if (updatable.isEmpty()) return Result.success()

            // Include the patch version in the signature so a newer patch of an already
            // announced version still notifies; app-version updates keep their old signature
            // until the version itself changes.
            val signature = updatable
                .sortedBy { it.packageName }
                .joinToString("|") { app ->
                    "${app.packageName}@${app.latestVersionName}+${app.patchVersion.orEmpty()}"
                }
            if (signature == preferencesManager.getLastNotifiedUpdateSignature()) {
                return Result.success()
            }

            postUpdateNotification(updatable.size)
            preferencesManager.setLastNotifiedUpdateSignature(signature)
            Result.success()
        }.getOrElse { error ->
            android.util.Log.w(TAG, "Update check failed", error)
            Result.retry()
        }
    }

    private fun postUpdateNotification(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = NotificationManagerCompat.from(applicationContext)
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.update_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(applicationContext.getString(R.string.update_notification_title, count))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 41
        private const val WORK_NAME = "update_check"

        /** Register the daily check (idempotent — KEEP keeps the existing schedule). */
        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
