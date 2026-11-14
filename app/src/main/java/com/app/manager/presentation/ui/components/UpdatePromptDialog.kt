package com.app.manager.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.manager.R
import com.app.manager.domain.model.RevancedApp

/**
 * The "N updates available" prompt: batch list of updatable apps with individual checkboxes, so
 * the user can take a subset instead of everything. Actions sit in a right-aligned FlowRow —
 * on a narrow screen the three text buttons would otherwise clip horizontally (upstream e0f8096).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdatePromptDialog(
    updatableApps: List<RevancedApp>,
    onInstallSelected: (List<RevancedApp>) -> Unit,
    onSkipToday: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(updatableApps) { mutableStateOf(updatableApps.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.update_prompt_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.update_prompt_description),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogListMaxHeight())
                        .padding(top = 8.dp)
                ) {
                    items(updatableApps, key = { "${it.packageName}#${it.index}" }) { app ->
                        AppCheckRow(
                            app = app,
                            subtitle = versionSubtitle(app.currentVersion, app.latestVersionName),
                            isChecked = app in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app else selected - app
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onTurnOff) {
                    Text(stringResource(R.string.update_prompt_turn_off))
                }
                TextButton(onClick = onSkipToday) {
                    Text(stringResource(R.string.update_prompt_skip_today))
                }
                Button(
                    onClick = { onInstallSelected(selected.toList()) },
                    enabled = selected.isNotEmpty()
                ) {
                    Text(stringResource(R.string.update_prompt_install, selected.size))
                }
            }
        },
        dismissButton = {},
        modifier = modifier
    )
}

/** "current → latest", or just "latest" when the current version is unknown. */
private fun versionSubtitle(currentVersion: String?, latestVersion: String): String = when {
    currentVersion.isNullOrBlank() -> latestVersion
    else -> "$currentVersion → $latestVersion"
}
