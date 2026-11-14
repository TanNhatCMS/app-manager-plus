package com.app.manager.presentation.bloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.manager.R
import com.app.manager.core.common.LocaleHelper
import com.app.manager.core.common.Result
import com.app.manager.core.common.VersionComparator
import com.app.manager.core.common.StringProvider
import com.app.manager.data.local.preferences.PreferencesManager
import com.app.manager.data.manager.ApkSignatureVerifier
import com.app.manager.data.manager.AppManager
import com.app.manager.data.manager.DownloadService
import com.app.manager.data.manager.SimpleDownloadManager
import com.app.manager.data.manager.RevancedPackageInstaller
import com.app.manager.data.manager.InstallationResult
import com.app.manager.data.manager.PackageChangedReceiver
import com.app.manager.data.manager.PackageEvent
import com.app.manager.data.repository.DownloadStateRepository
import com.app.manager.domain.model.AppConfig
import com.app.manager.domain.model.AppStatus
import com.app.manager.domain.model.Language
import com.app.manager.domain.model.RevancedApp
import com.app.manager.domain.model.ThemeMode
import com.app.manager.domain.usecase.AppManagementUseCases
import com.app.manager.domain.usecase.GetAppsUseCase
import com.app.manager.domain.usecase.InstallAppUseCase
import com.app.manager.domain.usecase.OpenAppUseCase
import com.app.manager.domain.usecase.UninstallAppUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * BLoC for managing app state and handling user events
 * Improved version with better download/install flow and error handling
 */
