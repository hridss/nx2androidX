package com.nx.android.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.MarkdownTypography
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nx.android.R
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.components.Hint
import com.nx.android.core.ui.components.StatusDot
import com.nx.android.core.ui.nav.NavKey
import com.nx.android.core.ui.nav.OnNavKey
import com.nx.android.agent.AgentFailure
import com.nx.android.agent.AgentMessage
import com.nx.android.agent.AgentModelConfig
import com.nx.android.agent.AgentRole
import com.nx.android.agent.AgentSubagent
import com.nx.android.agent.AgentToolCard
import com.nx.android.agent.AgentToolState

/**
 * The assistant's chat page: a full cover like the settings and the FAQ, but portrait (the activity turns
 * the screen while it is open).
 *
 * The top bar says who answers (a capsule: provider · model, or a gray word when nobody does); the flow is
 * the conversation — the player's bubbles on the right, the assistant's words running the full width while
 * they stream, the tools it used folded into one-line cards; the bottom bar sends, or stops while a turn
 * runs. Without a configured key the setup sheet stands in for the flow: the same form the ⋯ menu reopens.
 */
@Composable
fun AgentScreen(
    conversation: AgentConversationState,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
    navActive: Boolean = false,
    onRunGame: (String) -> Unit = {},
) {
    val state by conversation.uiState.collectAsStateWithLifecycle()
    BackHandler { onClose() }
    // the gamepad's back key: the same door as the system's (this page only knows how to close)
    OnNavKey(enabled = navActive) { key ->
        if (key == NavKey.Back) onClose()
    }
    // the ⋯ menu's "model settings" opens the settings page at the assistant's section; without a key the
    // page opens there by itself
    // the draft's sheet: the ⋯ menu's "model settings" opens the settings page at the assistant's
    // section; without a key the page opens there by itself
    val showSetup = !state.configured
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val copiedWord = stringResource(R.string.agent_copied)
    val copyLabel = stringResource(R.string.agent_title)
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    // 复制：把这条回答的原始 Markdown 放上剪贴板（围栏与缩进都在）
    val onCopy: (AgentMessage) -> Unit = { message ->
        clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, copyPayloadOf(message)))
        Toast.makeText(context, copiedWord, Toast.LENGTH_SHORT).show()
    }

    // new content arrives at the bottom: follow it (not while the player is scrolling up to read)
    val last = state.messages.lastOrNull()
    // every (re)open lands at the true bottom — but the markdown is laid out after the jump, so
    // the landing follows the layout until it settles
    var followBottom by remember { mutableStateOf(false) }
    var followed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // the models are read again: the settings page may have changed them while this was closed
        conversation.refreshModels()
        // 历史第一次打开就读：引擎起进程那零点几秒由加载态盖住
        conversation.ensureHistory()
    }
    // 消息到了才跟：万一开页时对话还没载入，别把这一跟的机会烧掉
    LaunchedEffect(state.messages) {
        if (!shouldFollowToBottom(open = true, followed = followed, messageCount = state.messages.size)) {
            return@LaunchedEffect
        }
        followed = true
        followBottom = true
    }
    FollowBottom(listState, follow = followBottom, onStop = { followBottom = false })
    // 正文一长就把玩家按回底部——但玩家自己往上拖了就不跟了
    val pinned = rememberPinnedToBottom(listState)
    // 子 Agent 的状态变了也带到底部：它跑完了、又开始续跑——玩家贴着底就该看见
    val subagentMark = state.subagents.joinToString(",") { "${it.runId}:${it.status}" }
    LaunchedEffect(state.messages.size, last?.text?.length, last?.tools?.size, subagentMark) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!pinned.value) return@LaunchedEffect
        listState.scrollToBottom()
    }
    // markdown 提前在后台解析好：条目进屏时高度一次就对，滚动不再等解析
    val mdStates by conversation.markdownCache.ready.collectAsStateWithLifecycle()
    // markdown 解析落地会改高度：还贴着底就再按一次底（玩家翻上去了就不动）
    LaunchedEffect(mdStates) {
        if (!pinned.value) return@LaunchedEffect
        listState.scrollToBottom()
    }

    // the light sheet: everything inside resolves ink-on-white, whatever the app's dark theme says
    MaterialTheme(colorScheme = AgentLightScheme, typography = AgentTypography) {
    Box(Modifier.fillMaxSize().background(AgentPalette.Ground)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentTopBar(
                state.config,
                onBack = onClose,
                onNewSession = { conversation.requestSwitch(null) },
                onOpenSetup = onOpenSettings,
            )
            state.error?.let { reason ->
                ErrorStrip(reason) { conversation.dismissError() }
            }
            if (showSetup) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.heightIn(min = 80.dp))
                    Hint(stringResource(R.string.agent_setup_prompt), size = 13.sp)
                    CapsuleButton(
                        stringResource(R.string.agent_menu_settings),
                        onOpenSettings,
                        Modifier.fillMaxWidth(0.6f),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
                ) {
                    if (state.messages.isEmpty()) {
                        item {
                            HistoryStatus(
                                state.loading,
                                state.engineMissing,
                                onOpenSettings,
                                Modifier.fillMaxWidth().padding(top = 48.dp),
                            )
                        }
                    }
                    items(state.messages, key = { it.id }) { message ->
                        MessageRow(
                            message,
                            streaming = message.id == state.streamingId,
                            markdownState = mdStates[message.id],
                            onCopy = onCopy,
                            subagents = state.subagents,
                            subagentRuns = state.subagentRuns,
                            caseProgress = state.caseProgress,
                            onRunGame = onRunGame,
                        )
                    }
                }
                AgentInputBar(
                    text = state.draft,
                    onTextChange = { conversation.updateDraft(it) },
                    running = state.running,
                    onSend = { conversation.send(it) },
                    onStop = { conversation.stop() },
                )
            }
        }
        // 切会话前的二次确认：有子代理在跑时，玩家先看一眼代价
        AgentSwitchConfirmDialog(
            ask = state.switchAsk,
            onConfirm = { conversation.confirmSwitch() },
            onDismiss = { conversation.dismissSwitch() },
        )
    }
    } // MaterialTheme(agent light)
}

