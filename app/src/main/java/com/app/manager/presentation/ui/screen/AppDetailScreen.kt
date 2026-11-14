package com.app.manager.presentation.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.manager.R
import com.app.manager.domain.model.AppStatus
import com.app.manager.domain.model.RevancedApp
import com.app.manager.presentation.bloc.AppBloc
import com.app.manager.presentation.bloc.AppEvent
import com.app.manager.presentation.ui.components.AppIcon
import com.app.manager.presentation.ui.components.tvFocusBorder
import com.app.manager.presentation.ui.theme.downloadColor
import com.app.manager.presentation.ui.theme.openColor
import com.app.manager.presentation.ui.theme.uninstallColor
import com.app.manager.presentation.ui.theme.updateColor

/**
 * One app's detail page (ported from upstream).
 *
 * Shares the list's [AppBloc], so the live download progress and install status stream in without
 * any extra plumbing; actions dispatch the same events the card on the list does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    app: RevancedApp,
    isFavorite: Boolean,
    bloc: AppBloc,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expandedDescription by remember { mutableStateOf(false) }

    val copyActionLabel = stringResource(R.string.detail_copy_package)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = app.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { bloc.handleEvent(AppEvent.ToggleFavorite(app.packageName)) },
                        modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.filter_favorites),
                            tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Identity
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    url = app.iconUrl,
                    size = 64.dp,
                    cornerRadius = 14.dp,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier
                            .tvFocusBorder(shape = RoundedCornerShape(6.dp))
                            .clickable {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("package", app.packageName))
                                Toast.makeText(context, R.string.detail_package_copied, Toast.LENGTH_SHORT).show()
                            }
                            .semantics {
                                customActions = listOf(CustomAccessibilityAction(copyActionLabel) { true })
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Versions
            DetailInfoRow(
                label = stringResource(R.string.installed_label),
                value = app.currentVersion ?: stringResource(R.string.filter_not_installed).replaceFirstChar { it.lowercase() }
            )
            DetailInfoRow(label = stringResource(R.string.latest_label), value = app.latestVersionName)
            app.variantLabel?.takeIf { it.isNotBlank() }?.let {
                DetailInfoRow(label = stringResource(R.string.detail_variant), value = it)
            }
            app.vendor?.takeIf { it.isNotBlank() }?.let {
                DetailInfoRow(label = stringResource(R.string.detail_vendor), value = it)
            }
            app.patchVersion?.takeIf { it.isNotBlank() }?.let {
                DetailInfoRow(label = stringResource(R.string.detail_patches), value = it)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // MicroG requirement
            if (app.requiresMicroG) {
                Text(
                    text = stringResource(R.string.requires_microg),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA726)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Description
            Text(
                text = app.description.ifBlank { stringResource(R.string.app_subtitle) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expandedDescription) Int.MAX_VALUE else 4
            )
            if (app.description.length > 160) {
                TextButton(expandedDescription, onToggle = { expandedDescription = !expandedDescription })
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Download progress
            if (app.status == AppStatus.DOWNLOADING && app.downloadProgress > 0) {
                LinearProgressIndicator(
                    progress = { app.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.downloading_progress, (app.downloadProgress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions — same events the list card dispatches
            DetailActionButtons(app = app, bloc = bloc)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TextButton(expanded: Boolean, onToggle: () -> Unit) {
    Text(
        text = stringResource(if (expanded) R.string.show_less else R.string.read_more),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 4.dp)
            .tvFocusBorder(shape = RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
    )
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DetailActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(44.dp)
            .tvFocusBorder(shape = RoundedCornerShape(10.dp))
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text)
    }
}

@Composable
private fun DetailActionButtons(app: RevancedApp, bloc: AppBloc) {
    val onDownload: () -> Unit = {
        bloc.handleEvent(AppEvent.DownloadApp(app.packageName, app.downloadUrl))
    }
    val onOpen: () -> Unit = { bloc.handleEvent(AppEvent.OpenApp(app.packageName)) }
    val onUninstall: () -> Unit = { bloc.handleEvent(AppEvent.UninstallApp(app.packageName)) }
    val onReinstall: () -> Unit = { bloc.handleEvent(AppEvent.ReinstallApp(app.packageName)) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (app.status) {
            AppStatus.NOT_INSTALLED -> {
                DetailActionButton(
                    text = stringResource(R.string.download),
                    icon = Icons.Default.Download,
                    color = MaterialTheme.colorScheme.downloadColor,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AppStatus.UPDATE_AVAILABLE -> {
                DetailActionButton(
                    text = stringResource(R.string.update),
                    icon = Icons.Default.Refresh,
                    color = MaterialTheme.colorScheme.updateColor,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailActionButton(
                        text = stringResource(R.string.open),
                        icon = Icons.Default.PlayArrow,
                        color = MaterialTheme.colorScheme.openColor,
                        onClick = onOpen,
                        modifier = Modifier.weight(1f)
                    )
                    DetailActionButton(
                        text = stringResource(R.string.uninstall),
                        icon = Icons.Default.Delete,
                        color = MaterialTheme.colorScheme.uninstallColor,
                        onClick = onUninstall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            AppStatus.UP_TO_DATE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailActionButton(
                        text = stringResource(R.string.open),
                        icon = Icons.Default.PlayArrow,
                        color = MaterialTheme.colorScheme.openColor,
                        onClick = onOpen,
                        modifier = Modifier.weight(1f)
                    )
                    DetailActionButton(
                        text = stringResource(R.string.uninstall),
                        icon = Icons.Default.Delete,
                        color = MaterialTheme.colorScheme.uninstallColor,
                        onClick = onUninstall,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = onReinstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusBorder(shape = RoundedCornerShape(10.dp))
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.reinstall))
                }
            }
            AppStatus.DOWNLOADING -> {
                OutlinedButton(
                    onClick = { bloc.handleEvent(AppEvent.CancelDownload(app.packageName)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusBorder(shape = RoundedCornerShape(10.dp))
                ) {
                    Text(stringResource(R.string.download_cancelled))
                }
            }
            else -> {
                // INSTALLING / UNINSTALLING / READY_TO_INSTALL / UNKNOWN — read-only state text
                Text(
                    text = when (app.status) {
                        AppStatus.INSTALLING -> stringResource(R.string.installing)
                        AppStatus.UNINSTALLING -> stringResource(R.string.uninstalling)
                        else -> stringResource(R.string.unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