@HiltViewModel
class AppBloc @Inject constructor(
    @ApplicationContext private val context: Context,
    private val useCases: AppManagementUseCases,
    private val simpleDownloadManager: SimpleDownloadManager,
    private val appManager: AppManager,
    private val preferencesManager: PreferencesManager,
    private val stringProvider: StringProvider,
    private val packageInstaller: RevancedPackageInstaller,
    private val installedBuildStore: com.app.manager.core.common.InstalledBuildStore,
    private val downloadStateRepository: DownloadStateRepository,
    private val packageChangedReceiver: PackageChangedReceiver
) : ViewModel(), DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppBloc"
        private const val MAX_LOG_ENTRIES = 500

        /** The package every MicroG build installs as — catalog entries flagged `requiresMicroG`
         *  need *a* build of it (MicroG RE and the ReVanced GmsCore both use this package). */
        const val MICROG_PACKAGE = "app.revanced.android.gms"
    }

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    private val actionLogs = mutableListOf<AppLogEntry>()
    private var currentLogDay = ""
    private val logDayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val logTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    @Volatile
    private var isDebugLoggingEnabled = true

    // Track concurrent download/install states
    private val activeDownloads = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val installationRetries = mutableMapOf<String, Int>()
    private val completedDownloads = mutableSetOf<String>()

    // Sequential installation queue — only one install dialog is shown at a time.
    private val installationQueue = mutableListOf<PendingInstallation>()
    private var isInstallationInProgress = false
    
    // Track if app was backgrounded to determine auto-install behavior
    private var wasAppBackgrounded = false

    /** Packages whose silent install was already retried with the system dialog (issue #63). */
    private val silentInstallRetried = mutableSetOf<String>()

    /** packageName → versionName of the last install success we already settled in the UI. */
    private val handledInstallSuccess = mutableMapOf<String, String>()
    
    // Track pending uninstalls/reinstalls for system event handling
    private val pendingReinstalls = mutableMapOf<String, String>() // packageName -> apkPath
    private val pendingReinstallDownloads = mutableMapOf<String, String>() // packageName -> downloadUrl

    /** The "N updates available" prompt is shown at most once per session. */
    private var updatePromptShownThisSession = false
    private val pendingUninstallChecks = mutableSetOf<String>() // packages waiting for uninstall confirmation
    
    // V2 vendor/beta filtering
    private var allFetchedApps: List<RevancedApp> = emptyList()
    private var selectedVendor: String? = null
    private val maxProgressPerDownload = ConcurrentHashMap<String, Float>()
    private val activeDownloadUrls = ConcurrentHashMap<String, String>()
    private var showBeta: Boolean = false
    private var favoriteSet: Set<String> = emptySet()
    
    data class PendingInstallation(
        val packageName: String,
        val filePath: String,
        val appName: String
    )
    
    // Broadcast receiver for download completion - enhanced for concurrent downloads
    private val downloadCompleteBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_DOWNLOAD_COMPLETE -> {
                    val packageName = intent.getStringExtra(DownloadService.EXTRA_PACKAGE_NAME)
                    val filePath = intent.getStringExtra(DownloadService.EXTRA_FILE_PATH)
                    
                    if (packageName != null && filePath != null) {
                        Log.i(TAG, "📥 Individual download complete broadcast: $packageName")
                        handleEvent(AppEvent.DownloadCompleted(packageName, filePath))
                    }
                }
                
                DownloadService.ACTION_ALL_DOWNLOADS_COMPLETE -> {
                    Log.i(TAG, "🎉 All downloads complete broadcast received")
                    
                    val completedPackages = intent.getStringArrayListExtra("completed_packages") ?: arrayListOf()
                    val completedNames = intent.getStringArrayListExtra("completed_names") ?: arrayListOf()
                    val completedPaths = intent.getStringArrayListExtra("completed_paths") ?: arrayListOf()
                    val failedPackages = intent.getStringArrayListExtra("failed_packages") ?: arrayListOf()
                    val failedNames = intent.getStringArrayListExtra("failed_names") ?: arrayListOf()
                    val failedErrors = intent.getStringArrayListExtra("failed_errors") ?: arrayListOf()
                    
                    // Auto-install all completed downloads immediately
                    handleEvent(AppEvent.AutoInstallAllCompleted(
                        completedPackages, completedNames, completedPaths,
                        failedPackages, failedNames, failedErrors
                    ))
                }
            }
        }
    }

    init {
        Log.i(TAG, "AppBloc initialized")
        handleEvent(AppEvent.LoadConfiguration)
        handleEvent(AppEvent.LoadAppsFromCacheFirst)  // Changed to load cache first

        // Sessions left over from a killed process would otherwise linger forever (and hold
        // storage), and later surface as spurious aborted installs.
        packageInstaller.abandonOrphanedSessions()

        // Listen for installation events from PackageInstaller
        setupPackageInstallerListener()
        
        // Listen for system package events (install/uninstall completion)
        packageChangedReceiver.register()
        setupPackageChangedListener()
        
        // Setup lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Register broadcast receiver for download completion
        registerDownloadBroadcastReceiver()
    }
    
    /**
     * Setup listener for Android system package change events
     * This provides more reliable detection than polling
     */
    private fun setupPackageChangedListener() {
        packageChangedReceiver.packageEvents
            .onEach { event ->
                when (event) {
                    is PackageEvent.Installed, is PackageEvent.Updated -> {
                        val packageName = when (event) {
                            is PackageEvent.Installed -> event.packageName
                            is PackageEvent.Updated -> event.packageName
                            else -> return@onEach
                        }
                        Log.i(TAG, "📦 System event: Package installed/updated: $packageName")
                        pendingUninstallChecks.remove(packageName)
                        val installedVersion = appManager.getInstalledVersionName(packageName)
                        if (installedVersion != null) {
                            handleInstallationSuccess(packageName, installedVersion)
                        }
                    }
                    is PackageEvent.Uninstalled -> {
                        Log.i(TAG, "🗑️ System event: Package uninstalled: ${event.packageName}")
                        pendingUninstallChecks.remove(event.packageName)
                        handledInstallSuccess.remove(event.packageName)
                        invalidateMicrogCache()
                        
                        // Check if this was part of a reinstall flow
                        val pendingPath = pendingReinstalls.remove(event.packageName)
                        val pendingUrl = pendingReinstallDownloads.remove(event.packageName)
                        if (pendingPath != null) {
                            Log.i(TAG, "🔄 Reinstall after uninstall: ${event.packageName}")
                            installApp(event.packageName, pendingPath)
                        } else if (pendingUrl != null) {
                            Log.i(TAG, "🔄 Re-downloading after uninstall: ${event.packageName}")
                            completedDownloads.remove(event.packageName)
                            updateAppStatus(event.packageName, AppStatus.DOWNLOADING)
                            downloadApp(event.packageName, pendingUrl)
                        } else {
                            updateAppStatus(event.packageName, AppStatus.NOT_INSTALLED)
                            showToast(stringProvider.getString(R.string.uninstallation_completed))
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Setup PackageInstaller listener for immediate and accurate installation feedback
     */
    private fun setupPackageInstallerListener() {
        packageInstaller.installationResults
            .onEach { result ->
                when (result) {
                    is InstallationResult.Success -> {
                        Log.i(TAG, "✅ Installation successful: ${result.packageName}")
                        recordInstalledBuild(result.packageName)
                        handleInstallationSuccess(result.packageName, appManager.getInstalledVersionName(result.packageName) ?: "Unknown")
                        // Clean up queue after successful installation
                        Log.i(TAG, "🔄 Installation success - cleaning up queue")
                        processNextInstallation()
                    }
                    
                    is InstallationResult.Failed -> {
                        Log.w(TAG, "❌ Installation failed: ${result.packageName}, error: ${result.error} (status=${result.statusCode})")
                        val reason = com.app.manager.domain.model.InstallFailureReason.from(result.statusCode, result.error)

                        // A silent attempt the ROM refused gets exactly one retry with the system
                        // confirmation dialog, inside this queue turn — the next queued install
                        // must not start alongside it, and the APK on disk is still good so
                        // nothing is re-downloaded (#63).
                        val pendingPath = installationQueue.find { it.packageName == result.packageName }?.filePath
                        if (result.silentInstallRefused && pendingPath != null &&
                            silentInstallRetried.add(result.packageName)
                        ) {
                            Log.i(TAG, "ROM refused silent install of ${result.packageName} — retrying with system confirmation")
                            installAppDirect(result.packageName, pendingPath, requireUserAction = true)
                            return@onEach  // stay in the same queue turn
                        }

                        when (reason) {
                            com.app.manager.domain.model.InstallFailureReason.ABORTED -> {
                                Log.i(TAG, "🚫 Installation aborted for: ${result.packageName}, not showing retry dialog")
                                handleInstallationAborted(result.packageName, result.error)
                            }
                            // Uninstall-and-retry only makes sense when the old build blocks the
                            // install (signature clash, downgrade) — its dialog offers that.
                            com.app.manager.domain.model.InstallFailureReason.SIGNATURE_CONFLICT,
                            com.app.manager.domain.model.InstallFailureReason.VERSION_DOWNGRADE ->
                                handleInstallationFailedWithRetry(result.packageName, result.error)
                            else -> handleInstallationFailureClassified(result.packageName, result.error, reason)
                        }

                        // Clean up queue after failed installation
                        Log.i(TAG, "🔄 Installation failed - cleaning up queue")
                        processNextInstallation()
                    }
                    
                    is InstallationResult.PendingUserAction -> {
                        Log.i(TAG, "⏳ User action required for: ${result.packageName}")
                        showToast(stringProvider.getString(R.string.installation_pending_user_action))
                        
                        // Start a timeout to handle user cancellation/inaction - but only when app is in foreground
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(30000) // 30 seconds timeout
                            
                            // 🔥 ONLY process timeout if app is currently in foreground
                            if (!wasAppBackgrounded) {
                                // Check if this specific installation is still pending
                                val stillPending = installationQueue.any { it.packageName == result.packageName }
                                if (stillPending) {
                                    Log.w(TAG, "⏰ Installation timeout for: ${result.packageName}, assuming user cancelled")
                                    handleInstallationAborted(result.packageName, "User cancelled or timeout")
                                    processNextInstallation()
                                }
                            } else {
                                Log.i(TAG, "⏳ App is backgrounded, skipping timeout cleanup for: ${result.packageName}")
                            }
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }
    
    /**
     * Register broadcast receiver for download completion events - enhanced for concurrent downloads
     */
    private fun registerDownloadBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_DOWNLOAD_COMPLETE)
            addAction(DownloadService.ACTION_ALL_DOWNLOADS_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadCompleteBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadCompleteBroadcastReceiver, filter)
        }
        Log.i(TAG, "📡 Enhanced download broadcast receiver registered")
    }
    
    /**
     * Lifecycle event - App moved to foreground
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.i(TAG, "🌟 === APP MOVED TO FOREGROUND === 🌟")
        Log.i(TAG, "📱 Owner: ${owner.javaClass.simpleName}")
        Log.i(TAG, "📱 Was backgrounded: $wasAppBackgrounded")
        
        if (wasAppBackgrounded) {
            Log.i(TAG, "🔄 App was backgrounded, checking pending downloads...")
            checkPendingDownloadsOnForeground()
            
            // Check pending uninstalls that might have completed in background
            checkPendingUninstallsOnForeground()
        } else {
            Log.i(TAG, "🔄 Fresh app start, resetting all queues and pending downloads...")
            resetAllQueuesAndPendingDownloads()
        }
        
        // Reset background flag
        wasAppBackgrounded = false

        maybeShowUpdatePrompt()
    }
    
    /**
     * Check for pending uninstalls that may have completed while app was backgrounded
     */
    private fun checkPendingUninstallsOnForeground() {
        viewModelScope.launch {
            pendingUninstallChecks.toList().forEach { packageName ->
                if (!appManager.isAppInstalled(packageName)) {
                    Log.i(TAG, "📦 Background uninstall detected: $packageName")
                    pendingUninstallChecks.remove(packageName)
                    val path = pendingReinstalls.remove(packageName)
                    val downloadUrl = pendingReinstallDownloads.remove(packageName)
                    if (path != null) {
                        Log.i(TAG, "🔄 Reinstall after background uninstall: $packageName")
                        installApp(packageName, path)
                    } else if (downloadUrl != null) {
                        Log.i(TAG, "🔄 Re-downloading after background uninstall: $packageName")
                        completedDownloads.remove(packageName)
                        updateAppStatus(packageName, AppStatus.DOWNLOADING)
                        downloadApp(packageName, downloadUrl)
                    } else {
                        updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                        showToast(stringProvider.getString(R.string.uninstallation_completed))
                    }
                }
            }
        }
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.i(TAG, "⭐ === APP MOVED TO BACKGROUND === ⭐")
        wasAppBackgrounded = true
    }
    
    /**
     * Reset all queues and pending downloads on fresh app start
     */
    private fun resetAllQueuesAndPendingDownloads() {
        Log.i(TAG, "🗑️ === RESETTING ALL QUEUES AND PENDING DOWNLOADS ===")
        
        viewModelScope.launch {
            try {
                // Clear installation queue
                clearInstallationQueue()
                
                // Clear all pending download states
                downloadStateRepository.clearAllDownloadStates()
                Log.i(TAG, "🗑️ Cleared all download states from database")
                
                // Reset all app statuses to UPDATE_AVAILABLE if they were in downloading/installing states
                val currentState = _state.value
                if (currentState is AppState.Success) {
                    val updatedApps = currentState.apps.map { app ->
                        when (app.status) {
                            AppStatus.DOWNLOADING,
                            AppStatus.INSTALLING -> {
                                // Resolve the actual install state so first-time installs show
                                // NOT_INSTALLED and updates show UPDATE_AVAILABLE.
                                val actual = resolveActualStatus(app.packageName)
                                Log.i(TAG, "🔄 Resetting ${app.packageName} from ${app.status} to $actual")
                                app.copy(status = actual, downloadProgress = 0f)
                            }
                            else -> app
                        }
                    }
                    _state.value = currentState.copy(apps = updatedApps)
                }
                
                Log.i(TAG, "✅ All queues and pending downloads reset successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error resetting queues and pending downloads", e)
            }
        }
    }
    
    /**
     * Check for pending downloads when app comes to foreground
     * Enhanced to force-check download service status and handle missed broadcasts
     */
    private fun checkPendingDownloadsOnForeground() {
        Log.i(TAG, "🔍 === CHECKING PENDING DOWNLOADS ON FOREGROUND ===")
        
        viewModelScope.launch {
            try {
                // Debug auto-install preference first
                val isAutoInstallEnabled = preferencesManager.isAutoInstallEnabled()
                Log.i(TAG, "🔧 Auto-install enabled: $isAutoInstallEnabled")
                
                // 🔥 NEW: Force check download service progress to catch missed broadcasts
                Log.i(TAG, "🔍 Force checking download service status...")
                checkDownloadServiceProgress()
                
                // Small delay to let service updates process
                delay(1000)
                
                val completedDownloads = downloadStateRepository.getCompletedDownloads()
                val activeDownloads = downloadStateRepository.getActiveDownloads()
                
                Log.i(TAG, "📊 Found ${completedDownloads.size} completed downloads, ${activeDownloads.size} active downloads")
                
                // Debug completed downloads details
                completedDownloads.forEachIndexed { index, download ->
                    Log.i(TAG, "📦 Completed download [$index]: ${download.packageName}")
                    Log.i(TAG, "    📂 File path: ${download.filePath}")
                    Log.i(TAG, "    📅 Created: ${download.createdAt}")
                    Log.i(TAG, "    📅 Updated: ${download.updatedAt}")
                    
                    // Check if file exists
                    val fileExists = download.filePath?.let { java.io.File(it).exists() } ?: false
                    Log.i(TAG, "    📁 File exists: $fileExists")
                }
                
                // 🔥 Process completed downloads immediately
                if (completedDownloads.isNotEmpty()) {
                    Log.i(TAG, "🚀 Processing ${completedDownloads.size} completed downloads immediately")
                    
                    completedDownloads.forEach { download ->
                        Log.i(TAG, "⚡ Triggering installation for completed download: ${download.packageName}")
                        
                        // Trigger installation immediately
                        queueInstallation(download.packageName, download.filePath!!, download.appName)
                    }
                } else {
                    Log.i(TAG, "📭 No completed downloads found")
                }
                
                // Handle active downloads that might have been interrupted or need status update
                activeDownloads.forEach { download ->
                    Log.w(TAG, "🔄 Found active download, checking status: ${download.packageName}")
                    
                    // Check if file actually exists and is complete
                    val filePath = download.filePath
                    if (filePath != null && java.io.File(filePath).exists()) {
                        Log.i(TAG, "📦 Active download appears complete, marking as completed: ${download.packageName}")
                        // Mark as completed and trigger installation
                        downloadStateRepository.markDownloadCompleted(download.packageName, filePath)
                        queueInstallation(download.packageName, filePath, download.appName)
                    } else {
                        Log.w(TAG, "❌ Active download file missing or incomplete, cleaning up: ${download.packageName}")
                        // Reset UI state - user can retry if needed
                        updateAppStatus(download.packageName, AppStatus.UPDATE_AVAILABLE)
                        // Clean up the interrupted download state
                        downloadStateRepository.removeDownloadState(download.packageName)
                    }
                }
                
                // Clean up old failed downloads
                downloadStateRepository.cleanupOldFailedDownloads()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking pending downloads", e)
            }
        }
    }
    
    /**
     * Force check download service progress to catch missed broadcasts
     */
    private fun checkDownloadServiceProgress() {
        try {
            Log.i(TAG, "🔍 Checking SimpleDownloadManager for active downloads...")
            
            // Get current download progress from SimpleDownloadManager
            val activeDownloads = simpleDownloadManager.getActiveDownloads()
            
            Log.i(TAG, "📊 SimpleDownloadManager reports ${activeDownloads.size} active downloads")
            
            activeDownloads.forEach { (packageName, download) ->
                Log.i(TAG, "📥 Active download: $packageName - Progress: ${download.progress}% - Complete: ${download.isComplete}")
                
                if (download.isComplete && download.filePath != null) {
                    Log.i(TAG, "🎉 Found completed download that may have missed broadcast: $packageName")

                    // Skip if the app is already installed or mid-install — this prevents a
                    // re-install when the user returns to the foreground within the 5-second
                    // window that SimpleDownloadManager keeps completed entries.
                    val currentStatus = (_state.value as? AppState.Success)
                        ?.apps?.find { it.packageName == packageName }?.status
                    if (currentStatus == AppStatus.UP_TO_DATE ||
                        currentStatus == AppStatus.INSTALLING ||
                        installationQueue.any { it.packageName == packageName }) {
                        Log.i(TAG, "⏭️ Skipping already handled/installing package: $packageName (status=$currentStatus)")
                    } else {
                        // Trigger download completion handling
                        handleEvent(AppEvent.DownloadCompleted(packageName, download.filePath!!))
                    }
                } else if (!download.isComplete) {
                    Log.i(TAG, "🔄 Download still in progress: $packageName (${download.progress}%)")
                    
                    // Update UI progress
                    updateAppProgress(packageName, download.progress)
                    updateAppStatus(packageName, AppStatus.DOWNLOADING)
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error checking download service progress", e)
        }
    }
    
    /**
     * Clean up resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(downloadCompleteBroadcastReceiver)
            Log.i(TAG, "Download broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister broadcast receiver", e)
        }
        
        // Unregister PackageChangedReceiver
        packageChangedReceiver.unregister()
    }

    /**
     * Handle incoming events
     */
    // --- V2 vendor/beta filtering helpers ---

    private fun filterAppsByVendorAndBeta(apps: List<RevancedApp>): List<RevancedApp> {
        val vendor = selectedVendor
        if (vendor == null) return apps
        return apps.filter { app ->
            val isManager = app.vendor == null
            val matchesVendor = isManager || app.vendor == vendor
            val passesBeta = showBeta || app.channel != "beta"
            matchesVendor && passesBeta
        }
    }

    private fun selectVendor(vendor: String) {
        Log.i(TAG, "Vendor selected: $vendor")
        selectedVendor = vendor
        preferencesManager.setSelectedVendor(vendor)
        updateConfigInState { it.copy(vendor = vendor) }
        reapplyVendorFilter()
    }

    private fun toggleShowBeta(enabled: Boolean) {
        Log.i(TAG, "Show beta toggled: $enabled")
        showBeta = enabled
        preferencesManager.setShowBeta(enabled)
        updateConfigInState { it.copy(showBeta = enabled) }
        reapplyVendorFilter()
    }

    private fun clearDownloadCache() {
        val deletedCount = cleanupDownloadCache()
        showToast(stringProvider.getString(R.string.cache_cleared, deletedCount))
        Log.i(TAG, "🗑️ Cache cleared: $deletedCount files deleted")
    }

    private fun cleanupDownloadCache(): Int {
        var count = 0
        // Clean internal cache
        val internalDir = File(context.cacheDir, "downloads")
        if (internalDir.exists()) {
            internalDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk")) {
                    if (file.delete()) count++
                }
            }
        }
        // Clean configured download path
        val config = preferencesManager.getAppConfig()
        if (config.downloadPath.isNotBlank()) {
            val downloadDir = if (config.downloadPath.startsWith("content://")) {
                null // Skip SAF paths for cache cleanup
            } else {
                File(config.downloadPath)
            }
            downloadDir?.let { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".apk")) {
                            if (file.delete()) count++
                        }
                    }
                }
            }
        }
        return count
    }

    private fun toggleFavorite(packageName: String) {
        val added = preferencesManager.toggleFavorite(packageName)
        favoriteSet = preferencesManager.getFavorites()
        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(favorites = favoriteSet)
        }
        showToast(stringProvider.getString(
            if (added) R.string.favorite_added else R.string.favorite_removed
        ))
    }

    private fun reapplyVendorFilter() {
        val filtered = filterAppsByVendorAndBeta(allFetchedApps)
        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(apps = filtered)
        }
    }

    private fun storeAndFilter(apps: List<RevancedApp>, config: AppConfig): List<RevancedApp> {
        allFetchedApps = apps
        selectedVendor = config.vendor
        showBeta = config.showBeta
        favoriteSet = preferencesManager.getFavorites()
        return filterAppsByVendorAndBeta(apps)
    }

    private fun updateConfigInState(transform: (AppConfig) -> AppConfig) {
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(config = transform(currentState.config))
            }
            is AppState.Error -> {
                _state.value = currentState.copy(config = transform(currentState.config))
            }
            else -> {}
        }
    }

    fun handleEvent(event: AppEvent) {
        Log.d(TAG, "Handling event: ${event::class.simpleName}")
        logEvent(event)
        when (event) {
            is AppEvent.LoadApps -> loadApps(forceRefresh = false)
            is AppEvent.RefreshApps -> loadApps(forceRefresh = true)
            is AppEvent.LoadAppsFromCacheFirst -> loadAppsFromCacheFirst()
            is AppEvent.BackgroundRefreshApps -> backgroundRefreshApps()
            is AppEvent.UpdateSingleApp -> updateSingleApp(event.app)
            is AppEvent.DownloadApp -> downloadApp(event.packageName, event.downloadUrl)
            is AppEvent.InstallApp -> installApp(event.packageName, event.apkFilePath)
            is AppEvent.UninstallApp -> {
                if (shouldWarnBeforeUninstall(event.packageName)) {
                    showMicrogUninstallWarning(event.packageName)
                } else {
                    uninstallApp(event.packageName)
                }
            }
            is AppEvent.ConfirmUninstallMicroG -> uninstallApp(event.packageName)
            is AppEvent.ConfirmMicroGSuggestion -> {
                handleEvent(AppEvent.DismissDialog)
                event.apps.forEach { app -> downloadApp(app.packageName, app.downloadUrl) }
            }
            is AppEvent.ShowReinstallConfirmation -> showReinstallConfirmation(event.packageName)
            is AppEvent.ReinstallApp -> reinstallApp(event.packageName)
            is AppEvent.OpenApp -> openApp(event.packageName)
            is AppEvent.UpdateAppProgress -> updateAppProgress(event.packageName, event.progress)
            is AppEvent.UpdateAppStatus -> updateAppStatus(event.packageName, event.status)
            is AppEvent.ShowError -> showError(event.message)
            is AppEvent.RetryInstallation -> retryInstallation(event.packageName, event.apkFilePath, event.shouldUninstallFirst)
            is AppEvent.CancelDownload -> cancelDownload(event.packageName)
            is AppEvent.ConfirmUninstallBeforeReinstall -> confirmUninstallBeforeReinstall(event.packageName, event.apkFilePath)
            is AppEvent.DismissDialog -> dismissDialog()
            is AppEvent.DismissDialogAndUpdateStatus -> {
                dismissDialog()
                updateAppStatus(event.packageName, event.status)
            }

            is AppEvent.DownloadCompleted -> handleDownloadCompleted(event.packageName, event.filePath)
            is AppEvent.DownloadFailed -> handleDownloadFailed(event.packageName, event.error)
            is AppEvent.InstallationCompleted -> handleInstallationCompleted(event.packageName, event.success)
            is AppEvent.ShowConfirmationDialog -> showConfirmationDialog(event.title, event.message, event.onConfirm, event.onCancel)
            is AppEvent.ShowLogsDialog -> showLogsDialog()
            is AppEvent.ClearLogs -> clearLogs()
            is AppEvent.SaveConfiguration -> saveConfiguration(event.config)
            is AppEvent.UpdateCompactMode -> updateCompactMode(event.enabled)
            is AppEvent.LoadConfiguration -> loadConfiguration()
            
            // Concurrent downloads events - simplified for auto-install
            is AppEvent.AutoInstallAllCompleted -> autoInstallAllCompleted(
                event.completedPackages, event.completedNames, event.completedPaths,
                event.failedPackages, event.failedNames, event.failedErrors
            )
            
            // Vendor & beta events (V2)
            is AppEvent.SelectVendor -> selectVendor(event.vendor)
            is AppEvent.ToggleShowBeta -> toggleShowBeta(event.enabled)
            is AppEvent.ClearDownloadCache -> clearDownloadCache()
            is AppEvent.ToggleFavorite -> toggleFavorite(event.packageName)
            
            // Search events
            is AppEvent.SearchApps -> searchApps(event.query)
            is AppEvent.ClearSearch -> clearSearch()

            // Filter events
            is AppEvent.SetFilter -> setFilter(event.filter)
            is AppEvent.SetSort -> setSort(event.sort)
            is AppEvent.UpdateAll -> updateAll()
            is AppEvent.InstallSelectedUpdates -> {
                handleEvent(AppEvent.DismissDialog)
                event.apps.forEach { app -> downloadApp(app.packageName, app.downloadUrl) }
            }
            is AppEvent.SkipUpdatePromptToday -> {
                preferencesManager.setUpdatePromptSnoozedDate(java.time.LocalDate.now().toString())
                handleEvent(AppEvent.DismissDialog)
            }
            is AppEvent.TurnOffUpdatePrompt -> turnOffUpdatePrompt()
        }
    }
    
    /**
     * Search apps by query (title or package name)
     */
    private fun searchApps(query: String) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(searchQuery = query)
        }
    }
    
    /**
     * Clear search query
     */
    private fun clearSearch() {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(searchQuery = "")
        }
    }

    /**
     * Set the active filter option
     */
    private fun setFilter(filter: AppFilterOption) {
        preferencesManager.setFilterOption(filter.name)
        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(filterOption = filter)
        }
    }

    private fun setSort(sort: AppSortOption) {
        preferencesManager.setSortOption(sort.name)
        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(sortOption = sort)
        }
    }

    /** Restore the persisted list filter — PROCESSING-style transient filters stay un-restored. */
    private fun restoreFilterOption(): AppFilterOption =
        AppFilterOption.entries.find { it.name == preferencesManager.getFilterOption() }
            ?: AppFilterOption.ALL

    private fun restoreSortOption(): AppSortOption =
        AppSortOption.entries.find { it.name == preferencesManager.getSortOption() }
            ?: AppSortOption.CATALOG

    /**
     * Load apps from cache first for fast UI, then trigger background refresh
     */
    private fun loadAppsFromCacheFirst() {
        Log.i(TAG, "Loading apps from cache first for fast UI")
        addActionLog("INFO", "LoadAppsFromCacheFirst started")
        viewModelScope.launch {
            useCases.appRepository.getAppsFromCacheImmediately()
                .onEach { result ->
                    when (result) {
                        is Result.Loading -> {
                            Log.d(TAG, "No cache available, loading from network")
                            _state.value = AppState.Loading
                            // If no cache, fallback to normal loading
                            loadApps(forceRefresh = false)
                        }
                        is Result.Success -> {
                            Log.i(TAG, "Cache loaded successfully: ${result.data.size} items")
                            addActionLog("INFO", "Cache loaded: ${result.data.size} apps")
                            val config = try {
                                preferencesManager.getAppConfig()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load config, using fallback", e)
                                AppConfig(ThemeMode.DARK, Language.VIETNAMESE)
                            }
                            _state.value = AppState.Success(
                                storeAndFilter(result.data, config),
                                filterOption = restoreFilterOption(),
                                sortOption = restoreSortOption(),
                                config = config,
                                favorites = favoriteSet
                            )
                            
                            // Start background refresh after a short delay to let UI render
                            viewModelScope.launch {
                                delay(500) // 500ms delay to let UI stabilize
                                Log.i(TAG, "Starting background refresh after UI render")
                                handleEvent(AppEvent.BackgroundRefreshApps)
                            }
                        }
                        is Result.Error -> {
                            Log.e(TAG, "Failed to load cached apps", result.exception)
                            logException("Load cached apps failed", result.exception)
                            // If cache loading fails, fallback to normal loading
                            loadApps(forceRefresh = false)
                        }
                    }
                }
                .launchIn(this)
        }
    }

    /**
     * Background refresh apps and update UI smoothly
     */
    private fun backgroundRefreshApps() {
        Log.i(TAG, "Background refresh starting")
        addActionLog("INFO", "Background refresh started")
        viewModelScope.launch {
            useCases.appRepository.backgroundRefreshApps()
                .onEach { result ->
                    when (result) {
                        is Result.Loading -> {
                            Log.d(TAG, "Background refresh in progress...")
                            // Don't show loading state during background refresh
                        }
                        is Result.Success -> {
                            Log.i(TAG, "Background refresh completed: ${result.data.size} items")
                            addActionLog("INFO", "Background refresh completed: ${result.data.size} apps")
                            
                            val currentState = _state.value
                            if (currentState is AppState.Success) {
                                val oldApps = currentState.apps
                                val newApps = result.data
                                
                                // Find which apps have been updated
                                val updatedApps = useCases.appRepository.getUpdatedApps(oldApps, newApps)
                                
                                if (updatedApps.isNotEmpty()) {
                                    Log.i(TAG, "Found ${updatedApps.size} updated apps, updating UI smoothly")
                                    
                                    // Update the entire list with smooth transition
                                    val config = try {
                                        preferencesManager.getAppConfig()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to load config, using fallback", e)
                                        AppConfig(ThemeMode.DARK, Language.VIETNAMESE)
                                    }
                                    
                                    _state.value = AppState.Success(
                                    storeAndFilter(newApps, config),
                                    filterOption = restoreFilterOption(),
                                    sortOption = restoreSortOption(),
                                    config = config,
                                    favorites = favoriteSet
                                )
                                    
                                    // Show a subtle toast if there are significant updates
                                    if (updatedApps.size > 1) {
                                        showToast(stringProvider.getString(R.string.apps_updated, updatedApps.size))
                                    }
                                } else {
                                    Log.d(TAG, "No app updates found during background refresh")
                                }

                                // The network-backed load finished — now the prompt may list the
                                // builds actually published (cache-only launch loads are excluded).
                                maybeShowUpdatePrompt()
                            } else {
                                Log.w(TAG, "Background refresh completed but current state is not Success")
                            }
                        }
                        is Result.Error -> {
                            Log.w(TAG, "Background refresh failed, keeping current state", result.exception)
                            logException("Background refresh failed", result.exception)
                            // Don't update UI on background refresh failure
                            // User will still see cached data
                        }
                    }
                }
                .launchIn(this)
        }
    }

    /**
     * Update a single app in the current list
     */
    private fun updateSingleApp(updatedApp: RevancedApp) {
        Log.d(TAG, "Updating single app: ${updatedApp.packageName}")
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == updatedApp.packageName) {
                    updatedApp
                } else {
                    app
                }
            }
            _state.value = currentState.copy(apps = updatedApps)
            Log.d(TAG, "Single app updated in UI: ${updatedApp.packageName}")
        }
    }

    /**
     * Load apps from repository
     */
    private fun loadApps(forceRefresh: Boolean) {
        Log.i(TAG, "Loading apps, forceRefresh: $forceRefresh")
        addActionLog("INFO", "LoadApps started (forceRefresh=$forceRefresh)")
        viewModelScope.launch {
            useCases.getAppsUseCase(forceRefresh)
                .onEach { result ->
                    when (result) {
                        is Result.Loading -> {
                            Log.d(TAG, "Apps loading...")
                            _state.value = AppState.Loading
                        }
                        is Result.Success -> {
                            Log.i(TAG, "Apps loaded successfully: ${result.data.size} items")
                            addActionLog("INFO", "LoadApps success: ${result.data.size} apps")
                            // Ensure config is included when transitioning to Success state
                            val config = try {
                                preferencesManager.getAppConfig()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load config during app loading, using fallback", e)
                                AppConfig(ThemeMode.DARK, Language.VIETNAMESE)
                            }
                            _state.value = AppState.Success(
                                storeAndFilter(result.data, config),
                                filterOption = restoreFilterOption(),
                                sortOption = restoreSortOption(),
                                config = config,
                                favorites = favoriteSet
                            )
                            maybeSuggestMicroG(result.data)
                        }
                        is Result.Error -> {
                            Log.e(TAG, "Failed to load apps", result.exception)
                            logException("Load apps failed", result.exception)
                            // Keep main UI visible even when network/app source fails.
                            // We show the error as toast and stay in Success state.
                            val config = try {
                                preferencesManager.getAppConfig()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load config during app error, using fallback", e)
                                AppConfig(ThemeMode.DARK, Language.VIETNAMESE)
                            }
                            val currentState = _state.value
                            when (currentState) {
                                is AppState.Success -> {
                                    _state.value = currentState.copy(
                                        config = config,
                                        dialogState = null
                                    )
                                }
                                else -> {
                                    _state.value = AppState.Success(
                                        apps = emptyList(),
                                        config = config,
                                        logs = getCurrentLogs()
                                    )
                                }
                            }
                            showError(result.message, result.exception)
                        }
                    }
                }
                .launchIn(this)
        }
    }

    @Volatile
    private var microgInstalledCache: Boolean? = null

    /** True when no MicroG build is on the device (result cached between installs). */
    fun isMicrogMissing(): Boolean {
        if (microgInstalledCache == null) {
            microgInstalledCache = appManager.isAppInstalled(MICROG_PACKAGE)
        }
        return microgInstalledCache == false
    }

    private fun invalidateMicrogCache() {
        microgInstalledCache = null
    }

    /**
     * Remember which patches the build this manager just installed carries, so the patch-update
     * check knows exactly instead of going by install time (see PatchUpdate). Only from our own
     * PackageInstaller result — a PACKAGE_ADDED broadcast can be anyone's install. The record is
     * written only when the installed version is the owner entry's build, so a catalog that moved
     * to a new app version records nothing rather than a wrong pair.
     */
    private fun recordInstalledBuild(packageName: String) {
        val versionName = appManager.getInstalledVersionName(packageName) ?: return
        val lastUpdateTime = appManager.getInstalledLastUpdateTime(packageName) ?: 0L
        val app = (_state.value as? AppState.Success)?.apps?.find { it.packageName == packageName } ?: return
        val patchVersion = app.patchVersion ?: return
        if (VersionComparator.compare(versionName, app.latestVersionName) != 0) return
        installedBuildStore.put(
            packageName,
            com.app.manager.core.common.InstalledBuildRecord(
                versionName = versionName,
                patchVersion = patchVersion,
                lastUpdateTime = lastUpdateTime
            )
        )
        Log.i(TAG, "Recorded installed build for $packageName: v$versionName patches $patchVersion")
    }

    /**
     * Offer to install MicroG on first run when catalog apps need it and none is on the device
     * (upstream SuggestionsDialog). Offered once per installation — dismissed forever after.
     */
    private fun maybeSuggestMicroG(apps: List<RevancedApp>) {
        if (preferencesManager.isMicrogSuggestionShown()) return
        if (!apps.any { it.requiresMicroG }) return
        if (appManager.isAppInstalled(MICROG_PACKAGE)) return

        val microgEntries = apps.filter { it.packageName == MICROG_PACKAGE }
        if (microgEntries.isEmpty()) return

        preferencesManager.setMicrogSuggestionShown()
        Log.i(TAG, "MicroG missing but catalog apps need it — showing suggestion dialog")

        val currentState = _state.value
        if (currentState is AppState.Success) {
            _state.value = currentState.copy(
                dialogState = DialogState.Suggestions(
                    apps = microgEntries,
                    onConfirm = { selected -> handleEvent(AppEvent.ConfirmMicroGSuggestion(selected)) },
                    onDismiss = { handleEvent(AppEvent.DismissDialog) }
                )
            )
        }
    }

    /**
     * Download (then sequentially install) every app that currently has an update available —
     * the "Update all" banner action. Downloads run in parallel; the install queue keeps the
     * system dialogs strictly one at a time.
     */
    private fun updateAll() {
        val currentState = _state.value
        val updatable = (currentState as? AppState.Success)
            ?.apps?.filter { it.status == AppStatus.UPDATE_AVAILABLE }
            .orEmpty()
        if (updatable.isEmpty()) return

        Log.i(TAG, "Update all: starting ${updatable.size} download(s)")
        showToast(
            stringProvider.getString(R.string.updating_count, updatable.size)
        )
        updatable.forEach { app -> downloadApp(app.packageName, app.downloadUrl) }
    }

    /**
     * Show the "N updates available" prompt at most once per session, unless it was snoozed for
     * today, disabled in settings, or another popup is on screen. Only after a network-backed
     * catalog load: the launch-time cache read may predate the published builds.
     */
    private fun maybeShowUpdatePrompt() {
        if (updatePromptShownThisSession) return
        val currentState = _state.value
        if (currentState !is AppState.Success) return
        if (!currentState.config.showUpdatePrompt) return
        if (currentState.dialogState != null) return

        val updatable = currentState.apps.filter { it.status == AppStatus.UPDATE_AVAILABLE }
        if (updatable.isEmpty()) return

        val today = java.time.LocalDate.now().toString()
        if (preferencesManager.getUpdatePromptSnoozedDate() == today) return

        Log.i(TAG, "Showing update prompt: ${updatable.size} update(s) available")
        updatePromptShownThisSession = true
        _state.value = currentState.copy(
            dialogState = DialogState.UpdatePrompt(
                apps = updatable,
                onInstallSelected = { selected -> handleEvent(AppEvent.InstallSelectedUpdates(selected)) },
                onSkipToday = { handleEvent(AppEvent.SkipUpdatePromptToday) },
                onTurnOff = { handleEvent(AppEvent.TurnOffUpdatePrompt) },
                onDismiss = { handleEvent(AppEvent.DismissDialog) }
            )
        )
    }

    /**
     * "Don't show again" inside the prompt: flip the launch-popup setting off so the user who
     * never wants to be asked does not have to find it in Settings. Card statuses and the daily
     * notification are untouched.
     */
    private fun turnOffUpdatePrompt() {
        val config = runCatching { preferencesManager.getAppConfig() }
            .getOrElse { AppConfig(ThemeMode.DARK, Language.VIETNAMESE) }
            .copy(showUpdatePrompt = false)
        preferencesManager.saveAppConfig(config)
        when (val s = _state.value) {
            is AppState.Success -> _state.value = s.copy(config = config, dialogState = null)
            is AppState.Error -> _state.value = s.copy(config = config)
            else -> Unit
        }
        showToast(stringProvider.getString(R.string.update_prompt_turned_off))
    }

    /**
     * Warn before uninstalling MicroG while catalog apps that need it are still installed.
     * @return true when the uninstall may proceed straight away.
     */
    private fun shouldWarnBeforeUninstall(packageName: String): Boolean {
        if (packageName != MICROG_PACKAGE) return false
        val currentState = _state.value
        val dependentInstalled = (currentState as? AppState.Success)?.apps?.any { app ->
            app.requiresMicroG && appManager.isAppInstalled(app.packageName)
        } == true
        return dependentInstalled
    }

    /**
     * Download an app using modern WorkManager-based system
     */
    private fun downloadApp(packageName: String, downloadUrl: String) {
        // A download that we cannot install afterwards only wastes storage and bandwidth: gate
        // on the "Install unknown apps" permission before starting (upstream 15fe35e).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Log.w(TAG, "Install-unknown-apps permission missing — download blocked for $packageName")
            showToast(stringProvider.getString(R.string.install_permission_missing))
            return
        }

        // Reuse an already-present APK instead of downloading it again (fixes #22): the file
        // from a previous session or download goes straight into the install queue.
        getDownloadPath(packageName)?.let { existingPath ->
            Log.i(TAG, "APK already present for $packageName at $existingPath — skipping download")
            val currentState = _state.value
            val appName = (currentState as? AppState.Success)
                ?.apps?.find { it.packageName == packageName }?.title ?: packageName
            updateAppStatus(packageName, AppStatus.DOWNLOADING)
            updateAppProgress(packageName, 1f)
            queueInstallation(packageName, existingPath, appName)
            showToast(stringProvider.getString(R.string.download_completed_installing))
            return
        }

        activeDownloadUrls[packageName] = downloadUrl
        Log.i(TAG, "Starting download: $packageName")
        Log.d(TAG, "Download URL: $downloadUrl")
        
        // Cancel any existing download for this package
        cancelDownload(packageName)

        // Clear completed downloads tracking to allow re-download and installation
        completedDownloads.remove(packageName)
        Log.i(TAG, "🗑️ Cleared completed downloads tracking for: $packageName")

        // Remove from installation queue — if a previous download queued an install with an old
        // APK path, that entry must be removed so the new download's path is used instead.
        installationQueue.removeAll { it.packageName == packageName }
        Log.i(TAG, "🗑️ Removed stale queue entry for: $packageName")
        
        // Storage space check is handled in the service
        // Proceed directly to download
        
        val downloadJob = viewModelScope.launch {
            try {
                // Update app status to downloading
                updateAppStatus(packageName, AppStatus.DOWNLOADING)
                updateAppProgress(packageName, 0f)
                
                // Show starting message
                showToast(stringProvider.getString(R.string.download_starting))
                Log.i(TAG, "Download starting for: $packageName")
                
                simpleDownloadManager.downloadApp(packageName, downloadUrl)
                    .catch { error ->
                        Log.e(TAG, "Download failed for $packageName", error)
                        val errorMessage = when {
                            error.message?.contains("insufficient memory", ignoreCase = true) == true ||
                            error.message?.contains("OutOfMemoryError", ignoreCase = true) == true -> {
                                stringProvider.getString(R.string.download_failed_memory)
                            }
                            error.message?.contains("space", ignoreCase = true) == true -> {
                                stringProvider.getString(R.string.download_failed_storage)
                            }
                            else -> {
                                stringProvider.getString(R.string.download_failed, error.message ?: "Unknown error")
                            }
                        }
                        handleDownloadFailed(packageName, errorMessage)
                    }
                    .onEach { download ->
                        // Update progress
                        val progressPercent = download.progress * 100f
                        updateAppProgress(packageName, download.progress)
                        
                        // Log progress more frequently for better monitoring
                        Log.d(TAG, "Download progress for $packageName: ${String.format("%.2f", progressPercent)}% (${download.progress})")
                        
                        // Additional detailed logging for progress tracking
                        if (progressPercent > 0f) {
                            Log.i(TAG, "[$packageName] Download progress: ${String.format("%.1f", progressPercent)}%")
                        }
                        
                        if (download.isComplete && download.filePath != null) {
                            Log.i(TAG, "Download completed for $packageName: ${download.filePath}")
                            handleDownloadCompleted(packageName, download.filePath)
                            // Stop progress monitoring after completion
                            return@onEach
                        }
                    }
                    .launchIn(this)
                
            } catch (e: Exception) {
                Log.e(TAG, "Download error for $packageName", e)
                val errorMessage = when {
                    e.message?.contains("insufficient memory", ignoreCase = true) == true ||
                    e.message?.contains("OutOfMemoryError", ignoreCase = true) == true -> {
                        stringProvider.getString(R.string.download_failed_memory)
                    }
                    e.message?.contains("space", ignoreCase = true) == true -> {
                        stringProvider.getString(R.string.download_failed_storage)
                    }
                    else -> {
                        stringProvider.getString(R.string.download_failed, e.message ?: "Unknown error")
                    }
                }
                handleDownloadFailed(packageName, errorMessage)
            }
        }
        
        activeDownloads[packageName] = downloadJob
    }

    /**
     * Handle download completion - auto-install immediately
     */
    private fun handleDownloadCompleted(packageName: String, filePath: String) {
        // Prevent duplicate handling of the same download completion
        if (completedDownloads.contains(packageName)) {
            Log.w(TAG, "Download completion already handled for: $packageName")
            return
        }
        
        Log.i(TAG, "🚀 Download completed, queueing for installation: $packageName -> $filePath")
        completedDownloads.add(packageName)
        activeDownloads.remove(packageName)
        val urlCleanup = activeDownloadUrls.remove(packageName)
        if (urlCleanup != null) maxProgressPerDownload.remove(urlCleanup)
        
        // Get app name for better logging
        val currentState = _state.value
        val appName = if (currentState is AppState.Success) {
            currentState.apps.find { it.packageName == packageName }?.title ?: packageName
        } else {
            packageName
        }
        
        // Add to installation queue
        queueInstallation(packageName, filePath, appName)
        showToast(stringProvider.getString(R.string.download_completed_installing))
    }

    /**
     * Handle download failure — restore actual install status so the UI stays consistent.
     */
    private fun handleDownloadFailed(packageName: String, error: String) {
        Log.e(TAG, "Download failed: $packageName - $error")
        activeDownloads.remove(packageName)
        val urlCleanup = activeDownloadUrls.remove(packageName)
        if (urlCleanup != null) maxProgressPerDownload.remove(urlCleanup)

        // Determine correct status from what is actually installed on the device
        val restoredStatus = resolveActualStatus(packageName)
        updateAppStatus(packageName, restoredStatus)
        updateAppProgress(packageName, 0f)
        showError(error)
    }

    /**
     * Cancel an ongoing download — restore actual install status so the UI stays consistent.
     */
    private fun cancelDownload(packageName: String) {
        Log.i(TAG, "Cancelling download: $packageName")
        activeDownloads[packageName]?.cancel()
        activeDownloads.remove(packageName)
        val urlCleanup = activeDownloadUrls.remove(packageName)
        if (urlCleanup != null) maxProgressPerDownload.remove(urlCleanup)

        simpleDownloadManager.cancelDownload(packageName)

        val restoredStatus = resolveActualStatus(packageName)
        updateAppStatus(packageName, restoredStatus)
        updateAppProgress(packageName, 0f)

        // Only show the cancellation toast if the download was really in progress (not called
        // internally as part of a re-download).
        if (restoredStatus != AppStatus.DOWNLOADING) {
            showToast(stringProvider.getString(R.string.download_cancelled))
        }
    }

    /**
     * Install an app using queue system (public interface)
     */
    private fun installApp(packageName: String, apkFilePath: String) {
        val currentState = _state.value
        val appName = if (currentState is AppState.Success) {
            currentState.apps.find { it.packageName == packageName }?.title ?: packageName
        } else {
            packageName
        }
        
        Log.i(TAG, "📋 Queueing app installation: $appName ($packageName)")
        queueInstallation(packageName, apkFilePath, appName)
    }
    
    /**
     * Install an app directly using PackageInstaller API (used by queue).
     *
     * @param requireUserAction force the system confirmation dialog — the one retry after the ROM
     *   refused a silent install (issue #63).
     */
    private fun installAppDirect(packageName: String, apkFilePath: String, requireUserAction: Boolean = false) {
        Log.i(TAG, "🚀 Installing app directly: $packageName from $apkFilePath (requireUserAction=$requireUserAction)")
        viewModelScope.launch {
            try {
                // When the catalog publishes a signing fingerprint for this build (e.g. the
                // MicroG RE releases), verify the downloaded APK against it before committing an
                // install session — a mismatch means this is not the publisher's build.
                val expectedSha = (_state.value as? AppState.Success)
                    ?.apps?.find { it.packageName == packageName }?.signingSha256
                if (expectedSha != null) {
                    val signatureOk = withContext(Dispatchers.IO) {
                        ApkSignatureVerifier.matches(apkFilePath, expectedSha, context.packageManager)
                    }
                    if (!signatureOk) {
                        Log.e(TAG, "Signature mismatch for $packageName — expected $expectedSha")
                        installationQueue.removeAll { it.packageName == packageName }
                        installationRetries.remove(packageName)
                        updateAppStatus(packageName, resolveActualStatus(packageName))
                        val appName = (_state.value as? AppState.Success)
                            ?.apps?.find { it.packageName == packageName }?.title ?: packageName
                        showError(
                            stringProvider.getString(
                                R.string.install_failed_signature_mismatch_message,
                                appName,
                                expectedSha
                            )
                        )
                        return@launch
                    }
                    Log.i(TAG, "Signature verified for $packageName against published fingerprint")
                }

                val result = useCases.installAppUseCase(packageName, apkFilePath, requireUserAction)
                when (result) {
                    is Result.Success -> {
                        if (result.data) {
                            Log.i(TAG, "✅ Installation started successfully: $packageName")
                            showToast(stringProvider.getString(R.string.installation_started))
                            // PackageInstaller will handle the rest via BroadcastReceiver
                        } else {
                            Log.w(TAG, "⚠️ Installation failed to start: $packageName")
                            handleInstallationFailedWithRetry(packageName, stringProvider.getString(R.string.installation_failed_start))
                        }
                    }
                    is Result.Error -> {
                        Log.e(TAG, "❌ Installation error: $packageName", result.exception)
                        handleInstallationFailedWithRetry(packageName, result.message)
                    }
                    is Result.Loading -> {
                        // Should not happen in this case
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Installation exception: $packageName", e)
                handleInstallationFailedWithRetry(packageName, e.message ?: "Installation failed")
            }
        }
    }

    /**
     * Handle installation failure with immediate error feedback and retry dialog
     */
    private fun handleInstallationFailedWithRetry(packageName: String, error: String) {
        val currentRetries = installationRetries[packageName] ?: 0
        
        // Update app status based on current installation state
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                val isInstalled = appManager.isAppInstalled(packageName)
                val newStatus = if (isInstalled) {
                    val installedVersion = appManager.getInstalledVersionName(packageName)
                    if (installedVersion != null) {
                        when (compareVersions(installedVersion, app.latestVersionName)) {
                            0 -> AppStatus.UP_TO_DATE
                            1 -> AppStatus.UP_TO_DATE
                            else -> AppStatus.UPDATE_AVAILABLE
                        }
                    } else {
                        AppStatus.NOT_INSTALLED
                    }
                } else {
                    AppStatus.NOT_INSTALLED
                }
                
                updateAppStatus(packageName, newStatus)
            }
        }
        
        if (currentRetries < 1) { // Allow one retry
            installationRetries[packageName] = currentRetries + 1
            
            // Show retry dialog
            showConfirmationDialog(
                title = stringProvider.getString(R.string.installation_failed_title),
                message = stringProvider.getString(R.string.installation_failed_retry_message, error),
                onConfirm = AppEvent.RetryInstallation(packageName, getDownloadPath(packageName) ?: "", shouldUninstallFirst = true),
                onCancel = AppEvent.DismissDialogAndUpdateStatus(packageName, AppStatus.UPDATE_AVAILABLE)
            )
        } else {
            // Max retries reached
            installationRetries.remove(packageName)
            showError(stringProvider.getString(R.string.download_failed, error))
        }
        
        // Remove from installation queue
        installationQueue.removeAll { it.packageName == packageName }
        Log.i(TAG, "🗑️ Removed failed installation from queue: $packageName")
        
        // Remove from download state database to prevent re-queuing
        viewModelScope.launch {
            try {
                downloadStateRepository.removeDownloadState(packageName)
                Log.i(TAG, "🗑️ Removed failed installation from download state: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove download state for failed installation", e)
            }
        }
    }

    /**
     * Route an install failure whose cause removing the old version cannot fix (no space, wrong
     * ABI, damaged APK, blocked by the system) — uninstall-and-retry would fail for the same
     * reason, so the user gets an explanation instead (upstream handleInstallationFailure).
     */
    private fun handleInstallationFailureClassified(
        packageName: String,
        error: String,
        reason: com.app.manager.domain.model.InstallFailureReason
    ) {
        Log.w(TAG, "Installation failed for $packageName: $reason — $error")
        installationRetries.remove(packageName)
        installationQueue.removeAll { it.packageName == packageName }
        updateAppStatus(packageName, resolveActualStatus(packageName))
        viewModelScope.launch {
            runCatching { downloadStateRepository.removeDownloadState(packageName) }
        }

        val currentState = _state.value
        val appName = (currentState as? AppState.Success)
            ?.apps?.find { it.packageName == packageName }?.title ?: packageName
        val message = when (reason) {
            com.app.manager.domain.model.InstallFailureReason.STORAGE_FULL ->
                stringProvider.getString(R.string.install_failed_storage_message, appName)
            com.app.manager.domain.model.InstallFailureReason.INCOMPATIBLE ->
                stringProvider.getString(R.string.install_failed_incompatible_message, appName)
            com.app.manager.domain.model.InstallFailureReason.INVALID_APK ->
                stringProvider.getString(R.string.install_failed_invalid_message, appName)
            com.app.manager.domain.model.InstallFailureReason.BLOCKED ->
                stringProvider.getString(R.string.install_failed_blocked_message, appName)
            else ->
                stringProvider.getString(R.string.install_failed_generic_message, appName, error)
        }
        showError(message)
    }

    /**
     * Handle successful installation
     */
    private fun handleInstallationSuccess(packageName: String, installedVersion: String) {
        // Success arrives from both the PackageInstaller result and the system PACKAGE_ADDED
        // broadcast — settle the first delivery only.
        if (handledInstallSuccess[packageName] == installedVersion) {
            Log.d(TAG, "Install success already handled for $packageName v$installedVersion")
            return
        }
        handledInstallSuccess[packageName] = installedVersion
        invalidateMicrogCache()
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                val newStatus = when (compareVersions(installedVersion, app.latestVersionName)) {
                    0 -> AppStatus.UP_TO_DATE  // Same version as latest
                    1 -> AppStatus.UP_TO_DATE  // Installed version is newer than latest
                    else -> AppStatus.UPDATE_AVAILABLE  // Still needs update (rare case)
                }
                
                val updatedApps = currentState.apps.map { appItem ->
                    if (appItem.packageName == packageName) {
                        appItem.copy(
                            status = newStatus,
                            currentVersion = installedVersion,
                            downloadProgress = 0f
                        )
                    } else {
                        appItem
                    }
                }
                
                _state.value = currentState.copy(apps = updatedApps)
                // Clean up APK file after successful installation
                val pendingInstall = installationQueue.find { it.packageName == packageName }
                if (pendingInstall != null) {
                    runCatching { File(pendingInstall.filePath).delete() }
                    Log.i(TAG, "🗑️ Deleted APK after install: ${pendingInstall.filePath}")
                }
                // Download URL tracking already cleaned up in handleDownloadCompleted
                
                showToast(stringProvider.getString(R.string.installation_completed))
                
                // Clear pending install state and retries
                preferencesManager.removeKey("pending_install_${packageName}")
                installationRetries.remove(packageName)
                completedDownloads.remove(packageName)
                
                // Remove from installation queue
                installationQueue.removeAll { it.packageName == packageName }
                Log.i(TAG, "🗑️ Removed successful installation from queue: $packageName")
                
                // Remove download state from database after successful installation
                viewModelScope.launch {
                    try {
                        downloadStateRepository.removeDownloadState(packageName)
                        Log.d(TAG, "Removed download state after successful installation: $packageName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove download state", e)
                    }
                }
            }
        }
    }

    /**
     * Update status of a single app without refreshing the entire list
     * This prevents screen flickering and unnecessary API calls
     */
    private suspend fun updateSingleAppStatus(packageName: String) {
        try {
            val currentState = _state.value
            if (currentState is AppState.Success) {
                val appToUpdate = currentState.apps.find { it.packageName == packageName }
                if (appToUpdate != null) {
                    // Don't update status if app is currently being processed
                    // This prevents overriding INSTALLING/DOWNLOADING/UNINSTALLING states
                    if (appToUpdate.status in listOf(
                        AppStatus.DOWNLOADING,
                        AppStatus.INSTALLING,
                        AppStatus.UNINSTALLING
                    )) {
                        // Only handle UNINSTALLING status here, others are handled by their respective monitoring functions
                        if (appToUpdate.status == AppStatus.UNINSTALLING) {
                            val isInstalled = appManager.isAppInstalled(packageName)
                            if (!isInstalled) {
                                // Uninstallation completed
                                val updatedApps = currentState.apps.map { app ->
                                    if (app.packageName == packageName) {
                                        app.copy(
                                            status = AppStatus.NOT_INSTALLED,
                                            currentVersion = null,
                                            downloadProgress = 0f
                                        )
                                    } else {
                                        app
                                    }
                                }
                                
                                _state.value = currentState.copy(apps = updatedApps)
                                showToast(stringProvider.getString(R.string.uninstallation_completed))
                            }
                        }
                        // For DOWNLOADING and INSTALLING, don't interfere - let their monitoring functions handle
                        return
                    }
                    
                    // Normal status update for non-processing apps
                    val isInstalled = appManager.isAppInstalled(packageName)
                    val installedVersion = if (isInstalled) {
                        appManager.getInstalledVersionName(packageName)
                    } else {
                        null
                    }
                    
                    // Determine new status based on installation state
                    val newStatus = when {
                        !isInstalled -> AppStatus.NOT_INSTALLED
                        installedVersion != null -> {
                            when (compareVersions(installedVersion, appToUpdate.latestVersionName)) {
                                0 -> AppStatus.UP_TO_DATE  // Same version
                                1 -> AppStatus.UP_TO_DATE  // Installed version is newer
                                else -> AppStatus.UPDATE_AVAILABLE  // Latest version is newer
                            }
                        }
                        else -> AppStatus.NOT_INSTALLED
                    }
                    
                    // Only update if status actually changed
                    if (newStatus != appToUpdate.status) {
                        val updatedApps = currentState.apps.map { app ->
                            if (app.packageName == packageName) {
                                app.copy(
                                    status = newStatus,
                                    currentVersion = installedVersion,
                                    downloadProgress = 0f
                                )
                            } else {
                                app
                            }
                        }
                        
                        _state.value = currentState.copy(apps = updatedApps)
                        showToast(stringProvider.getString(R.string.app_status_updated))
                    }
                }
            }
        } catch (e: Exception) {
            // If single app update fails, fallback to full refresh
            loadApps(forceRefresh = true)
        }
    }

    /**
     * Handle installation completion
     */
    private fun handleInstallationCompleted(packageName: String, success: Boolean) {
        if (success) {
            updateAppStatus(packageName, AppStatus.UP_TO_DATE)
            showToast(stringProvider.getString(R.string.installation_completed))
            installationRetries.remove(packageName)
            
            // Clear pending install state  
            preferencesManager.removeKey("pending_install_${packageName}")
        } else {
            val currentState = _state.value
            if (currentState is AppState.Success) {
                val app = currentState.apps.find { it.packageName == packageName }
                app?.let {
                    val apkFilePath = getDownloadPath(packageName) ?: preferencesManager.getPendingInstallPath(packageName)
                    if (apkFilePath != null) {
                        handleInstallationFailed(packageName, apkFilePath, stringProvider.getString(R.string.installation_failed_start))
                    } else {
                        updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                        showError(stringProvider.getString(R.string.installation_failed_apk_not_found))
                    }
                }
            }
        }
    }

    /**
     * Handle installation aborted by user - no retry dialog
     */
    private fun handleInstallationAborted(packageName: String, error: String) {
        Log.i(TAG, "🚫 Installation aborted by user: $packageName")
        
        // Clean up without retry
        installationRetries.remove(packageName)
        
        // 🔥 Check actual installation status instead of assuming UPDATE_AVAILABLE
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                val isInstalled = appManager.isAppInstalled(packageName)
                val newStatus = if (isInstalled) {
                    val installedVersion = appManager.getInstalledVersionName(packageName)
                    if (installedVersion != null) {
                        when (compareVersions(installedVersion, app.latestVersionName)) {
                            0 -> AppStatus.UP_TO_DATE
                            1 -> AppStatus.UP_TO_DATE
                            else -> AppStatus.UPDATE_AVAILABLE
                        }
                    } else {
                        AppStatus.NOT_INSTALLED
                    }
                } else {
                    AppStatus.NOT_INSTALLED
                }
                
                Log.i(TAG, "📦 After user abort, setting status to: $newStatus (installed: $isInstalled)")
                updateAppStatus(packageName, newStatus)
            }
        }
        
        // Show simple error message without retry option
        showToast(stringProvider.getString(R.string.installation_cancelled_by_user))
        
        // Clean up download state and installation queue
        viewModelScope.launch {
            downloadStateRepository.removeDownloadState(packageName)
            Log.i(TAG, "🗑️ Removed download state after user abort: $packageName")
        }
        
        // Remove from installation queue and completed downloads tracking
        installationQueue.removeAll { it.packageName == packageName }
        completedDownloads.remove(packageName)
        Log.i(TAG, "🗑️ Removed from installation queue and completed downloads after user abort: $packageName")
    }

    /**
     * Handle installation failure with retry logic
     */
    private fun handleInstallationFailed(packageName: String, apkFilePath: String, error: String) {
        val currentRetries = installationRetries[packageName] ?: 0
        
        if (currentRetries < 1) { // Allow one retry
            installationRetries[packageName] = currentRetries + 1
            
            // Show retry dialog
            showConfirmationDialog(
                title = stringProvider.getString(R.string.installation_failed_title),
                message = stringProvider.getString(R.string.installation_failed_retry_message, error),
                onConfirm = AppEvent.RetryInstallation(packageName, apkFilePath, shouldUninstallFirst = true),
                onCancel = AppEvent.UpdateAppStatus(packageName, AppStatus.NOT_INSTALLED)
            )
        } else {
            // Max retries reached
            installationRetries.remove(packageName)
            updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
            showError(stringProvider.getString(R.string.download_failed, error))
        }
    }

    /**
     * Retry installation with optional uninstall first
     * Uses system events for detection, with fallback polling after 5s
     */
    private fun retryInstallation(packageName: String, apkFilePath: String, shouldUninstallFirst: Boolean) {
        dismissDialog()
        
        if (shouldUninstallFirst) {
            viewModelScope.launch {
                try {
                    // Check if package is actually installed before attempting uninstall
                    val isInstalled = appManager.isAppInstalled(packageName)
                    if (!isInstalled) {
                        Log.i(TAG, "📦 Package $packageName is not installed, skipping uninstall and proceeding with installation")
                        showToast(stringProvider.getString(R.string.app_not_installed_proceeding))
                        installApp(packageName, apkFilePath)
                        return@launch
                    }
                    
                    // Store pending reinstall and track uninstall
                    pendingReinstalls[packageName] = apkFilePath
                    pendingUninstallChecks.add(packageName)
                    
                    // First uninstall the existing app
                    updateAppStatus(packageName, AppStatus.UNINSTALLING)
                    val uninstallResult = useCases.uninstallAppUseCase(packageName)
                    when (uninstallResult) {
                        is Result.Success -> {
                            if (uninstallResult.data) {
                                showToast(stringProvider.getString(R.string.old_version_uninstalled))
                                
                                // Fallback: if system event not received within 5s, poll and proceed
                                delay(5000)
                                if (pendingUninstallChecks.contains(packageName)) {
                                    Log.w(TAG, "⚠️ System event not received for reinstall, checking manually")
                                    if (!appManager.isAppInstalled(packageName)) {
                                        pendingUninstallChecks.remove(packageName)
                                        val path = pendingReinstalls.remove(packageName)
                                        if (path != null) {
                                            Log.i(TAG, "🔄 Proceeding with reinstall via fallback: $packageName")
                                            installApp(packageName, path)
                                        }
                                    }
                                }
                            } else {
                                pendingReinstalls.remove(packageName)
                                pendingUninstallChecks.remove(packageName)
                                showError(stringProvider.getString(R.string.failed_uninstall_old_version))
                                updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                            }
                        }
                        is Result.Error -> {
                            pendingReinstalls.remove(packageName)
                            pendingUninstallChecks.remove(packageName)
                            showError(stringProvider.getString(R.string.failed_uninstall_old_version_error, uninstallResult.message))
                            updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                        }
                        is Result.Loading -> {
                            // Should not happen
                        }
                    }
                } catch (e: Exception) {
                    pendingReinstalls.remove(packageName)
                    pendingUninstallChecks.remove(packageName)
                    showError(stringProvider.getString(R.string.failed_uninstall_old_version_error, e.message ?: ""))
                    updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                }
            }
        } else {
            installApp(packageName, apkFilePath)
        }
    }

    /**
     * Confirm uninstall before reinstall
     */
    private fun confirmUninstallBeforeReinstall(packageName: String, apkFilePath: String) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                showConfirmationDialog(
                    title = stringProvider.getString(R.string.uninstall_required_title),
                    message = stringProvider.getString(R.string.uninstall_required_message, app.title),
                    onConfirm = AppEvent.RetryInstallation(packageName, apkFilePath, shouldUninstallFirst = true),
                    onCancel = AppEvent.UpdateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                )
            }
        }
    }

    /**
     * Uninstall an app
     * Uses system events for detection, with fallback polling after 5s
     */
    private fun uninstallApp(packageName: String) {
        dismissDialog()
        
        viewModelScope.launch {
            try {
                updateAppStatus(packageName, AppStatus.UNINSTALLING)
                pendingUninstallChecks.add(packageName)
                
                val result = useCases.uninstallAppUseCase(packageName)
                when (result) {
                    is Result.Success -> {
                        if (result.data) {
                            showToast(stringProvider.getString(R.string.uninstallation_started))
                            
                            // Fallback: if system event not received within 5s, poll manually
                            delay(5000)
                            if (pendingUninstallChecks.contains(packageName)) {
                                Log.w(TAG, "⚠️ System event not received, checking manually for: $packageName")
                                if (!appManager.isAppInstalled(packageName)) {
                                    pendingUninstallChecks.remove(packageName)
                                    updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                                    showToast(stringProvider.getString(R.string.uninstallation_completed))
                                } else {
                                    Log.d(TAG, "📦 Package still installed, waiting for system event...")
                                }
                            }
                        } else {
                            pendingUninstallChecks.remove(packageName)
                            showError(stringProvider.getString(R.string.failed_start_uninstallation))
                            updateSingleAppStatus(packageName)
                        }
                    }
                    is Result.Error -> {
                        pendingUninstallChecks.remove(packageName)
                        showError(stringProvider.getString(R.string.uninstallation_failed, result.message))
                        updateSingleAppStatus(packageName)
                    }
                    is Result.Loading -> {
                        // Should not happen in this case
                    }
                }
            } catch (e: Exception) {
                pendingUninstallChecks.remove(packageName)
                showError(stringProvider.getString(R.string.uninstallation_failed, e.message ?: ""))
                updateSingleAppStatus(packageName)
            }
        }
    }

    /**
     * Show confirmation dialog before reinstalling an app
     */
    private fun showMicrogUninstallWarning(packageName: String) {
        val title = stringProvider.getString(R.string.microg_uninstall_warning_title)
        val message = stringProvider.getString(R.string.microg_uninstall_warning_message)
        handleEvent(
            AppEvent.ShowConfirmationDialog(
                title = title,
                message = message,
                onConfirm = AppEvent.ConfirmUninstallMicroG(packageName),
                onCancel = AppEvent.DismissDialog
            )
        )
    }

    /**
     * Show confirmation dialog before reinstalling an app
     */
    private fun showReinstallConfirmation(packageName: String) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            if (app != null) {
                val title = stringProvider.getString(R.string.reinstall_confirmation_title)
                val message = stringProvider.getString(R.string.reinstall_confirmation_message, app.title)
                val confirmEvent = AppEvent.ReinstallApp(packageName)
                val cancelEvent = AppEvent.DismissDialog
                
                handleEvent(AppEvent.ShowConfirmationDialog(title, message, confirmEvent, cancelEvent))
            }
        }
    }

    /**
     * Reinstall an app (uninstall -> download -> install)
     */
    private fun reinstallApp(packageName: String) {
        dismissDialog()
        
        viewModelScope.launch {
            try {
                val currentState = _state.value
                if (currentState is AppState.Success) {
                    val app = currentState.apps.find { it.packageName == packageName }
                    if (app != null) {
                        // Step 1: Uninstall
                        updateAppStatus(packageName, AppStatus.UNINSTALLING)
                        showToast(stringProvider.getString(R.string.reinstall_started))
                        
                        val uninstallResult = useCases.uninstallAppUseCase(packageName)
                        when (uninstallResult) {
                            is Result.Success -> {
                                if (uninstallResult.data) {
                                    // Wait for uninstallation to complete
                                    delay(3000)
                                    
                                    // Step 2: Download latest version
                                    updateAppStatus(packageName, AppStatus.DOWNLOADING)
                                    updateAppProgress(packageName, 0f)
                                    
                                    // Clear completed downloads tracking
                                    completedDownloads.remove(packageName)
                                    
                                    simpleDownloadManager.downloadApp(packageName, app.downloadUrl)
                                        .catch { error ->
                                            Log.e(TAG, "Reinstall download failed for $packageName", error)
                                            showError(stringProvider.getString(R.string.reinstall_failed, error.message ?: "Download failed"))
                                            updateSingleAppStatus(packageName)
                                        }
                                        .onEach { download ->
                                            // Update progress
                                            updateAppProgress(packageName, download.progress)
                                            
                                            // Download completed, proceed to install
                                             if (download.progress >= 1.0f) {
                                                 // Step 3: Install
                                                 val filePath = download.filePath
                                                 if (filePath != null) {
                                                     updateAppStatus(packageName, AppStatus.INSTALLING)
                                                     val installResult = useCases.installAppUseCase(packageName, filePath)
                                                     when (installResult) {
                                                         is Result.Success -> {
                                                             if (installResult.data) {
                                                                 showToast(stringProvider.getString(R.string.reinstall_completed))
                                                             } else {
                                                                 showError(stringProvider.getString(R.string.reinstall_failed))
                                                                 updateSingleAppStatus(packageName)
                                                             }
                                                         }
                                                         is Result.Error -> {
                                                             showError(stringProvider.getString(R.string.reinstall_failed, installResult.message))
                                                             updateSingleAppStatus(packageName)
                                                         }
                                                         is Result.Loading -> {
                                                             // Should not happen
                                                         }
                                                     }
                                                 } else {
                                                     showError(stringProvider.getString(R.string.reinstall_failed, "File path is null"))
                                                     updateSingleAppStatus(packageName)
                                                 }
                                             }
                                        }.launchIn(this@launch)
                                } else {
                                    showError(stringProvider.getString(R.string.reinstall_failed))
                                    updateSingleAppStatus(packageName)
                                }
                            }
                            is Result.Error -> {
                                showError(stringProvider.getString(R.string.reinstall_failed, uninstallResult.message))
                                updateSingleAppStatus(packageName)
                            }
                            is Result.Loading -> {
                                // Should not happen
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                showError(stringProvider.getString(R.string.reinstall_failed, e.message ?: ""))
                updateSingleAppStatus(packageName)
            }
        }
    }

    /**
     * Open an installed app
     */
    private fun openApp(packageName: String) {
        viewModelScope.launch {
            try {
                val result = useCases.openAppUseCase(packageName)
                when (result) {
                    is Result.Success -> {
                        if (!result.data) {
                            showError(stringProvider.getString(R.string.failed_open_app))
                        }
                    }
                    is Result.Error -> {
                        showError(stringProvider.getString(R.string.failed_open_app_error, result.message))
                    }
                    is Result.Loading -> {
                        // Should not happen in this case
                    }
                }
            } catch (e: Exception) {
                showError(stringProvider.getString(R.string.failed_open_app_error, e.message ?: ""))
            }
        }
    }

    /**
     * Show confirmation dialog
     */
    private fun showConfirmationDialog(title: String, message: String, onConfirm: AppEvent, onCancel: AppEvent?) {
        val currentState = _state.value
        val dialogState = DialogState.Confirmation(
            title = title,
            message = message,
            onConfirmAction = { handleEvent(onConfirm) },
            onCancelAction = onCancel?.let { { handleEvent(it) } }
        )
        
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(dialogState = dialogState)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(dialogState = dialogState)
            }
            is AppState.Loading -> {
                // Can't show dialog during loading
            }
        }
    }

    /**
     * Dismiss dialog
     */
    private fun dismissDialog() {
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(dialogState = null)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(dialogState = null)
            }
            is AppState.Loading -> {
                // No dialog to dismiss
            }
        }
    }

    /**
     * Update app progress
     */
    private fun updateAppProgress(packageName: String, progress: Float, downloadUrl: String? = null) {
        // Anti-jump: only update if progress increased (or is final 1.0)
        val effectiveUrl = downloadUrl ?: activeDownloadUrls[packageName]
        if (effectiveUrl != null) {
            val prevMax = maxProgressPerDownload.getOrDefault(effectiveUrl, -1f)
            if (progress <= prevMax && progress < 1f) return
            maxProgressPerDownload[effectiveUrl] = progress
        }
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    if (effectiveUrl == null || app.downloadUrl == effectiveUrl) app.copy(downloadProgress = progress) else app
                } else {
                    app
                }
            }
            _state.value = currentState.copy(apps = updatedApps)
        }
    }

    /**
     * Update app status
     */
    private fun updateAppStatus(packageName: String, status: AppStatus, downloadUrl: String? = null) {
        val effectiveUrl = downloadUrl ?: activeDownloadUrls[packageName]
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    if (effectiveUrl == null || app.downloadUrl == effectiveUrl) app.copy(status = status) else app
                } else {
                    app
                }
            }
            _state.value = currentState.copy(apps = updatedApps)
        }
    }

    private fun logEvent(event: AppEvent) {
        val detail = when (event) {
            AppEvent.LoadApps -> "LoadApps"
            AppEvent.RefreshApps -> "RefreshApps"
            AppEvent.LoadAppsFromCacheFirst -> "LoadAppsFromCacheFirst"
            AppEvent.BackgroundRefreshApps -> "BackgroundRefreshApps"
            is AppEvent.UpdateSingleApp -> "UpdateSingleApp ${event.app.packageName}"
            is AppEvent.DownloadApp -> "DownloadApp ${event.packageName}"
            is AppEvent.InstallApp -> "InstallApp ${event.packageName}"
            is AppEvent.UninstallApp -> "UninstallApp ${event.packageName}"
            is AppEvent.ConfirmUninstallMicroG -> "ConfirmUninstallMicroG ${event.packageName}"
            is AppEvent.ConfirmMicroGSuggestion -> "ConfirmMicroGSuggestion ${event.apps.size} apps"
            is AppEvent.ShowReinstallConfirmation -> "ShowReinstallConfirmation ${event.packageName}"
            is AppEvent.ReinstallApp -> "ReinstallApp ${event.packageName}"
            is AppEvent.OpenApp -> "OpenApp ${event.packageName}"
            is AppEvent.SearchApps -> "SearchApps '${event.query}'"
            AppEvent.ClearSearch -> "ClearSearch"
            is AppEvent.SetFilter -> "SetFilter ${event.filter}"
            is AppEvent.SetSort -> "SetSort ${event.sort}"
            AppEvent.UpdateAll -> "UpdateAll"
            is AppEvent.InstallSelectedUpdates -> "InstallSelectedUpdates ${event.apps.size} apps"
            AppEvent.SkipUpdatePromptToday -> "SkipUpdatePromptToday"
            AppEvent.TurnOffUpdatePrompt -> "TurnOffUpdatePrompt"
            is AppEvent.SaveConfiguration -> "SaveConfiguration theme=${event.config.themeMode}, language=${event.config.language.code}, compact=${event.config.compactMode}, debug=${event.config.debugLogging}, path=${event.config.downloadPath}"
            is AppEvent.UpdateCompactMode -> "UpdateCompactMode ${event.enabled}"
            AppEvent.LoadConfiguration -> "LoadConfiguration"
            is AppEvent.UpdateAppProgress -> "UpdateAppProgress ${event.packageName} ${(event.progress * 100).toInt()}%"
            is AppEvent.UpdateAppStatus -> "UpdateAppStatus ${event.packageName} -> ${event.status}"
            is AppEvent.ShowError -> "ShowError ${event.message}"
            is AppEvent.RetryInstallation -> "RetryInstallation ${event.packageName} uninstallFirst=${event.shouldUninstallFirst}"
            is AppEvent.CancelDownload -> "CancelDownload ${event.packageName}"
            is AppEvent.ConfirmUninstallBeforeReinstall -> "ConfirmUninstallBeforeReinstall ${event.packageName}"
            is AppEvent.ShowConfirmationDialog -> "ShowConfirmationDialog ${event.title}"
            AppEvent.DismissDialog -> "DismissDialog"
            is AppEvent.DismissDialogAndUpdateStatus -> "DismissDialogAndUpdateStatus ${event.packageName} -> ${event.status}"
            is AppEvent.DownloadCompleted -> "DownloadCompleted ${event.packageName}"
            is AppEvent.DownloadFailed -> "DownloadFailed ${event.packageName}"
            is AppEvent.InstallationCompleted -> "InstallationCompleted ${event.packageName} success=${event.success}"
            is AppEvent.AutoInstallAllCompleted -> "AutoInstallAllCompleted success=${event.completedPackages.size} failed=${event.failedPackages.size}"
            AppEvent.ShowLogsDialog -> "ShowLogsDialog"
            is AppEvent.SelectVendor -> "SelectVendor ${event.vendor}"
            is AppEvent.ToggleShowBeta -> "ToggleShowBeta ${event.enabled}"
            is AppEvent.ClearDownloadCache -> "ClearDownloadCache"
            is AppEvent.ToggleFavorite -> "ToggleFavorite ${event.packageName}"
            AppEvent.ClearLogs -> "ClearLogs"
        }
        addActionLog("EVENT", detail)
    }

    private fun addActionLog(level: String, message: String) {
        if (!isDebugLoggingEnabled && level != "ERROR") {
            return
        }
        // On the first entry of a logging day, write a session header so a saved log names the
        // app version, device, ROM and ABIs it came from (upstream session header).
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (today != currentLogDay) {
            currentLogDay = today
            sessionHeaderLines().forEach { headerLine ->
                actionLogs.add(AppLogEntry(today, "INFO", headerLine))
            }
        }
        val timestamp = synchronized(logTimestampFormat) {
            logTimestampFormat.format(Date())
        }
        val entry = AppLogEntry(
            timestamp = timestamp,
            level = level,
            message = message
        )
        synchronized(actionLogs) {
            actionLogs.add(entry)
            if (actionLogs.size > MAX_LOG_ENTRIES) {
                actionLogs.removeAt(0)
            }
        }

        val currentLogs = getCurrentLogs()
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                val dialogState = when (val dialog = currentState.dialogState) {
                    is DialogState.Logs -> dialog.copy(logs = currentLogs)
                    else -> dialog
                }
                _state.value = currentState.copy(logs = currentLogs, dialogState = dialogState)
            }
            is AppState.Error -> {
                val dialogState = when (val dialog = currentState.dialogState) {
                    is DialogState.Logs -> dialog.copy(logs = currentLogs)
                    else -> dialog
                }
                _state.value = currentState.copy(logs = currentLogs, dialogState = dialogState)
            }
            is AppState.Loading -> Unit
        }
    }

    /** Device/app facts a debug log should start with (upstream session header). */
    private fun sessionHeaderLines(): List<String> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toString() ?: "?"
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toString() ?: "?"
        }
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val today = logDayFormat.format(Date())
        return listOf(
            "===== SESSION $today =====",
            "app=$versionName ($versionCode) debuggable=$debuggable",
            "device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "rom=${Build.DISPLAY}",
            "abis=${Build.SUPPORTED_ABIS.joinToString(",")} locale=${Locale.getDefault()}"
        )
    }

    private fun getCurrentLogs(): List<AppLogEntry> =
        synchronized(actionLogs) { actionLogs.toList() }

    private fun logException(contextMessage: String, throwable: Throwable) {
        addActionLog(
            "ERROR",
            "$contextMessage | ${throwable::class.java.simpleName}: ${throwable.localizedMessage ?: throwable.message ?: "n/a"}"
        )
        throwable.stackTrace.take(8).forEach { frame ->
            addActionLog("STACK", "at $frame")
        }
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        Log.w(TAG, "Showing error: $message")
        addActionLog("ERROR", message)
        _toastMessage.value = message
    }

    private fun showError(message: String, throwable: Throwable) {
        Log.w(TAG, "Showing error: $message", throwable)
        logException(message, throwable)
        _toastMessage.value = message
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        Log.d(TAG, "Showing toast: $message")
        addActionLog("TOAST", message)
        _toastMessage.value = message
    }
    
    /**
     * Clear toast message after showing
     */
    fun clearToast() {
        _toastMessage.value = null
    }

    /**
     * Get download path for a package
     */
    private fun getDownloadPath(packageName: String): String? {
        val candidates = listOf(
            File(File(context.cacheDir, "downloads"), "$packageName.apk"),
            File(File(context.getExternalFilesDir(null), "downloads"), "$packageName.apk"),
            File(File(context.getExternalFilesDir(null), "AppManager"), "$packageName.apk")
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }

    /**
     * Determine the correct AppStatus for a package by querying the PackageManager.
     * Used to restore status after a download cancel or failure.
     */
    private fun resolveActualStatus(packageName: String): AppStatus {
        if (!appManager.isAppInstalled(packageName)) return AppStatus.NOT_INSTALLED
        val installedVersion = appManager.getInstalledVersionName(packageName) ?: return AppStatus.NOT_INSTALLED
        val latestVersion = (_state.value as? AppState.Success)
            ?.apps?.find { it.packageName == packageName }?.latestVersionName ?: return AppStatus.UP_TO_DATE
        return when (compareVersions(installedVersion, latestVersion)) {
            0, 1 -> AppStatus.UP_TO_DATE
            else -> AppStatus.UPDATE_AVAILABLE
        }
    }

    /**
     * Compare version strings — delegates to [VersionComparator], the single source of truth
     * shared with GetAppsUseCase (suffix-aware: a prerelease ranks below its own release).
     * @return 1 if version1 > version2, -1 if version1 < version2, 0 if equal
     */
    private fun compareVersions(version1: String, version2: String): Int =
        VersionComparator.compare(version1, version2)

    private fun showLogsDialog() {
        val currentLogs = getCurrentLogs()
        val dialogState = DialogState.Logs(
            logs = currentLogs,
            onClear = { handleEvent(AppEvent.ClearLogs) },
            onClose = { handleEvent(AppEvent.DismissDialog) }
        )

        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> _state.value = currentState.copy(dialogState = dialogState, logs = currentLogs)
            is AppState.Error -> _state.value = currentState.copy(dialogState = dialogState, logs = currentLogs)
            is AppState.Loading -> {
                _state.value = AppState.Success(
                    apps = emptyList(),
                    dialogState = dialogState,
                    logs = currentLogs
                )
            }
        }
    }

    private fun clearLogs() {
        synchronized(actionLogs) {
            actionLogs.clear()
        }
        addActionLog("INFO", "Logs cleared by user")
    }
    
    /**
     * Save configuration with immediate language application
     */
    private fun saveConfiguration(config: AppConfig) {
        Log.i(TAG, "🔧 === SAVE CONFIGURATION START ===")
        Log.i(TAG, "🔧 New config - Theme: ${config.themeMode}, Language: ${config.language.displayName} (${config.language.code})")
        
        val previousConfig = when (val currentState = _state.value) {
            is AppState.Success -> {
                Log.d(TAG, "🔧 Previous config from Success state: ${currentState.config}")
                currentState.config
            }
            is AppState.Error -> {
                Log.d(TAG, "🔧 Previous config from Error state: ${currentState.config}")
                currentState.config
            }
            else -> {
                Log.d(TAG, "🔧 No previous config, using default")
                AppConfig()
            }
        }
        
        Log.i(TAG, "🔧 Previous config - Theme: ${previousConfig.themeMode}, Language: ${previousConfig.language.displayName} (${previousConfig.language.code})")
        
        // Save to preferences
        Log.d(TAG, "🔧 Saving config to preferences...")
        preferencesManager.saveAppConfig(config)
        isDebugLoggingEnabled = config.debugLogging
        selectedVendor = config.vendor
        showBeta = config.showBeta
        favoriteSet = preferencesManager.getFavorites()
        Log.d(TAG, "🔧 Config saved to preferences successfully")
        
        // Update state
        val currentState = _state.value
        Log.d(TAG, "🔧 Current state type: ${currentState::class.simpleName}")
        when (currentState) {
            is AppState.Success -> {
                Log.d(TAG, "🔧 Updating Success state with new config")
                _state.value = currentState.copy(config = config, dialogState = null)
            }
            is AppState.Error -> {
                Log.d(TAG, "🔧 Updating Error state with new config")
                _state.value = currentState.copy(config = config, dialogState = null)
            }
            is AppState.Loading -> {
                Log.w(TAG, "🔧 Can't update config during loading state")
            }
        }
        
        // Check if language changed and apply immediately
        val languageChanged = previousConfig.language != config.language
        Log.i(TAG, "🔧 Language changed: $languageChanged")
        
        if (languageChanged) {
            Log.i(TAG, "🔧 === LANGUAGE CHANGE DETECTED ===")
            Log.i(TAG, "🔧 From: ${previousConfig.language.displayName} (${previousConfig.language.code})")
            Log.i(TAG, "🔧 To: ${config.language.displayName} (${config.language.code})")
            Log.i(TAG, "🔧 Current thread: ${Thread.currentThread().name}")
            Log.i(TAG, "🔧 ================================")
            Log.i(TAG, "🔧 Language changed - applying immediately without toast")
            applyLanguageImmediately(config.language)
        } else {
            Log.d(TAG, "🔧 No language change, just saving config with toast")
            showToast(stringProvider.getString(R.string.configuration_saved))
        }
        
        Log.i(TAG, "🔧 === SAVE CONFIGURATION END ===")
    }
    
    /**
     * Update compact mode setting
     */
    private fun updateCompactMode(enabled: Boolean) {
        Log.i(TAG, "🔧 Updating compact mode to: $enabled")
        
        val currentState = _state.value
        val currentConfig = when (currentState) {
            is AppState.Success -> currentState.config
            is AppState.Error -> currentState.config
            else -> AppConfig()
        }
        
        val newConfig = currentConfig.copy(compactMode = enabled)
        saveConfiguration(newConfig)
    }
    
    /**
     * Apply language immediately using LocaleHelper with activity recreation
     */
    private fun applyLanguageImmediately(newLanguage: Language) {
        Log.i(TAG, "🌍 === APPLY LANGUAGE IMMEDIATELY START ===")
        Log.i(TAG, "🌍 Target language: ${newLanguage.displayName} (${newLanguage.code})")
        Log.i(TAG, "🌍 Current thread: ${Thread.currentThread().name}")
        
        viewModelScope.launch {
            try {
                Log.i(TAG, "🌍 Inside viewModelScope.launch")
                Log.i(TAG, "🌍 Coroutine thread: ${Thread.currentThread().name}")
                
                // Handle language codes with country codes (e.g., es-ES -> es)
                val languageCode = if (newLanguage.code.contains("-")) {
                    val cleanCode = newLanguage.code.split("-")[0]
                    Log.d(TAG, "🌍 Cleaned language code: ${newLanguage.code} -> $cleanCode")
                    cleanCode
                } else {
                    Log.d(TAG, "🌍 Using original language code: ${newLanguage.code}")
                    newLanguage.code
                }
                
                Log.i(TAG, "🌍 Final language code to apply: $languageCode")
                
                // Update state first (before recreation)
                Log.d(TAG, "🌍 Updating state before language application...")
                val currentState = _state.value
                Log.d(TAG, "🌍 Current state type: ${currentState::class.simpleName}")
                
                when (currentState) {
                    is AppState.Success -> {
                        Log.d(TAG, "🌍 Updating Success state with new language")
                        val updatedConfig = currentState.config.copy(language = newLanguage)
                        _state.value = currentState.copy(config = updatedConfig)
                        Log.d(TAG, "🌍 Success state updated")
                    }
                    is AppState.Error -> {
                        Log.d(TAG, "🌍 Updating Error state with new language")
                        val updatedConfig = currentState.config.copy(language = newLanguage)
                        _state.value = currentState.copy(config = updatedConfig)
                        Log.d(TAG, "🌍 Error state updated")
                    }
                    else -> {
                        Log.w(TAG, "🌍 State is Loading, cannot update")
                    }
                }
                
                Log.i(TAG, "🌍 About to call LocaleHelper.applyLocaleToActivity...")
                Log.i(TAG, "🌍 Context type: ${context::class.simpleName}")
                Log.i(TAG, "🌍 Context toString: $context")
                
                // Use LocaleHelper for reliable language switching
                Log.i(TAG, "🌍 About to recreate activity for language change...")
                LocaleHelper.applyLocaleToActivity(context, languageCode)
                
                Log.i(TAG, "🌍 LocaleHelper.applyLocaleToActivity called")
                Log.i(TAG, "🌍 Apply language immediately completed successfully")
                
                // Note: No toast for language change - user will see UI change immediately
                
            } catch (e: Exception) {
                Log.e(TAG, "🌍 💥 Exception in applyLanguageImmediately", e)
                Log.e(TAG, "🌍 💥 Exception message: ${e.message}")
                Log.e(TAG, "🌍 💥 Exception cause: ${e.cause}")
                showToast(stringProvider.getString(R.string.error_applying_language))
            }
        }
        
        Log.i(TAG, "🌍 === APPLY LANGUAGE IMMEDIATELY END ===")
    }
    
    /**
     * Load configuration with robust error handling
     */
    private fun loadConfiguration() {
        val config = try {
            preferencesManager.getAppConfig()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load configuration, using fallback", e)
            // Use fallback configuration as requested: DARK theme and ENGLISH language
            AppConfig(ThemeMode.DARK, Language.VIETNAMESE)
        }
        isDebugLoggingEnabled = config.debugLogging
        selectedVendor = config.vendor
        showBeta = config.showBeta
        favoriteSet = preferencesManager.getFavorites()
        
        Log.d(TAG, "Loaded configuration: theme=${config.themeMode}, language=${config.language.displayName}")
        
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(config = config)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(config = config)
            }
            is AppState.Loading -> {
                // Store config for when loading completes
                // We'll apply it once the state transitions to Success or Error
                Log.d(TAG, "Config loaded during loading state, will apply when state is ready")
            }
        }
    }
    
    // ============= SEQUENTIAL INSTALLATION QUEUE =============

    /**
     * Add a package to the installation queue.
     * If no install is currently in progress, starts it immediately; otherwise the item
     * waits until [processNextInstallation] is called after the current install finishes.
     */
    private fun queueInstallation(packageName: String, filePath: String, appName: String = packageName) {
        Log.i(TAG, "📋 Queuing installation: $appName ($packageName)")

        if (installationQueue.any { it.packageName == packageName }) {
            Log.w(TAG, "⚠️ Already in queue, skipping: $packageName")
            return
        }

        // A fresh install starts with a silent attempt again — the one-confirmed-retry budget
        // is per install, not per process.
        silentInstallRetried.remove(packageName)

        installationQueue.add(PendingInstallation(packageName, filePath, appName))
        Log.i(TAG, "📋 Queue size: ${installationQueue.size}, inProgress: $isInstallationInProgress")

        if (!isInstallationInProgress) {
            triggerNextInstallation()
        }
        // else: will be picked up when the current install finishes via processNextInstallation()
    }

    /**
     * Start the next pending installation from the front of the queue.
     * Must only be called when [isInstallationInProgress] is false.
     */
    private fun triggerNextInstallation() {
        val next = installationQueue.firstOrNull()
        if (next == null) {
            Log.i(TAG, "📋 Installation queue empty, nothing to start")
            isInstallationInProgress = false
            return
        }

        isInstallationInProgress = true
        Log.i(TAG, "🚀 Starting next installation: ${next.appName} (${next.packageName})")
        updateAppStatus(next.packageName, AppStatus.INSTALLING)
        installAppDirect(next.packageName, next.filePath)
    }

    /**
     * Called after every install result (success / failure / cancel).
     * Removes the finished entry and starts the next one.
     */
    private fun processNextInstallation() {
        Log.i(TAG, "📋 processNextInstallation — queue size before cleanup: ${installationQueue.size}")

        // Remove entries that are no longer pending (installed, not-installed, update-available)
        installationQueue.removeAll { installation ->
            val state = _state.value as? AppState.Success
            val status = state?.apps?.find { it.packageName == installation.packageName }?.status
            val done = status == AppStatus.UP_TO_DATE ||
                       status == AppStatus.NOT_INSTALLED ||
                       status == AppStatus.UPDATE_AVAILABLE
            if (done) Log.i(TAG, "🗑️ Removing finished entry: ${installation.appName}")
            done
        }

        isInstallationInProgress = false
        Log.i(TAG, "📋 Queue size after cleanup: ${installationQueue.size}")
        triggerNextInstallation()
    }

    /**
     * Clear the entire installation queue (app restart / error recovery).
     */
    private fun clearInstallationQueue() {
        Log.i(TAG, "🗑️ Clearing installation queue (${installationQueue.size} items)")
        installationQueue.clear()
        isInstallationInProgress = false
    }
    
    // ============= CONCURRENT DOWNLOADS HANDLING - AUTO INSTALL =============
    
    /**
     * Auto-install all completed downloads immediately without showing dialog
     */
    private fun autoInstallAllCompleted(
        completedPackages: List<String>,
        completedNames: List<String>,
        completedPaths: List<String>,
        failedPackages: List<String>,
        failedNames: List<String>,
        failedErrors: List<String>
    ) {
        Log.i(TAG, "🚀 Auto-installing all completed downloads")
        Log.i(TAG, "📊 Completed: ${completedPackages.size}, Failed: ${failedPackages.size}")
        
        viewModelScope.launch {
            try {
                // Update UI states for failed downloads first
                failedPackages.forEach { packageName ->
                    updateAppStatus(packageName, AppStatus.UPDATE_AVAILABLE)
                }
                
                // Queue all completed downloads for installation.
                // Skip packages that were already handled by an individual ACTION_DOWNLOAD_COMPLETE
                // broadcast (their individual handler ran first and may have already installed the
                // app before this ALL_COMPLETE broadcast fires).
                val successState = _state.value as? AppState.Success
                completedPackages.zip(completedNames).zip(completedPaths).forEach { (namePackage, path) ->
                    val (packageName, appName) = namePackage
                    val currentStatus = successState?.apps?.find { it.packageName == packageName }?.status
                    if (currentStatus == AppStatus.UP_TO_DATE) {
                        Log.w(TAG, "⏭️ Skipping re-queue for already installed package: $packageName")
                        return@forEach
                    }
                    if (currentStatus == AppStatus.INSTALLING ||
                        installationQueue.any { it.packageName == packageName }) {
                        Log.w(TAG, "⏭️ Skipping re-queue for already-installing package: $packageName")
                        return@forEach
                    }
                    Log.i(TAG, "📋 Queueing for installation: $appName ($packageName)")
                    queueInstallation(packageName, path, appName)
                }
                
                // Show summary toast
                if (completedPackages.isNotEmpty()) {
                    val message = if (completedPackages.size == 1) {
                        "Installing ${completedNames[0]}..."
                    } else {
                        "Installing ${completedPackages.size} apps..."
                    }
                    showToast(message)
                }
                
                // Show failed downloads toast if any
                if (failedPackages.isNotEmpty()) {
                    showToast("${failedPackages.size} downloads failed")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error auto-installing completed downloads", e)
                showError("Failed to auto-install apps: ${e.message}")
            }
        }
    }
    
}


