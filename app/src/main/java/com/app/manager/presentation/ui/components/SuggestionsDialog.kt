package com.app.manager.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.manager.R
import com.app.manager.domain.model.RevancedApp

/**
 * The first-run suggestion popup: tickable rows for catalog entries worth installing now —
 * offered when an app that needs MicroG is present but no MicroG build is installed.
 */
@Composable
fun SuggestionsDialog(
    suggestions: List<RevancedApp>,
    onConfirm: (List<RevancedApp>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(suggestions) { mutableStateOf(suggestions.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.microg_suggestion_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.microg_suggestion_description),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogListMaxHeight())
                        .padding(top = 8.dp)
                ) {
                    items(suggestions, key = { "${it.packageName}#${it.index}" }) { app ->
                        AppCheckRow(
                            app = app,
                            subtitle = app.packageName,
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
            Button(
                onClick = { onConfirm(selected.toList()) },
                shape = RoundedCornerShape(50),
                modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.microg_suggestion_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusBorder(shape = RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.microg_suggestion_dismiss))
            }
        },
        modifier = modifier
    )
}

/** Cap the list so the buttons stay reachable on small screens. */
internal fun dialogListMaxHeight(): androidx.compose.ui.unit.Dp = 320.dp
