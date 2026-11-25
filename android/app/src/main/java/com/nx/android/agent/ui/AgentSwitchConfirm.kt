package com.nx.android.agent.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.nx.android.R

/**
 * 切会话前的二次确认：当前会话有正在跑的子任务时，把代价说清楚再问一句。
 *
 * 子代理跟着会话活——切走会把正在跑的那些掐断。所以这里写明有几个在跑、切过去会中断
 * 它们，并给出两条路：仍要切换，或者留下（那些任务接着跑）。
 */
@Composable
internal fun AgentSwitchConfirmDialog(
    ask: AgentSwitchAsk?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (ask == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.agent_switch_confirm_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                stringResource(R.string.agent_switch_confirm_text, ask.running),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.agent_switch_confirm_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
