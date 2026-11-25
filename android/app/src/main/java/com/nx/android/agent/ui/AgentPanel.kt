package com.nx.android.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nx.android.R
import com.nx.android.agent.AgentMessage
import com.nx.android.agent.AgentPresets
import com.nx.android.agent.AgentRole
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The drawer's frosted glass (磨砂): the page behind is blurred at 28dp and washed with the warm
 * white at 84% — the hero art bleeds through just enough to feel like glass, while ink text keeps a
 * ~12:1 contrast. Devices without backdrop blur (API < 31) get the solid ground instead.
 */
private val DrawerGlass = HazeBlurStyle {
    blurRadius(28.dp)
    backgroundColor(AgentPalette.Ground)
    colorEffects(listOf(HazeColorEffect.tint(AgentPalette.Ground.copy(alpha = 0.84f))))
    fallbackColorEffect(HazeColorEffect.tint(AgentPalette.Ground))
}

/** The drawer's width: the sheet is this wide, and it slides out of the right edge. */
private val DrawerWidth = 700.dp

/**
 * The drawer's host: the sheet itself stays in the tree and slides out of the screen when it
 * closes. Nothing is torn down — the scroll position, the laid-out markdown and the half-typed
 * words are all still there when it comes back.
 *
 * The scrim belongs to the host, not to the sheet: it dims the page behind and swallows the tap
 * that closes the drawer. It is only in the tree while the drawer is (or is on its way) open, so
 * a closed drawer never eats the page's touches.
 */
@Composable
fun AgentDrawerHost(
    open: Boolean,
    conversation: AgentConversationState,
    chips: List<Int>,
    haze: HazeState?,
    onCollapse: () -> Unit,
    onExpand: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /** 导出这局的攻关档案（只有游戏页给得起这个动作） */
    onExportGamespec: (() -> Unit)? = null,
    /** 修好了之后「去跑一下」：按 title ID 把那一局交到游戏页 */
    onRunGame: (String) -> Unit = {},
) {
    // 0 = 抽屉在原位，1 = 抽屉整个滑到屏幕右边之外
    val slide = remember { Animatable(1f) }
    var scrimUp by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            scrimUp = true
            slide.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        } else {
            slide.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            scrimUp = false
        }
    }
    val widthPx = with(LocalDensity.current) { DrawerWidth.toPx() }
    Box(Modifier.fillMaxSize()) {
        if (scrimUp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - slide.value }
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.34f to Color(0x75141416),
                            1f to Color(0xF5141416),
                        ),
                    )
                    .clickable { onCollapse() }
            )
        }
        // the sheet: it sits on the right edge and slides out of the screen when it closes
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            AgentPanel(
                conversation = conversation,
                chips = chips,
                haze = haze,
                open = open,
                onCollapse = onCollapse,
                onExpand = onExpand,
                onOpenSettings = onOpenSettings,
                onExportGamespec = onExportGamespec,
                onRunGame = onRunGame,
                modifier = Modifier.offset { IntOffset((slide.value * widthPx).roundToInt(), 0) },
            )
        }
    }
}

/**
 * The assistant's side drawer, phone-landscape (ADR-009, the mobile draft) — the app's own glass
 * language: two columns (conversation 460dp, tools 240dp) over a frosted ground, the dimmed
 * home hero showing through on the left. The conversation has no header; the tool column carries
 * the head (title + context occupancy), the model chip, quick asks, the composer, and the session
 * switcher beside the send button at the very bottom. The tool-call overlay covers the tool column.
 */
