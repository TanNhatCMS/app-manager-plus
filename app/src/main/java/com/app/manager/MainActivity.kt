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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    private var permissionRefreshTick by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionState()
        }

    private val storagePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    private val installUnknownAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionState()
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionState()
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Install splash screen
        installSplashScreen()
        
        // Make the app full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContent {
            val viewModel: AppBloc = hiltViewModel()
            val state by viewModel.state.collectAsState()
            permissionRefreshTick
            val permissionSteps = buildPermissionSteps()
            val hasMissingPermission = permissionSteps.any { !it.granted }
            
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
                    if (hasMissingPermission) {
                        PermissionGuideScreen(
                            steps = permissionSteps,
                            onRequestStep = { requestPermissionStep(it) },
                            onRefresh = { refreshPermissionState() }
                        )
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Activity cleanup - no special handling needed for the new approach
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }
    
    override fun attachBaseContext(newBase: Context?) {
        // Apply language configuration using LocaleHelper
        newBase?.let { context ->
            val sharedPrefs = context.getSharedPreferences("ReVancedManagerPreferences", Context.MODE_PRIVATE)
            val languageCode = sharedPrefs.getString("language", "vi") ?: "vi"

            // Handle language codes with country codes (e.g., es-ES -> es)
            val cleanLanguageCode = if (languageCode.contains("-")) {
                languageCode.split("-")[0]
            } else {
                languageCode
            }

            val contextWithLanguage = LocaleHelper.setLocale(context, cleanLanguageCode)
            super.attachBaseContext(contextWithLanguage)
        } ?: super.attachBaseContext(newBase)
    }

    private fun refreshPermissionState() {
        permissionRefreshTick++
    }

    private fun buildPermissionSteps(): List<PermissionStep> {
        val steps = mutableListOf<PermissionStep>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            steps.add(
                PermissionStep(
                    type = PermissionStepType.NOTIFICATION,
                    title = getString(R.string.permission_notification_title),
                    description = getString(R.string.permission_notification_desc),
                    granted = hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }

        val canInstallUnknownApps =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
        steps.add(
            PermissionStep(
                type = PermissionStepType.INSTALL_UNKNOWN_APP,
                title = getString(R.string.permission_install_title),
                description = getString(R.string.permission_install_desc),
                granted = canInstallUnknownApps
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            steps.add(
                PermissionStep(
                    type = PermissionStepType.ALL_FILES_ACCESS,
                    title = getString(R.string.permission_storage_title),
                    description = getString(R.string.permission_storage_desc),
                    granted = Environment.isExternalStorageManager()
                )
            )
        } else {
            val storagePermissions = requiredStoragePermissions()
            steps.add(
                PermissionStep(
                    type = PermissionStepType.STORAGE_PERMISSION,
                    title = getString(R.string.permission_storage_title),
                    description = getString(R.string.permission_storage_legacy_desc),
                    granted = storagePermissions.isEmpty() || storagePermissions.all { hasPermission(it) }
                )
            )
        }

        return steps
    }

    private fun requestPermissionStep(stepType: PermissionStepType) {
        when (stepType) {
            PermissionStepType.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    promptedNotification = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            PermissionStepType.INSTALL_UNKNOWN_APP -> {
                promptedInstallSource = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                installUnknownAppsLauncher.launch(intent)
            }

            PermissionStepType.ALL_FILES_ACCESS -> {
                promptedAllFilesAccess = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                val targetIntent = if (intent.resolveActivity(packageManager) != null) intent else fallbackIntent
                allFilesAccessLauncher.launch(targetIntent)
            }

            PermissionStepType.STORAGE_PERMISSION -> {
                val storagePermissions = requiredStoragePermissions()
                if (storagePermissions.isNotEmpty()) {
                    promptedStorage = true
                    storagePermissionsLauncher.launch(storagePermissions)
                }
            }
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

private enum class PermissionStepType {
    NOTIFICATION,
    INSTALL_UNKNOWN_APP,
    ALL_FILES_ACCESS,
    STORAGE_PERMISSION
}

private data class PermissionStep(
    val type: PermissionStepType,
    val title: String,
    val description: String,
    val granted: Boolean
)

@androidx.compose.runtime.Composable
private fun PermissionGuideScreen(
    steps: List<PermissionStep>,
    onRequestStep: (PermissionStepType) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.permission_guide_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.permission_guide_desc),
            style = MaterialTheme.typography.bodyMedium
        )

        steps.forEach { step ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = (if (step.granted) "\u2705 " else "\u2B1C ") + step.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!step.granted) {
                        Button(onClick = { onRequestStep(step.type) }) {
                            Text(text = androidx.compose.ui.res.stringResource(R.string.permission_grant_now))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) {
                Text(text = androidx.compose.ui.res.stringResource(R.string.permission_check_again))
            }
        }
    }
}




