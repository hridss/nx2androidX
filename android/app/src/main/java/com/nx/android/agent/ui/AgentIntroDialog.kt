package com.nx.android.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nx.android.R

/**
 * The introduction the player gets once per version while the assistant is not set up.
 *
 * It is modal: it does not close by itself. The player picks one of the two buttons — "set it up"
 * takes them to the settings, "later" puts it aside until the next version.
 */
@Composable
fun AgentIntroDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        // the box does not close by itself: only the two buttons below
        onDismissRequest = { },
        title = {
            Text(stringResource(R.string.agent_intro_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.agent_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IntroUse(stringResource(R.string.agent_intro_use_crash))
                IntroUse(stringResource(R.string.agent_intro_use_build))
                IntroUse(stringResource(R.string.agent_intro_use_library))
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.agent_intro_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.agent_intro_later))
            }
        },
    )
}

/** One of the things the assistant is for. */
@Composable
private fun IntroUse(text: String) {
    Text(
        "· $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