// ---- the top bar -----------------------------------------------------------------------------------------------

/** 对话区的占位：历史在路上（加载态）、引擎没装（引导安装）、空白新会话（欢迎）。 */
@Composable
internal fun HistoryStatus(
    loading: Boolean,
    engineMissing: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pulse by rememberInfiniteTransition(label = "history-pulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "alpha",
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AgentPalette.Working.copy(alpha = pulse)),
                )
                Text(
                    stringResource(R.string.agent_loading_history),
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentPalette.Ink2,
                )
            }

            engineMissing -> {
                Text(
                    stringResource(R.string.agent_engine_missing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentPalette.Ink2,
                )
                CapsuleButton(
                    stringResource(R.string.agent_go_settings),
                    onOpenSettings,
                    Modifier.fillMaxWidth(0.6f),
                )
            }

            else -> Text(
                stringResource(R.string.agent_empty),
                style = MaterialTheme.typography.bodySmall,
                color = AgentPalette.Ink2,
            )
        }
    }
}

/** The page's header: the way back, the name, who answers, and the two things this page can redo. */
@Composable
private fun AgentTopBar(
    config: AgentModelConfig,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                Modifier.size(22.dp),
                tint = AgentPalette.Ink,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.agent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = AgentPalette.Ink,
            modifier = Modifier.weight(1f),
        )
        StatusCapsule(config)
        Spacer(Modifier.width(10.dp))
        Box {
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable { menuOpen = true }.padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    stringResource(R.string.agent_menu_more),
                    Modifier.size(22.dp),
                    tint = AgentPalette.Ink,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agent_menu_new)) },
                    onClick = { menuOpen = false; onNewSession() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agent_menu_settings)) },
                    onClick = { menuOpen = false; onOpenSetup() },
                )
            }
        }
    }
}

