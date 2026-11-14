package com.app.manager.data.manager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs an APK through the PackageInstaller session API.
 *
 * A session is created, the APK is streamed into it, and a status broadcast receiver turns the
 * commit result into [InstallationResult] values. The first attempt per install runs without the
 * user confirmation dialog where the platform allows it; when the ROM refuses that (some HyperOS
 * builds report their own refusal as an aborted session — issue #63), the bloc retries once with
 * the system confirmation dialog.
 */
@Singleton
class RevancedPackageInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _installationResults = MutableSharedFlow<InstallationResult>(extraBufferCapacity = 64)
    val installationResults: SharedFlow<InstallationResult> = _installationResults

    private val packageInstaller: PackageInstaller
        get() = context.packageManager.packageInstaller

    /** sessionId → (packageName, silent attempt?) — the status broadcast only carries the id. */
    private val activeSessions = ConcurrentHashMap<Int, SessionInfo>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiveContext: Context, intent: Intent) {
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val session = activeSessions.remove(sessionId)
            if (session == null) {
                Log.w(TAG, "Install status for unknown session $sessionId")
                return
            }
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "Install session $sessionId (${session.packageName}) finished: status=$status ${message.orEmpty()}")

            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    _installationResults.tryEmit(InstallationResult.Success(session.packageName))
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // The system asks us to show the confirmation dialog; keep the session
                    // mapped so its eventual result still resolves to this package.
                    activeSessions[sessionId] = session
                    startConfirmationIntent(intent)
                    _installationResults.tryEmit(InstallationResult.PendingUserAction(session.packageName))
                }
                else -> {
                    // A silent attempt the ROM refused looks exactly like an aborted install —
                    // flag it so the caller can retry once with the confirmation dialog (#63).
                    val refusedSilent = session.silent &&
                        (status == PackageInstaller.STATUS_FAILURE_ABORTED ||
                            status == PackageInstaller.STATUS_FAILURE_BLOCKED)
                    _installationResults.tryEmit(
                        InstallationResult.Failed(
                            packageName = session.packageName,
                            error = message ?: "Install failed with status $status",
                            statusCode = status,
                            silentInstallRefused = refusedSilent
                        )
                    )
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /**
     * Start an install session for [packageName] from the APK at [apkFilePath].
     *
     * @param requireUserAction force the system confirmation dialog even where a silent install
     *   would be allowed — used for the retry after a ROM refused the silent attempt (#63).
     * @return true when the session was committed.
     */
    fun installPackage(
        packageName: String,
        apkFilePath: String,
        requireUserAction: Boolean = false
    ): Boolean {
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) {
            _installationResults.tryEmit(
                InstallationResult.Failed(packageName, "APK file does not exist: $apkFilePath")
            )
            return false
        }

        return runCatching {
            if (hasActiveSessionFor(packageName)) {
                Log.w(TAG, "Install session already open for $packageName — not opening a second one")
                _installationResults.tryEmit(
                    InstallationResult.Failed(packageName, "Another install session is already open")
                )
                return false
            }

            // The int-form setRequireUserAction (and its constants) only exist from API 34; on
            // older releases the platform default applies, so the "silent" attempt is simply a
            // normal attempt there.
            val silent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !requireUserAction
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequireUserAction(
                        if (requireUserAction) PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    )
                }
            }
            val sessionId = packageInstaller.createSession(params)
            activeSessions[sessionId] = SessionInfo(packageName, silent)

            val session = packageInstaller.openSession(sessionId)
            try {
                session.openWrite(packageName, 0L, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            } catch (writeError: Throwable) {
                runCatching { session.abandon() }
                activeSessions.remove(sessionId)
                throw writeError
            }

            val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val statusSender = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(statusSender.intentSender)
            Log.i(TAG, "Install session $sessionId committed for $packageName (silent=$silent)")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to start install session for $packageName", error)
            _installationResults.tryEmit(
                InstallationResult.Failed(
                    packageName,
                    error.message ?: "Failed to start install session"
                )
            )
            false
        }
    }

    /** Whether a session we opened is still live for [packageName] (no terminal result yet). */
    fun hasActiveSessionFor(packageName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeSessions.values.any { it.packageName == packageName } ||
                runCatching {
                    packageInstaller.mySessions.any { info -> info.appPackageName == packageName && info.isActive }
                }.getOrDefault(false)
        } else {
            activeSessions.values.any { it.packageName == packageName }
        }
    }

    /**
     * Abandon sessions left open by a killed process — they would linger forever and later
     * surface as spurious aborted installs.
     */
    fun abandonOrphanedSessions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            packageInstaller.mySessions.forEach { info ->
                if (info.isActive && info.appPackageName == context.packageName) {
                    Log.w(TAG, "Abandoning orphaned install session ${info.sessionId} (${info.appPackageName})")
                    packageInstaller.abandonSession(info.sessionId)
                }
            }
        }
    }

    /** Cancel the live session for [packageName]. @return true when a session was cancelled. */
    fun cancelInstallation(packageName: String): Boolean {
        val entry = activeSessions.entries.firstOrNull { it.value.packageName == packageName }
        return if (entry != null) {
            runCatching { packageInstaller.abandonSession(entry.key) }
            activeSessions.remove(entry.key)
            true
        } else {
            false
        }
    }

    private fun startConfirmationIntent(statusIntent: Intent) {
        runCatching {
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                statusIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                statusIntent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmIntent)
            } else {
                Log.w(TAG, "Pending user action without a confirmation intent")
            }
        }.onFailure { Log.e(TAG, "Could not show install confirmation dialog", it) }
    }

    private data class SessionInfo(val packageName: String, val silent: Boolean)

    private companion object {
        const val TAG = "RevancedPackageInstaller"
        const val ACTION_INSTALL_STATUS = "com.app.manager.INSTALL_SESSION_STATUS"
    }
}
