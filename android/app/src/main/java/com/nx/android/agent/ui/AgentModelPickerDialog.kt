package com.nx.android.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nx.android.R

/**
 * The models the endpoint offered: a list the player picks from, with a search on top.
 *
 * The list can be long (an account may see dozens), so it is a scrolling list with a filter, not a
 * dropdown that would push the dialog off the screen.
 */
@Composable
fun AgentModelPickerDialog(
    models: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val q = query.trim()
        if (q.isEmpty()) models else models.filter { it.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 跟模型编辑器一样：平台默认的宽度太窄，装不下这份清单
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.agent_model_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_model_search)) },
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered) { id ->
                        TextButton(
                            onClick = { onPick(id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                id,
                                Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.agent_model_pick_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.agent_model_cancel))
            }
        },
    )
}
