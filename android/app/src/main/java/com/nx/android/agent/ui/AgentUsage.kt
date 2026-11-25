package com.nx.android.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nx.android.R
import com.nx.android.agent.AgentTokenUsage

/**
 * 这一局的用量：上下文占了多少、主会话花了多少、子代理花了多少。
 *
 * 两半分开展示，不给合计——两个口径不同的数凑成一个合计会互相打架（引擎账本里的与逐次
 * 运行累加的，含的东西不一样）。
 *
 * 数字全是引擎给的：主会话那一半来自引擎那本账（`get_session_stats`），子代理那一半是
 * 每次运行自己那些回答的 `usage` 加起来。
 */
@Composable
internal fun AgentUsageDialog(
    contextTokens: Long,
    contextWindow: Long,
    session: AgentTokenUsage,
    subagents: AgentTokenUsage,
    subagentRuns: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.agent_usage_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UsageLine(
                    label = stringResource(R.string.agent_usage_context),
                    value = if (contextWindow > 0) {
                        formatUsageTokens(contextTokens) + " / " + formatUsageTokens(contextWindow) +
                            " " + usagePct(contextTokens, contextWindow)
                    } else {
                        formatUsageTokens(contextTokens)
                    },
                )
                UsageBlock(
                    label = stringResource(R.string.agent_usage_session),
                    usage = session,
                )
                UsageBlock(
                    label = stringResource(R.string.agent_usage_subagents, subagentRuns),
                    usage = subagents,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

/** 一行：左边是什么，右边是多少。 */
@Composable
private fun UsageLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = AgentPalette.Ink2,
            // 标签长（英文的 "Sub-agents (5 runs)"）也不挤掉右边的数
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AgentPalette.Ink,
        )
    }
}

/**
 * 一块用量：标题是「主会话」还是「子代理」，底下是总数、成本与分项。
 *
 * 没有价目的模型成本是 0——那就不写成本那一段，不写「$0.00」。
 */
@Composable
private fun UsageBlock(label: String, usage: AgentTokenUsage) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UsageLine(
            label = label,
            value = if (usage.cost > 0.0) {
                stringResource(
                    R.string.agent_usage_tokens_cost,
                    formatUsageTokens(usage.total),
                    usage.cost,
                )
            } else {
                stringResource(R.string.agent_usage_tokens, formatUsageTokens(usage.total))
            },
        )
        val split = usageSplit(usage)
        if (split.isNotBlank()) {
            Text(
                split,
                style = MaterialTheme.typography.bodySmall,
                color = AgentPalette.Ink2,
            )
        }
    }
}

/**
 * 分项那一行：入多少、出多少、缓存读多少（缓存写只在有的时候才写）。
 *
 * 没有就空着——一块什么都没花的运行不写分项。
 */
@Composable
private fun usageSplit(usage: AgentTokenUsage): String {
    if (usage.total <= 0L) return ""
    val read = stringResource(
        R.string.agent_usage_split,
        formatUsageTokens(usage.input),
        formatUsageTokens(usage.output),
        formatUsageTokens(usage.cacheRead),
    )
    if (usage.cacheWrite <= 0L) return read
    return read + stringResource(R.string.agent_usage_split_write, formatUsageTokens(usage.cacheWrite))
}

/** 用量面板里的写法：百万用 M，一千以上用 k。 */
internal fun formatUsageTokens(tokens: Long): String = when {
    tokens < 1_000 -> "$tokens"
    tokens < 1_000_000 -> "%.1fk".format(tokens / 1_000.0)
    else -> "%.1fM".format(tokens / 1_000_000.0)
}
