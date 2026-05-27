package com.app.manager.presentation.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.manager.R
import com.app.manager.domain.model.AppConfig
import com.app.manager.domain.model.Language
import com.app.manager.domain.model.ThemeMode

/**
 * Configuration dialog for app settings with improved dropdown-style UI
 */
@Composable
fun ConfigDialog(
    config: AppConfig,
    onSave: (AppConfig) -> Unit,
    onCancel: () -> Unit,
    onCompactModeChange: (Boolean) -> Unit = {},
    onClearCache: () -> Unit = {}
) {
    val context = LocalContext.current

    // Initialize state with current config values to ensure they display properly
    var selectedThemeMode by remember(config) { mutableStateOf(config.themeMode) }
    var selectedLanguage by remember(config) { mutableStateOf(config.language) }
    var compactModeEnabled by remember(config) { mutableStateOf(config.compactMode) }
    var debugLoggingEnabled by remember(config) { mutableStateOf(config.debugLogging) }
    var downloadPath by remember(config) { mutableStateOf(config.downloadPath) }
    var showBetaEnabled by remember(config) { mutableStateOf(config.showBeta) }
    var selectedVendor by remember(config) { mutableStateOf(config.vendor) }
    var showThemeSelector by remember { mutableStateOf(false) }
    var showLanguageSelector by remember { mutableStateOf(false) }

    // Interaction sources for D-pad / TV focus on collapse header rows
    val themeCollapseInteraction = remember { MutableInteractionSource() }
    val isThemeHeaderFocused by themeCollapseInteraction.collectIsFocusedAsState()
    val langCollapseInteraction = remember { MutableInteractionSource() }
    val isLangHeaderFocused by langCollapseInteraction.collectIsFocusedAsState()
    val pickFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            downloadPath = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Theme Section with dropdown-style selector
                Column {
                    Text(
                        text = stringResource(R.string.theme),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (showThemeSelector) {
                        // Theme dropdown list
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(4.dp)
                            ) {
                                // Header with collapse button
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = if (isThemeHeaderFocused) 2.dp else 0.dp,
                                            color = if (isThemeHeaderFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable(
                                            interactionSource = themeCollapseInteraction,
                                            indication = LocalIndication.current
                                        ) { showThemeSelector = false }
                                        .background(
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.theme),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Collapse theme list",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                // Theme options
                                ThemeMode.entries.forEach { themeMode ->
                                    ThemeItem(
                                        themeMode = themeMode,
                                        isSelected = selectedThemeMode == themeMode,
                                        onSelect = { 
                                            selectedThemeMode = themeMode
                                            showThemeSelector = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // Selected theme display
                        Card(
                            onClick = { showThemeSelector = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getThemeDisplayText(selectedThemeMode),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.tap_to_change),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand theme list",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Language Section with dropdown-style selector
                Column {
                    Text(
                        text = stringResource(R.string.language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (showLanguageSelector) {
                        // Language dropdown list
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(4.dp)
                            ) {
                                // Header with collapse button
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = if (isLangHeaderFocused) 2.dp else 0.dp,
                                            color = if (isLangHeaderFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable(
                                            interactionSource = langCollapseInteraction,
                                            indication = LocalIndication.current
                                        ) { showLanguageSelector = false }
                                        .background(
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.select_language),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Collapse language list",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                // Language list with max height and scroll
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 250.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(Language.entries) { language ->
                                        LanguageItem(
                                            language = language,
                                            isSelected = selectedLanguage == language,
                                            onSelect = { 
                                                selectedLanguage = language
                                                showLanguageSelector = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Selected language display
                        Card(
                            onClick = { showLanguageSelector = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedLanguage.flagEmoji,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontSize = 20.sp,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    
                                    Text(
                                        text = selectedLanguage.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.tap_to_change),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand language list",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Compact Mode Section
                Column {
                    Text(
                        text = stringResource(R.string.compact_mode),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                2.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.compact_mode),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            
                            Switch(
                            checked = compactModeEnabled,
                            onCheckedChange = {
                                compactModeEnabled = it
                            }
                        )
                        }
                    }
                }

                // Debug Logging Section
                Column {
                    Text(
                        text = stringResource(R.string.debug_logging),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                2.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.debug_logging),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )

                            Switch(
                                checked = debugLoggingEnabled,
                                onCheckedChange = {
                                    debugLoggingEnabled = it
                                }
                            )
                        }
                    }
                }

                // Beta Versions Section
                Column {
                    Text(
                        text = "Phiên bản thử nghiệm",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                2.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hiển thị bản thử nghiệm (Beta)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Bao gồm phiên bản đang phát triển",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = showBetaEnabled,
                                onCheckedChange = {
                                    showBetaEnabled = it
                                }
                            )
                        }
                    }
                }

                // Vendor Selection Section
                Column {
                    Text(
                        text = "Nhà cung cấp (Vendor)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // MorPhe option
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedVendor = "morphe" },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedVendor == "morphe")
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (selectedVendor == "morphe") 4.dp else 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "MorPhe",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (selectedVendor == "morphe") FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "YouTube Morphe + MicroG RE",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // ReVanced option
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedVendor = "revanced" },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedVendor == "revanced")
                                    MaterialTheme.colorScheme.secondaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (selectedVendor == "revanced") 4.dp else 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "ReVanced",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (selectedVendor == "revanced") FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "YouTube ReVanced + MicroG",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Download path section
                Column {
                    Text(
                        text = stringResource(R.string.download_path),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = if (downloadPath.isBlank()) stringResource(R.string.download_path_not_set) else downloadPath,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { pickFolderLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.choose_folder))
                        }
                        OutlinedButton(
                            onClick = { downloadPath = "" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.use_default_folder))
                        }
                    }
                    Text(
                        text = stringResource(R.string.download_path_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Clear Cache Button
                OutlinedButton(
                    onClick = onClearCache,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_cache))
                }

            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        AppConfig(
                            themeMode = selectedThemeMode,
                            language = selectedLanguage,
                            compactMode = compactModeEnabled,
                            debugLogging = debugLoggingEnabled,
                            downloadPath = downloadPath.trim(),
                            vendor = selectedVendor,
                            showBeta = showBetaEnabled
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