/** Who answers: provider · model in a capsule, or the gray word when nobody does. */
@Composable
private fun StatusCapsule(config: AgentModelConfig) {
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(AgentPalette.Panel)
            .border(1.dp, AgentPalette.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(if (config.configured) AgentPalette.Ready else AgentPalette.Idle, size = 7.dp)
        Text(
            if (config.configured) {
                stringResource(providerLabelRes(config.providerId)) + " · " + shortModel(config.modelName)
            } else {
                stringResource(R.string.agent_status_unset)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (config.configured) AgentPalette.Ink2 else AgentPalette.Idle,
            maxLines = 1,
        )
    }
}

// ---- the strip ---------------------------------------------------------------------------------------------------

/** A turn failed: one line under the top bar, the words say which kind (the code says nothing). */
@Composable
internal fun ErrorStrip(reason: AgentFailure, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AgentPalette.Danger.copy(alpha = 0.10f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(AgentPalette.Danger, size = 7.dp)
        Text(
            stringResource(failureText(reason)),
            style = MaterialTheme.typography.bodySmall,
            color = AgentPalette.Danger,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.common_close),
            style = MaterialTheme.typography.labelSmall,
            color = AgentPalette.Danger,
        )
    }
}

/** Why a turn failed: the data layer reports codes, this page gives the words. */
@StringRes
private fun failureText(reason: AgentFailure): Int = when (reason) {
    AgentFailure.NO_KEY -> R.string.agent_error_no_key
    AgentFailure.BAD_KEY -> R.string.agent_error_bad_key
    AgentFailure.NETWORK -> R.string.agent_error_network
    AgentFailure.MODEL_NO_TOOLS -> R.string.agent_error_no_tools
    AgentFailure.LOOP_ERROR -> R.string.agent_error_loop
}

// ---- the flow ---------------------------------------------------------------------------------------------------

/** One rendered message: the player's bubble, or the assistant's block. */
@Composable
internal fun MessageRow(
    message: AgentMessage,
    streaming: Boolean = false,
    markdownState: MarkdownState? = null,
    onToolsClick: ((AgentMessage) -> Unit)? = null,
    onCopy: ((AgentMessage) -> Unit)? = null,
    subagents: List<AgentSubagent> = emptyList(),
    subagentRuns: Map<String, AgentSubagent> = emptyMap(),
    caseProgress: String? = null,
    onRunGame: (String) -> Unit = {},
) {
    if (message.role == AgentRole.USER) {
        UserBubbleText(message.text, message.pending, onCopy?.let { cb -> { cb(message) } })
    } else {
        AssistantBlock(
            message, streaming, markdownState, onToolsClick, onCopy, subagents, subagentRuns,
            caseProgress, onRunGame,
        )
    }
}

/** What the copy button puts on the clipboard: the message's own text — the answer's markdown, fences and all. */
internal fun copyPayloadOf(message: AgentMessage): String = message.text

/** The player's bubble: the soft navy wash with the hairline, 13sp — the size the answers match. */
@Composable
private fun UserBubbleText(text: String, interrupted: Boolean = false, onCopy: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = AgentPalette.Ink,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .background(AgentPalette.Bubble, RoundedCornerShape(14.dp))
                    .border(1.dp, AgentPalette.BubbleBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        // 页脚：右边是复制（回答那条同一个样式），左边是这一轮断在 App 没了时的标记
        if (interrupted || onCopy != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (interrupted) {
                    Text(
                        stringResource(R.string.agent_interrupted),
                        style = MaterialTheme.typography.labelSmall,
                        color = AgentPalette.Ink2,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                if (onCopy != null) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            stringResource(R.string.agent_copy_message),
                            Modifier.size(15.dp),
                            tint = AgentPalette.Ink2,
                        )
                    }
                }
            }
        }
    }
}

