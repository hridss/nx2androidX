package com.nx.android.agent

/**
 * The conversation's own state machine: the messages the player sees, the turn that runs, the
 * failure that stopped it. Plain Kotlin — no Android, no Compose: the drawer, the chat page and
 * the tests all walk the same steps.
 */
class AgentConversation {

    /** The messages as the player sees them (the engine keeps its own copy for the context). */
    @Volatile
    var messages: List<AgentMessage> = emptyList()
        private set

    /** A turn is running. */
    @Volatile
    var running: Boolean = false
        private set

    /** The failure that stopped the last turn (null when the last turn was fine). */
    @Volatile
    var failure: AgentFailure? = null
        private set

    /** The message the running turn writes into; null when nothing runs. */
    @Volatile
    var streamingId: Long? = null
        private set

    /** The ids are this list's own: they start above whatever the engine brought along. */
    private var nextId: Long = 1L

    /** The assistant message the running turn's events land in. */
    private var draftId: Long? = null

    /** 轮外那一段（主 Agent 自己写下的话）落在哪条消息里。 */
    private var followUpId: Long? = null

    /** Another conversation answers from here on: the history is the new one. */
    fun load(history: List<AgentMessage>) {
        messages = history.map { withCaseStatus(it) }
        nextId = (history.maxOfOrNull { it.id } ?: 0L) + 1L
        running = false
        streamingId = null
        draftId = null
        followUpId = null
    }

    /** The player's words land at once. */
    fun say(text: String) {
        messages = messages + AgentMessage(nextId++, AgentRole.USER, text)
    }

    /** The turn starts: the answer is the message the events write into. */
    fun begin() {
        running = true
        failure = null
    }

