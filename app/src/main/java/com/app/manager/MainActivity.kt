package com.app.manager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import android.provider.Settings
import com.app.manager.core.common.LocaleHelper
import com.app.manager.data.local.preferences.PreferencesManager
import com.app.manager.presentation.bloc.AppBloc
import com.app.manager.presentation.ui.screen.MainScreen
import com.app.manager.presentation.ui.theme.RevancedManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main activity for ReVanced Manager
 * Updated to use AppCompatActivity for modern language switching support
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var preferencesManager: PreferencesManager

    private var promptedNotification = false
    private var promptedInstallSource = false
    private var promptedAllFilesAccess = false
    private var promptedStorage = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestStartupPermissions()
        }

    private val storagePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestStartupPermissions()
        }

    private val installUnknownAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestStartupPermissions()
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestStartupPermissions()
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Install splash screen
        installSplashScreen()
        
        // Add smooth transition for activity recreation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        
        // Make the app full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContent {
            val viewModel: AppBloc = hiltViewModel()
            val state by viewModel.state.collectAsState()
            
            val themeMode = when (val currentState = state) {
                is com.app.manager.presentation.bloc.AppState.Success -> currentState.config.themeMode
                is com.app.manager.presentation.bloc.AppState.Error -> currentState.config.themeMode
                else -> com.app.manager.domain.model.ThemeMode.SYSTEM
            }
            
            RevancedManagerTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }

        requestStartupPermissions()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Activity cleanup - no special handling needed for the new approach
    }
    
    override fun attachBaseContext(newBase: Context?) {
        // Apply language configuration using LocaleHelper
        newBase?.let { context ->
            val sharedPrefs = context.getSharedPreferences("ReVancedManagerPreferences", Context.MODE_PRIVATE)
            val languageCode = sharedPrefs.getString("language", null)
            
            if (languageCode != null) {
                // Handle language codes with country codes (e.g., es-ES -> es)
                val cleanLanguageCode = if (languageCode.contains("-")) {
                    languageCode.split("-")[0]
                } else {
                    languageCode
                }
                
                val contextWithLanguage = LocaleHelper.setLocale(context, cleanLanguageCode)
                super.attachBaseContext(contextWithLanguage)
            } else {
                super.attachBaseContext(newBase)
            }
        } ?: super.attachBaseContext(newBase)
    }

    private fun requestStartupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            !promptedNotification
        ) {
            promptedNotification = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val canInstallUnknownApps = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
        if (!canInstallUnknownApps && !promptedInstallSource) {
            promptedInstallSource = true
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            installUnknownAppsLauncher.launch(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager() &&
            !promptedAllFilesAccess
        ) {
            promptedAllFilesAccess = true
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            val targetIntent = if (intent.resolveActivity(packageManager) != null) intent else fallbackIntent
            allFilesAccessLauncher.launch(targetIntent)
            return
        }

        val storagePermissions = requiredStoragePermissions()
        if (storagePermissions.isNotEmpty() &&
            storagePermissions.any { !hasPermission(it) } &&
            !promptedStorage
        ) {
            promptedStorage = true
            storagePermissionsLauncher.launch(storagePermissions)
        }
    }

    private fun requiredStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            emptyArray()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}