/** The assistant's answer: the running tool shows live, the finished ones fold, then the words. */
@Composable
private fun AssistantBlock(
    message: AgentMessage,
    streaming: Boolean = false,
    markdownState: MarkdownState? = null,
    onToolsClick: ((AgentMessage) -> Unit)? = null,
    onCopy: ((AgentMessage) -> Unit)? = null,
    subagents: List<AgentSubagent> = emptyList(),
    subagentRuns: Map<String, AgentSubagent> = emptyMap(),
    caseProgress: String? = null,
    onRunGame: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 思考过程：模型自己想的那一段，折起来放在最前面（玩家想看就点开）
        if (message.thinking.isNotBlank()) {
            ThinkingBlock(message.thinking, message.tokens, message.id)
        }
        // 子 Agent 的运行有自己的卡片：类型、任务、状态——折成一行就看不见了
        val subagentCards = message.tools.filter { it.subagent != null }
        val otherTools = message.tools.filter { it.subagent == null }
        subagentCards.forEach { card ->
            val call = card.subagent
            // 那一次运行：引擎按调用给的把手对上（续跑那次有自己的运行，不挂上一轮的结果）
            val run = subagentRuns[card.id]
                ?: subagents.firstOrNull { it.prompt != null && it.prompt == call?.prompt }
            AgentRunCard(
                card,
                message.notifications.firstOrNull { it.toolCallId == card.id },
                run,
                progress = caseProgress,
            )
        }
        if (otherTools.isNotEmpty()) {
            ToolSummary(otherTools, message.id, onToolsClick?.let { cb -> { cb(message) } })
        }
        if (message.text.isNotEmpty()) {
            if (streaming) {
                // 正在生成：先按纯文本显示——这一轮结束后才排版成 Markdown（省掉逐字重排）
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(color = AgentPalette.Ink),
                )
            } else if (markdownState != null) {
                // 已经解析好的：高度一次就对（不然解析完高度再变一次，滚动就跳）
                Markdown(
                    markdownState = markdownState,
                    colors = markdownColor(),
                    typography = agentMarkdownTypography(),
                )
            } else {
                Markdown(message.text, typography = agentMarkdownTypography())
            }
        }
        // 助手自己发的案例状态：那条提示挂在这一条回答的下面（助手没发就没有）
        message.caseStatus?.let { status ->
            CaseStatusCard(
                status = status,
                onRun = onRunGame,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 子 Agent 跑完的通知：一张卡片，不是那坨 XML
        message.notifications.forEach { notification ->
            AgentNotificationCard(notification)
        }
        // 这一轮断在 App 没了的时候：标出来（字写了一半的也标）
        if (message.pending) {
            Text(
                stringResource(R.string.agent_interrupted),
                style = MaterialTheme.typography.labelSmall,
                color = AgentPalette.Ink2,
            )
        }
        // 页脚：左边是复制，旁边是这一条回答自己花了多少生成量
        if (message.text.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onCopy != null) {
                    IconButton(onClick = { onCopy(message) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            stringResource(R.string.agent_copy),
                            Modifier.size(15.dp),
                            tint = AgentPalette.Ink2,
                        )
                    }
                }
                if (message.tokens > 0) {
                    Text(
                        stringResource(R.string.agent_tokens_count, formatTokens(message.tokens)),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = AgentPalette.Ink2,
                    )
                }
            }
        }
    }
}

/** The answers' voice: serif headings, 13sp body on 22 (the bubble's size), mono code. */
@Composable
private fun agentMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge.copy(
        fontFamily = AgentHeading, fontSize = 16.sp, lineHeight = 22.sp, color = AgentPalette.Ink,
    ),
    h2 = MaterialTheme.typography.titleMedium.copy(
        fontFamily = AgentHeading, fontSize = 15.sp, lineHeight = 21.sp, color = AgentPalette.Ink,
    ),
    h3 = MaterialTheme.typography.titleSmall.copy(
        fontFamily = AgentHeading, fontSize = 14.sp, lineHeight = 19.sp, color = AgentPalette.Ink,
    ),
    text = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp, lineHeight = 22.sp, color = AgentPalette.Ink,
    ),
    code = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AgentPalette.Ink,
    ),
)

/**
 * The fold: a mono row — the count left, the state right. With an [onOpen] the tap hands the turn
 * to the tool overlay (the panel's tool column); without one it expands the records in place.
 */
@Composable
private fun ToolSummary(
    tools: List<AgentToolCard>,
    messageId: Long,
    onOpen: (() -> Unit)? = null,
) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp, horizontal = 11.dp)
                .clickable { if (onOpen != null) onOpen() else expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tallies = tools.groupingBy { it.name }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key}×${it.value}" }
            val live = tools.any { it.state == AgentToolState.RUNNING }
            Text(
                stringResource(R.string.agent_tools_summary, tools.size) +
                    if (tallies.isNotBlank()) " · $tallies" else "",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    // 还在跑的时候那行字深一点：它在动，但不再重复一句「正在干活」
                    color = if (live) AgentPalette.Ink else AgentPalette.Ink2,
                ),
                modifier = Modifier.weight(1f),
            )
            // 右边永远只是「查看详情」：这行点了就是明细，跑不跑由抽屉下面那条动画说
            Text(
                stringResource(R.string.agent_state_ready),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.4.sp,
                    color = AgentPalette.Ink2,
                ),
            )
        }
        if (onOpen == null && expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                tools.forEach { card ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            card.name.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp,
                            ),
                            color = AgentPalette.Ink2,
                        )
                        if (card.argsSummary.isNotBlank()) {
                            Text(
                                card.argsSummary,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = AgentPalette.Cmd,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        card.resultSummary?.let { result ->
                            Text(
                                "→ $result",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = AgentPalette.Ink2,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AgentPalette.Hairline),
        )
    }
}