    /** One event of the running turn. */
    fun apply(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> appendText(event.text)
            is AgentEvent.ThinkingDelta -> appendThinking(event.text)
            is AgentEvent.ToolStarted -> appendTool(event.card)
            is AgentEvent.ToolFinished -> finishTool(event.id, event.state, event.resultSummary)
            is AgentEvent.TurnDone -> finishTurn(event.assistantText, event.tokens)
            is AgentEvent.Failed -> fail(event.reason)
            is AgentEvent.Notification -> addNotification(event.notification)
            // 上下文占用不是对话的事：那是持有者那边的账
            is AgentEvent.UsageUpdated -> Unit
            // 轮外的那一段自己走 followUp
            is AgentEvent.FollowUp -> Unit
        }
    }

    /**
     * 轮外的一个事件：主 Agent 在没有玩家提问的时候写下的那段下文。
     *
     * 它落在自己的消息里（不混进上一轮的回答）——那段话是子 Agent 跑完之后才有的，
     * 玩家看到的是一条新的消息。
     */
    fun followUp(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                if (event.text.isEmpty()) return
                val id = followUpDraft().id
                update(id) { it.copy(text = it.text + event.text) }
            }
            is AgentEvent.Notification -> {
                val id = followUpDraft().id
                update(id) { it.copy(notifications = it.notifications + event.notification) }
            }
            is AgentEvent.FollowUp -> {
                val id = followUpId
                if (id != null) {
                    // 已经流出来的字留着；没流出来（App 刚起来）就用这一份完整的
                    update(id) { m ->
                        withCaseStatus(
                            m.copy(text = if (m.text.isBlank()) event.text else m.text, tokens = event.tokens)
                        )
                    }
                } else if (event.text.isNotBlank()) {
                    messages = messages + withCaseStatus(
                        AgentMessage(nextId++, AgentRole.ASSISTANT, event.text, tokens = event.tokens),
                    )
                }
                followUpId = null
            }
            else -> Unit
        }
    }

    /** 轮外那一段的草稿：它的第一条消息。 */
    private fun followUpDraft(): AgentMessage {
        val id = followUpId
        val last = messages.lastOrNull()
        if (id != null && last?.id == id) return last
        val made = AgentMessage(nextId++, AgentRole.ASSISTANT, "")
        messages = messages + made
        followUpId = made.id
        return made
    }

    /** The streamed words join the answer being written. */
    fun appendText(text: String) {
        if (text.isEmpty()) return
        // 轮次已经结束就不该再补字：那样会凭空多出一条"幽灵回答"
        if (!running && draftId == null) return
        val id = draft().id
        update(id) { it.copy(text = it.text + text) }
    }

    /** 思考过程也落在这一轮的回答里：折起来的那一段。 */
    fun appendThinking(text: String) {
        if (text.isEmpty()) return
        if (!running && draftId == null) return
        val id = draft().id
        update(id) { it.copy(thinking = it.thinking + text) }
    }

    /** The turn is over — the stream ended, or the player stopped it. */
    fun end() {
        running = false
        streamingId = null
        draftId = null
    }

    /** The strip's way out: the next turn replaces the words anyway. */
    fun dismissFailure() {
        failure = null
    }

    private fun appendTool(card: AgentToolCard) {
        val id = draft().id
        update(id) { it.copy(tools = it.tools + card) }
    }

    private fun finishTool(id: String, state: AgentToolState, result: String) {
        updateLastAssistant { m ->
            m.copy(tools = m.tools.map { card ->
                if (card.id == id) card.copy(state = state, resultSummary = result) else card
            })
        }
    }

    /** The turn's answer lands as a new assistant message (or it replaces the streamed one). */
    private fun finishTurn(text: String, tokens: Long) {
        if (text.isNotEmpty()) {
            val last = messages.lastOrNull()
            if (last?.role == AgentRole.ASSISTANT) {
                update(last.id) { withCaseStatus(it.copy(text = text, tokens = tokens)) }
            } else {
                messages = messages + withCaseStatus(
                    AgentMessage(nextId++, AgentRole.ASSISTANT, text, tokens = tokens)
                )
            }
        }
        end()
    }

    /**
     * 助手自己发的案例状态：正文里那个块剥掉，状态挂在消息上。
     *
     * 只有助手的话里才有这个块——玩家的消息不动，助手的消息没有这个块就原样留着。
     */
    private fun withCaseStatus(message: AgentMessage): AgentMessage {
        if (message.role != AgentRole.ASSISTANT || message.text.isBlank()) return message
        val (text, status) = CaseStatusFormat.extract(message.text)
        return message.copy(text = text, caseStatus = message.caseStatus ?: status)
    }

    private fun fail(reason: AgentFailure) {
        failure = reason
        dropEmptyDraft()
        running = false
    }

    /**
     * A sub-agent finished: its notice joins the answer it belongs to.
     *
     * The notice arrives while the answer is being written, so it lands on that answer. When the
     * turn is already over, it becomes its own little answer — the notice is worth seeing either
     * way.
     */
    private fun addNotification(notification: AgentNotification) {
        val last = messages.lastOrNull()
        if (last != null && last.role == AgentRole.ASSISTANT) {
            update(last.id) { it.copy(notifications = it.notifications + notification) }
            return
        }
        messages = messages + AgentMessage(
            nextId++, AgentRole.ASSISTANT, "", notifications = listOf(notification),
        )
    }

    /** The assistant message the events of the running turn land in; the turn's first event makes it. */
    private fun draft(): AgentMessage {
        val id = draftId
        val last = messages.lastOrNull()
        if (id != null && last?.id == id) return last
        val made = AgentMessage(nextId++, AgentRole.ASSISTANT, "")
        messages = messages + made
        draftId = made.id
        streamingId = made.id
        return made
    }

    /** A turn that failed before anything arrived leaves an empty shell behind: take it out. */
    private fun dropEmptyDraft() {
        val id = draftId
        val last = messages.lastOrNull()
        val empty = last != null && last.text.isEmpty() && last.tools.isEmpty() &&
            last.notifications.isEmpty() && last.thinking.isEmpty()
        if (id != null && last?.id == id && empty) {
            messages = messages.dropLast(1)
        }
        draftId = null
        streamingId = null
    }

    private fun update(id: Long, change: (AgentMessage) -> AgentMessage) {
        messages = messages.map { if (it.id == id) change(it) else it }
    }

    private fun updateLastAssistant(change: (AgentMessage) -> AgentMessage) {
        val last = messages.lastOrNull() ?: return
        if (last.role != AgentRole.ASSISTANT) return
        update(last.id, change)
    }
}