@Composable
fun AgentPanel(
    conversation: AgentConversationState,
    chips: List<Int>,
    haze: HazeState?,
    open: Boolean,
    onCollapse: () -> Unit,
    onExpand: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /** 导出这局的攻关档案（只有游戏页给得起这个动作） */
    onExportGamespec: (() -> Unit)? = null,
    /** 修好了之后「去跑一下」：按 title ID 把那一局交到游戏页 */
    onRunGame: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by conversation.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var toolsOverlay by remember { mutableStateOf<AgentMessage?>(null) }
    // the sub-agents overlay: the list of this chat's runs, and the one that is open
    var agentsOpen by remember { mutableStateOf(false) }
    // 「贡献上游」的二次确认：导出前先问一句——玩家要拿这个包去上游提 issue
    var exportConfirm by remember { mutableStateOf(false) }
    // 这一局的用量：点头部那个占用数字才弹出来
    var usageOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val copiedWord = stringResource(R.string.agent_copied)
    val copyLabel = stringResource(R.string.agent_title)
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    val last = state.messages.lastOrNull()
    // 当前会话的名字：引擎报的那个优先，没有就用列表里那一条的标题
    val activeTitle = state.activeTitle.ifBlank {
        state.sessions.firstOrNull { it.path == state.activePath }?.title
            ?: stringResource(R.string.agent_action_new_session)
    }
    // the first open lands at the conversation's true bottom; every open after it keeps the place
    // the player left (the drawer is never rebuilt, so there is nothing to restore)
    var opened by remember { mutableStateOf(false) }
    var followBottom by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        // the models are read again: the settings page may have changed them while this was closed
        conversation.refreshModels()
        // 历史第一次打开就读：引擎起进程那零点几秒由加载态盖住
        conversation.ensureHistory()
    }
    // 首开落在真底。这个开关只有一次机会，所以别在消息还没到的时候把它烧掉：
    // 消息到了（哪怕抽屉这会儿正开着）就补上这一跟。
    LaunchedEffect(open, state.messages) {
        if (!shouldFollowToBottom(open, opened, state.messages.size)) return@LaunchedEffect
        opened = true
        followBottom = true
    }
    // 攻关面板开着的时候清单跟着走：还在跑的子 Agent 会自己长大
    LaunchedEffect(agentsOpen) {
        if (!agentsOpen) return@LaunchedEffect
        while (agentsOpen) {
            conversation.refreshSubagents()
            delay(1200)
        }
    }
    // markdown 提前在后台解析好：条目进屏时高度一次就对，滚动不再等解析
    val mdStates by conversation.markdownCache.ready.collectAsStateWithLifecycle()
    // 跟着底部走：markdown 是一条条排出来的，跳过去那一刻高度还不是最终值，
    // 所以布局每变一次就再按一次底部，等它稳了（或者玩家自己上手了）就收手。
    // 这里不再等「最后那条排好版」才开跟：排版慢了就多跟几轮，排版失败了就照常落底——
    // 那个门槛一旦卡住（解析不出来的回答），首开就永远落不了底。
    FollowBottom(listState, follow = followBottom, onStop = { followBottom = false })
    // while it streams: stick to the bottom, but only while the player hasn't scrolled up to read
    val pinned = rememberPinnedToBottom(listState)
    // 子 Agent 的状态变了也带到底部：它跑完了、又开始续跑——玩家贴着底就该看见
    val subagentMark = state.subagents.joinToString(",") { "${it.runId}:${it.status}" }
    LaunchedEffect(state.messages.size, last?.text?.length, last?.tools?.size, state.running, subagentMark) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!pinned.value) return@LaunchedEffect   // 玩家自己上去了：不跟他抢
        listState.scrollToBottom()
    }
    // markdown 解析落地会改高度：还贴着底就再按一次底（玩家翻上去了就不动）
    LaunchedEffect(mdStates) {
        if (!pinned.value) return@LaunchedEffect
        listState.scrollToBottom()
    }

    // 复制：把这条回答的原始 Markdown 放上剪贴板（围栏与缩进都在）
    val onCopy: (AgentMessage) -> Unit = { message ->
        clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, copyPayloadOf(message)))
        Toast.makeText(context, copiedWord, Toast.LENGTH_SHORT).show()
    }

    // the light sheet: everything inside resolves ink-on-white, whatever the app's dark theme says
    MaterialTheme(colorScheme = AgentLightScheme, typography = AgentTypography) {
    Box(
        modifier
            .fillMaxHeight()
            .width(DrawerWidth)
            .then(
                // the frosted sheet where there is something to frost (the page behind);
                // a solid ground where there is not (the game's surface)
                if (haze != null) Modifier.hazeBlur(input = HazeInput.Sources(haze), style = DrawerGlass)
                else Modifier.background(AgentPalette.Ground)
            )
            .statusBarsPadding()
            .imePadding()
            // consume taps: the drawer is not the scrim (a tap in the flow used to close it)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
    Row(Modifier.fillMaxSize()) {
            // ---- the conversation: no header, the whole height -------------------------------------------
            Column(
                Modifier.weight(1f).fillMaxHeight(),
            ) {
                state.error?.let { reason -> ErrorStrip(reason) { conversation.dismissError() } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.messages.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                HistoryStatus(state.loading, state.engineMissing, onOpenSettings)
                                if (!state.loading && !state.engineMissing) {
                                    Text(
                                        stringResource(R.string.agent_empty_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AgentPalette.Ink2,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(state.messages, key = { it.id }) { message ->
                        MessageRow(
                            message,
                            streaming = message.id == state.streamingId,
                            markdownState = mdStates[message.id],
                            onToolsClick = { toolsOverlay = it },
                            onCopy = onCopy,
                            subagents = state.subagents,
                            subagentRuns = state.subagentRuns,
                            caseProgress = state.caseProgress,
                            onRunGame = onRunGame,
                        )
                    }
                }
                if (state.running) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 那个点一直在呼吸：助手还在环境里忙着
                        val pulse by rememberInfiniteTransition(label = "agent-pulse").animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(700, easing = FastOutSlowInEasing),
                                RepeatMode.Reverse,
                            ),
                            label = "alpha",
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AgentPalette.Working.copy(alpha = pulse)),
                        )
                        Text(
                            stringResource(R.string.agent_state_working),
                            style = MaterialTheme.typography.labelSmall,
                            color = AgentPalette.Ink2,
                        )
                        WorkingDots(AgentPalette.Working)
                    }
                }
            }
            // ---- the tool column, covered by the tool-call overlay when open -------------------------------
            Box(Modifier.width(240.dp).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 0.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // the head: the session's title (truncated) and its context occupancy (opencode style)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            activeTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AgentPalette.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ContextRing(
                            state.usageTokens,
                            state.usageWindow,
                            ringSize = 14.dp,
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            // 引擎没就绪不给假数字：窗口还不知道就先省略号
                            if (state.usageWindow > 0) {
                                formatTokens(state.usageTokens) + usagePct(state.usageTokens, state.usageWindow)
                            } else {
                                "…"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            // 它能点：颜色与下划线都告诉玩家这一点（点开看这一局的用量）
                            color = AgentPalette.Accent,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable {
                                    usageOpen = true
                                    conversation.refreshUsage()
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    // the model chip: what answers now; the menu lists the models the player configured
                    var modelMenu by remember { mutableStateOf(false) }
                    // the menu hangs under the chip: the popup's parent is this box, so it opens
                    // where the chip is and not at the top of the column
                    Box(Modifier.fillMaxWidth()) {
                        GlassChip(
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            onClick = { modelMenu = !modelMenu },
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.configured) AgentPalette.Ready else AgentPalette.Idle),
                            )
                            Text(
                                state.profiles.firstOrNull { it.id == state.activeProfileId }?.modelName
                                    .orEmpty()
                                    .ifBlank { stringResource(R.string.agent_model_unset) },
                                style = MaterialTheme.typography.labelMedium,
                                color = AgentPalette.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                null,
                                // the arrow turns when the menu is open
                                Modifier.size(13.dp).rotate(if (modelMenu) 180f else 0f),
                                tint = AgentPalette.Ink2,
                            )
                        }
                        GlassMenu(
                            expanded = modelMenu,
                            onDismiss = { modelMenu = false },
                            // a Popup has no parent width: the chip's own width, so the menu's right
                            // edge lines up with the chip's
                            modifier = Modifier.width(220.dp),
                            alignment = Alignment.TopStart,
                            // below the chip, with a finger's gap
                            offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }),
                        ) {
                            if (state.profiles.isEmpty()) {
                                GlassMenuRow(
                                    stringResource(R.string.agent_model_unset),
                                    ink = AgentPalette.Ink2,
                                    onClick = { modelMenu = false },
                                )
                            }
                            state.profiles.forEach { profile ->
                                GlassMenuRow(
                                    // one name per row: the player's own name first, the model name
                                    // when they gave none. A long name is cut, not squeezed.
                                    profile.label.ifBlank { profile.modelName },
                                    onClick = {
                                        modelMenu = false
                                        conversation.selectProfile(profile.id)
                                    },
                                )
                            }
                            HorizontalDivider(color = AgentPalette.Hairline)
                            GlassMenuRow(
                                stringResource(R.string.agent_menu_settings),
                                icon = Icons.Filled.Settings,
                                ink = AgentPalette.Ink2,
                                onClick = {
                                    modelMenu = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                    // the sub-agents entry: this chat's runs, and the ones still going
                    GlassChip(
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        onClick = { agentsOpen = true },
                    ) {
                        Icon(
                            Icons.Filled.AccountTree,
                            null,
                            Modifier.size(13.dp),
                            tint = AgentPalette.Ink2,
                        )
                        Text(
                            stringResource(R.string.agent_subagents_entry),
                            style = MaterialTheme.typography.labelMedium,
                            color = AgentPalette.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.subagents.isNotEmpty()) {
                            Text(
                                state.subagents.size.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = AgentPalette.Ink2,
                            )
                        }
                        // 有活儿在跑：那盏灯亮着
                        if (state.subagents.any { it.running }) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(AgentPalette.Working),
                            )
                        }
                    }
                    // 贡献上游：把这局的档案打成 zip 交给系统分享，玩家拿去上游提 issue
                    if (onExportGamespec != null) {
                        GlassChip(
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            onClick = { exportConfirm = true },
                        ) {
                            Icon(
                                Icons.Filled.IosShare,
                                null,
                                Modifier.size(13.dp),
                                tint = AgentPalette.Ink2,
                            )
                            Text(
                                stringResource(R.string.agent_gamespec_export),
                                style = MaterialTheme.typography.labelMedium,
                                color = AgentPalette.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // quick asks: they follow the page context and stay while the agent works.
                    // 只有还没开聊的新会话才给它们——开了聊就不打扰了。
                    if (state.messages.isEmpty()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.agent_label_quick_ops),
                                style = MaterialTheme.typography.labelSmall,
                                color = AgentPalette.Ink2,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            chips.forEach { res ->
                                val words = stringResource(res)
                                QuickCard(words) { conversation.send(words) }
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // the composer: the input alone, then the session switcher beside the send
                    OutlinedTextField(
                        state.draft,
                        conversation::updateDraft,
                        Modifier.fillMaxWidth().height(60.dp),
                        maxLines = 2,
                        placeholder = { Text(stringResource(R.string.agent_input_hint), style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AgentPalette.Ink),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AgentPalette.Hairline,
                            unfocusedBorderColor = AgentPalette.Hairline,
                            focusedContainerColor = AgentPalette.Panel,
                            unfocusedContainerColor = AgentPalette.Panel,
                            focusedTextColor = AgentPalette.Ink,
                            unfocusedTextColor = AgentPalette.Ink,
                            cursorColor = AgentPalette.Orange,
                            focusedPlaceholderColor = AgentPalette.Idle,
                            unfocusedPlaceholderColor = AgentPalette.Idle,
                        ),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        var sessionMenu by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(AgentPalette.Panel, RoundedCornerShape(12.dp))
                                    .border(1.dp, AgentPalette.Hairline, RoundedCornerShape(12.dp))
                                    .clickable { sessionMenu = true }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Icon(
                                    Icons.Filled.AddComment,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = AgentPalette.Ink2,
                                )
                                Text(
                                    activeTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AgentPalette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = AgentPalette.Ink2,
                                )
                            }
                            GlassMenu(
                                expanded = sessionMenu,
                                onDismiss = { sessionMenu = false },
                                alignment = Alignment.BottomEnd,
                                offset = IntOffset(0, with(LocalDensity.current) { -(52.dp.roundToPx()) }),
                                modifier = Modifier.width(292.dp),
                            ) {
                                state.sessions.take(6).forEach { meta ->
                                    GlassMenuRow(
                                        meta.title,
                                        ink = if (meta.path == state.activePath) AgentPalette.Quick else AgentPalette.Ink,
                                        onClick = {
                                            sessionMenu = false
                                            conversation.requestSwitch(meta.path)
                                        },
                                    )
                                }
                                HorizontalDivider(color = AgentPalette.Hairline)
                                GlassMenuRow(
                                    stringResource(R.string.agent_action_new_session),
                                    bold = true,
                                    onClick = {
                                        sessionMenu = false
                                        conversation.requestSwitch(null)
                                    },
                                )
                            }
                        }
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.running || state.draft.isNotBlank()) AgentPalette.Orange else AgentPalette.Panel,
                                )
                                .clickable(enabled = state.running || state.draft.isNotBlank()) {
                                    if (state.running) conversation.stop() else conversation.send(state.draft)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                stringResource(if (state.running) R.string.agent_stop else R.string.agent_send),
                                Modifier.size(20.dp),
                                tint = if (state.running || state.draft.isNotBlank()) Color.White else AgentPalette.Idle,
                            )
                        }
                    }
                }
                // the tool-call overlay: covers the tool column edge-to-edge, closes with the X
                toolsOverlay?.let { msg ->
                    ToolOverlay(
                        message = msg,
                        onDismiss = { toolsOverlay = null },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        // the sub-agents overlay: the list of this chat's runs, and the one that is open
        if (agentsOpen) {
            AgentsOverlay(
                subagents = state.subagents,
                running = state.subagents.count { it.running },
                loadConversation = { subagent -> conversation.subagentConversation(subagent) },
                onDismiss = { agentsOpen = false },
                modifier = Modifier.matchParentSize(),
            )
        }
        // 这一局的用量：上下文占用、主会话累计、子代理累计
        if (usageOpen) {
            AgentUsageDialog(
                contextTokens = state.usageTokens,
                contextWindow = state.usageWindow,
                session = state.sessionUsage,
                subagents = state.subagentUsage,
                subagentRuns = state.subagents.size,
                onDismiss = { usageOpen = false },
            )
        }
        // 切会话前的二次确认：有子代理在跑时，玩家先看一眼代价
        AgentSwitchConfirmDialog(
            ask = state.switchAsk,
            onConfirm = { conversation.confirmSwitch() },
            onDismiss = { conversation.dismissSwitch() },
        )
        // 「贡献上游」的二次确认：包里是这一局的档案，玩家要拿着它去上游提 issue
        if (exportConfirm) {
            AlertDialog(
                onDismissRequest = { exportConfirm = false },
                title = {
                    Text(
                        stringResource(R.string.agent_gamespec_export),
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.agent_gamespec_confirm_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        exportConfirm = false
                        onExportGamespec?.invoke()
                    }) {
                        Text(stringResource(R.string.common_export))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { exportConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
    } // MaterialTheme(agent light)
}
/** The tool-call overlay: the tool column's whole face — title, close, the records below. */
@Composable
private fun ToolOverlay(message: AgentMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // consume taps: the overlay covers the tool column, so a tap on its blank face must not
    // reach the chips underneath (the model chip answered the player through it)
    Column(
        modifier
            .background(AgentPalette.DrawerBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.agent_tool_overlay_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AgentPalette.Ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.agent_close),
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(4.dp),
                tint = AgentPalette.Ink2,
            )
        }
        HorizontalDivider(color = AgentPalette.Hairline)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message.tools.reversed().forEach { card ->   // 最后调用的在最上面：正在跑的那个
                Column(Modifier.fillMaxWidth()) {
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
            if (message.tools.isEmpty()) {
                Text(
                    stringResource(R.string.agent_tool_overlay_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentPalette.Ink2,
                )
            }
        }
    }
}

/** A glass chip: the base card at pill size with the white cap and the hairline. */
@Composable
private fun GlassChip(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    onClick: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier
            .background(AgentPalette.Panel, RoundedCornerShape(50))
            .border(1.dp, AgentPalette.Hairline, RoundedCornerShape(50))
            .padding(contentPadding)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

/** The context ring: the session's occupancy of the model's window. Tiny in the head, big standalone. */
@Composable
private fun ContextRing(
    context: Long,
    window: Long,
    modifier: Modifier = Modifier,
    ringSize: androidx.compose.ui.unit.Dp = 120.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val pct = if (window > 0) (context * 100.0 / window).coerceIn(0.0, 100.0) else 0.0
    val color = when {
        pct >= 85 -> AgentPalette.Danger
        pct >= 50 -> AgentPalette.Working
        else -> AgentPalette.Ready
    }
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            drawArc(
                AgentPalette.Hairline.copy(alpha = 0.6f),
                -90f, 360f, false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (pct > 0) {
                drawArc(
                    color,
                    -90f, (pct * 3.6).toFloat(), false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        if (ringSize >= 64.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatTokens(context),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AgentPalette.Ink,
                )
                Text(
                    "${pct.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AgentPalette.Ink2,
                )
            }
        }
    }
}

/**
 * A glass dropdown: the M3 menu's shape is square-ish and its surface can't take the white cap —
 * this popup carries the full glass recipe at a 20dp radius. [alignment]/[offset] pick where it
 * opens (the session menu rises from the bottom row; the model menu drops from its chip).
 */
@Composable
private fun GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .background(AgentPalette.Panel, RoundedCornerShape(16.dp))
                .border(1.dp, AgentPalette.Hairline, RoundedCornerShape(16.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/**
 * A row in the glass menu: a name, an optional icon, and a tap that answers.
 *
 * The Material dropdown row is not used here: it brings its own height and padding, and in a
 * glass popup it reads like a different app. This one fills the menu's width, keeps the name on
 * one line (cut, not squeezed) and takes the popup's own radius.
 */
@Composable
private fun GlassMenuRow(
    text: String,
    icon: ImageVector? = null,
    ink: Color = AgentPalette.Ink,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = AgentPalette.Ink2)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.let {
                if (bold) it.copy(fontWeight = FontWeight.SemiBold) else it
            },
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 三个循环的点：助手还在环境里忙的时候，它一路走——像有人在敲键盘。
 */
@Composable
private fun WorkingDots(color: Color) {
    val phase by rememberInfiniteTransition(label = "agent-dots").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            // 走得最远的那颗最亮
            val near = 1f - kotlin.math.abs(phase - (i + 1)).coerceIn(0f, 2f)
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.25f + 0.75f * near)),
            )
        }
    }
}

/** A quick ask: a glass card, the words coming from the page context. */
@Composable
private fun QuickCard(label: String, onClick: () -> Unit) {    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = AgentPalette.Quick,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(AgentPalette.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, AgentPalette.Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** opencode style: 642.2k above a thousand gets one decimal and a k. */
internal fun formatTokens(tokens: Long): String =
    if (tokens < 1_000) "$tokens"
    else "%.1fk".format(tokens / 1_000.0)

/** The occupancy percent — empty when the window is unknown. */
internal fun usagePct(context: Long, window: Long): String =
    if (window > 0 && context > 0) "(${(context * 100 / window).coerceAtMost(999)}%)" else ""

/**
 * The true bottom: snap to the last item, then keep going until the list cannot scroll any more.
 *
 * The last item's own height is not the end of the content — the bottom padding (the留白 below the
 * last message) is, and that is where the copy button lives. Stopping at the item's edge cuts it
 * off, so we scroll the rest of the way.
 */
internal suspend fun LazyListState.scrollToBottom() {
    val n = layoutInfo.totalItemsCount
    if (n == 0) return
    scrollToItem(n - 1)
    // 滚到不能再滚为止：scrollBy 自己会夹在合法范围内
    repeat(4) {
        if (!canScrollForward) return@repeat
        scrollBy(100_000f)
    }
}

/**
 * 贴底的开关：正文一长就把玩家按回底部——但玩家自己往上拖了就不跟了，拖回底部才恢复。
 *
 * 判据是"一次滚动停下来时玩家停在哪儿"：贴着底就继续跟，拖上去了就放他走。
 */
@Composable
internal fun rememberPinnedToBottom(listState: LazyListState): MutableState<Boolean> {
    val pinned = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var scrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { now ->
            if (scrolling && !now) pinned.value = !listState.canScrollForward
            scrolling = now
        }
    }
    return pinned
}

/**
 * 首开要不要跟到底：抽屉开着、还没跟过、有消息——三个条件都满足才跟。
 *
 * 单独拿出来是因为这个判断只有一次机会：早一步把它用掉（消息还没到就标记「跟过了」），
 * 首开就永远落不了底，而那正是玩家第一次看到这段对话的时刻。
 */
internal fun shouldFollowToBottom(open: Boolean, followed: Boolean, messageCount: Int): Boolean =
    open && !followed && messageCount > 0

/**
 * 跟着底部走：markdown 是一条条排版出来的，跳过去那一刻最后一项的高度还不是最终值——
 * 一次滚动落在消息中间。布局每变一次就再按一次底部，等它稳了（或者玩家自己上手了）就收手。
 */
@Composable
internal fun FollowBottom(
    listState: LazyListState,
    follow: Boolean,
    onStop: () -> Unit,
) {
    LaunchedEffect(listState, follow) {
        if (!follow) return@LaunchedEffect
        // 玩家一拖就收手：阅读的位置还给玩家
        val player = launch {
            listState.interactionSource.interactions
                .filterIsInstance<DragInteraction.Start>()
                .collect { onStop() }
        }
        try {
            var last = -1
            var stable = 0
            var rounds = 0
            // 最多跟三秒：排版再慢也不至于更久
            while (rounds < 25 && stable < 1) {
                rounds++
                listState.scrollToBottom()
                delay(120)
                val size = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: -1
                // 只有"到底了"且高度不再变，才算落定：内容还在下面长着就继续跟
                val atEnd = !listState.canScrollForward
                stable = if (size == last && atEnd) stable + 1 else 0
                last = size
            }
        } finally {
            player.cancel()
            onStop()
        }
    }
}