/**
 * The fold for the model's own thinking: a quiet row that opens into the reasoning.
 *
 * It sits above the tools because that is the order the model works in: it thinks, then it calls
 * tools, then it answers. Collapsed by default — the reasoning is long and the answer is what the
 * player came for; the fold is there for the times the reasoning is the point.
 *
 * The number is what the thinking cost: the tokens the model generated for this turn (the engine
 * counts the thinking and the answer together — it does not split them).
 */
@Composable
private fun ThinkingBlock(thinking: String, tokens: Long, messageId: Long) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "agent-thinking-arrow")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 11.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.agent_thinking_fold),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = AgentPalette.Ink2,
                modifier = Modifier.weight(1f),
            )
            if (tokens > 0) {
                Text(
                    stringResource(R.string.agent_thinking_tokens, formatTokens(tokens)),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = AgentPalette.Ink2,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                null,
                Modifier.size(16.dp).rotate(rotation),
                tint = AgentPalette.Ink2,
            )
        }
        AnimatedVisibility(expanded) {
            Text(
                thinking,
                style = MaterialTheme.typography.bodySmall.copy(color = AgentPalette.Ink2),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp)
                    .padding(bottom = 8.dp),
            )
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AgentPalette.Hairline),
        )
    }
}

@Composable
internal fun ToolCard(card: AgentToolCard) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "agent-tool-arrow")
    val (tint, stateWord) = when (card.state) {
        AgentToolState.RUNNING -> AgentPalette.Working to stringResource(R.string.agent_tool_running)
        AgentToolState.DONE -> AgentPalette.Ready to stringResource(R.string.agent_tool_done)
        AgentToolState.FAILED -> AgentPalette.Danger to stringResource(R.string.agent_tool_failed)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AgentPalette.Panel)
            .border(1.dp, AgentPalette.Hairline)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    card.resultSummary ?: stateWord,
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentPalette.Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    Modifier.size(18.dp).rotate(rotation),
                    tint = AgentPalette.Ink2,
                )
                StatusDot(tint, size = 8.dp)
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.agent_tool_args),
                        style = MaterialTheme.typography.labelSmall,
                        color = AgentPalette.Ink2,
                    )
                    Text(
                        card.argsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (!card.resultSummary.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.agent_tool_result),
                            style = MaterialTheme.typography.labelSmall,
                            color = AgentPalette.Ink2,
                        )
                        Text(
                            card.resultSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = AgentPalette.Ink2,
                        )
                    }
                }
            }
        }
    }
}

// ---- the input bar ----------------------------------------------------------------------------------------------

/** The bottom bar: the box grows up to four lines; the round button sends, or stops while a turn runs. */
@Composable
internal fun AgentInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    running: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val canSend = !running && text.isNotBlank()
    Row(
        Modifier.fillMaxWidth().imePadding().padding(bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            text,
            { onTextChange(it) },
            Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.agent_input_hint)) },
            maxLines = 4,
        )
        Box(
            Modifier
                .padding(bottom = 4.dp)
                .size(46.dp)
                .clip(RoundedCornerShape(50))
                .background(if (running || canSend) AgentPalette.Orange else AgentPalette.Panel)
                .clickable(enabled = running || canSend) {
                    if (running) onStop()
                    else onSend(text)   // the ViewModel clears the draft as the words land
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                stringResource(if (running) R.string.agent_stop else R.string.agent_send),
                Modifier.size(20.dp),
                tint = if (running || canSend) Color.White else AgentPalette.Idle,
            )
        }
    }
}
