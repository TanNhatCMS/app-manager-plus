package com.app.manager.presentation.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.manager.R
import com.app.manager.domain.model.AppConfig
import com.app.manager.domain.model.Language
import com.app.manager.domain.model.ThemeMode
import com.app.manager.presentation.ui.components.SettingsSwitchRow
import com.app.manager.presentation.ui.components.tvFocusBorder

/**
 * Full-screen settings view (ported from the settings-as-popup design).
 *
 * Owns its own Scaffold and top bar: settings is a navigation destination now, not a mode the
 * list screen switches into, so nothing above it is drawing an app bar on its behalf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentConfig: AppConfig,
    onSave: (AppConfig) -> Unit,
    onCancel: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTheme by remember(currentConfig) { mutableStateOf(currentConfig.themeMode) }
    var selectedLanguage by remember(currentConfig) { mutableStateOf(currentConfig.language) }
    var compactMode by remember(currentConfig) { mutableStateOf(currentConfig.compactMode) }
    var debugLogging by remember(currentConfig) { mutableStateOf(currentConfig.debugLogging) }
    var showBeta by remember(currentConfig) { mutableStateOf(currentConfig.showBeta) }
    var showUpdatePrompt by remember(currentConfig) { mutableStateOf(currentConfig.showUpdatePrompt) }
    var autoUpdateCheck by remember(currentConfig) { mutableStateOf(currentConfig.autoUpdateCheck) }
    var patchUpdatesEnabled by remember(currentConfig) { mutableStateOf(currentConfig.patchUpdatesEnabled) }
    var microgIconHidden by remember(currentConfig) { mutableStateOf(currentConfig.microgIconHidden) }
    var downloadPath by remember(currentConfig) { mutableStateOf(currentConfig.downloadPath) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        downloadPath = uri.toString()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Appearance section
                SettingsSectionHeader(stringResource(R.string.theme))
                ThemeSelector(
                    selectedTheme = selectedTheme,
                    onThemeSelected = { selectedTheme = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Language section
                SettingsSectionHeader(stringResource(R.string.language))
                LanguageSelector(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { selectedLanguage = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Display section
                SettingsSectionHeader(stringResource(R.string.settings_section_display))
                SettingsSwitchRow(
                    title = stringResource(R.string.compact_mode),
                    checked = compactMode,
                    onCheckedChange = { compactMode = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Updates section
                SettingsSectionHeader(stringResource(R.string.settings_section_updates))
                SettingsSwitchRow(
                    title = stringResource(R.string.show_update_prompt),
                    subtitle = stringResource(R.string.show_update_prompt_description),
                    checked = showUpdatePrompt,
                    onCheckedChange = { showUpdatePrompt = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.auto_update_check),
                    subtitle = stringResource(R.string.auto_update_check_description),
                    checked = autoUpdateCheck,
                    onCheckedChange = { autoUpdateCheck = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.patch_updates),
                    subtitle = stringResource(R.string.patch_updates_description),
                    checked = patchUpdatesEnabled,
                    onCheckedChange = { patchUpdatesEnabled = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // MicroG section
                SettingsSectionHeader(stringResource(R.string.settings_section_microg))
                SettingsSwitchRow(
                    title = stringResource(R.string.microg_hide_icon),
                    subtitle = stringResource(R.string.microg_hide_icon_description),
                    checked = microgIconHidden,
                    onCheckedChange = { microgIconHidden = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Vendor & beta section
                SettingsSectionHeader(stringResource(R.string.settings_section_beta))
                SettingsSwitchRow(
                    title = stringResource(R.string.show_beta),
                    subtitle = stringResource(R.string.show_beta_description),
                    checked = showBeta,
                    onCheckedChange = { showBeta = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Storage section
                SettingsSectionHeader(stringResource(R.string.settings_section_storage))
                DownloadPathCard(
                    downloadPath = downloadPath,
                    onPickFolder = { folderPicker.launch(null) },
                    onUseDefault = { downloadPath = "" },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                OutlinedButton(
                    onClick = { showClearCacheConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .tvFocusBorder(shape = RoundedCornerShape(50))
                ) {
                    Text(stringResource(R.string.clear_cache))
                }

                // Debug section
                SettingsSectionHeader(stringResource(R.string.settings_section_debug))
                SettingsSwitchRow(
                    title = stringResource(R.string.debug_logging),
                    checked = debugLogging,
                    onCheckedChange = { debugLogging = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Sticky bottom action bar
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusBorder(shape = RoundedCornerShape(50))
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onSave(
                            AppConfig(
                                themeMode = selectedTheme,
                                language = selectedLanguage,
                                compactMode = compactMode,
                                debugLogging = debugLogging,
                                downloadPath = downloadPath,
                                vendor = currentConfig.vendor,
                                showBeta = showBeta,
                                showUpdatePrompt = showUpdatePrompt,
                                autoUpdateCheck = autoUpdateCheck,
                                patchUpdatesEnabled = patchUpdatesEnabled,
                                microgIconHidden = microgIconHidden
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusBorder(shape = RoundedCornerShape(50))
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    // Clear-cache confirmation dialog
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.clear_cache_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        onClearCache()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// ---- Section header ----

@Composable
private fun SettingsSectionHeader(title: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

// ---- Download path ----

@Composable
private fun DownloadPathCard(
    downloadPath: String,
    onPickFolder: () -> Unit,
    onUseDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.download_path),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = downloadPath.ifBlank { stringResource(R.string.download_path_not_set) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFolder, modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))) {
                    Text(stringResource(R.string.choose_folder))
                }
                if (downloadPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = onUseDefault,
                        modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))
                    ) {
                        Text(stringResource(R.string.use_default_folder))
                    }
                }
            }
        }
    }
}

// ---- Theme / language selectors (dropdown cards) ----

@Composable
private fun ThemeSelector(
    selectedTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownCard(
        modifier = modifier,
        label = when (selectedTheme) {
            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
            ThemeMode.DARK -> stringResource(R.string.theme_dark)
            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
        },
        options = ThemeMode.entries.map { mode ->
            when (mode) {
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
            } to mode
        },
        selectedValue = selectedTheme,
        onSelected = { onThemeSelected(it) }
    )
}

@Composable
private fun LanguageSelector(
    selectedLanguage: Language,
    onLanguageSelected: (Language) -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownCard(
        modifier = modifier,
        label = "${selectedLanguage.flagEmoji} ${selectedLanguage.displayName}",
        options = Language.entries.map { "${it.flagEmoji} ${it.displayName}" to it },
        selectedValue = selectedLanguage,
        onSelected = { onLanguageSelected(it) }
    )
}

@Composable
private fun <T> DropdownCard(
    label: String,
    options: List<Pair<String, T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusBorder(shape = RoundedCornerShape(16.dp))
            .clickable { expanded = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
        ) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        if (value != selectedValue) onSelected(value)
                    }
                )
            }
        }
    }
    Spacer(modifier = Modifier.width(0.dp))
}
