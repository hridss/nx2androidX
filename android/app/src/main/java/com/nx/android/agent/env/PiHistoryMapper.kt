package com.nx.android.agent.env

import com.nx.android.agent.AgentMessage
import com.nx.android.agent.AgentNotification
import com.nx.android.agent.AgentRole
import com.nx.android.agent.AgentSubagentCallParser
import com.nx.android.agent.AgentToolCard
import com.nx.android.agent.AgentToolState
import org.json.JSONObject

/**
 * 引擎的消息 → 面板的消息：纯映射，JVM 测试直接喂 JSON。
 *
 * 引擎的一「轮」是 user 之后一串交替的 assistant/toolResult（带工具的轮次不止一条模型消息）；
 * 面板的一轮是两条消息：玩家的话 + 助手的回答（工具折叠在里面）。这里做的就是把那串交替
 * 归拢成面板的形状：
 *
 * - system 跳过（注入的引导，不渲染）
 * - thinking 收进这一轮的另一格（折起来的那一段：模型自己想的过程，不是回答）
 * - assistant 的 text 部件拼成回答正文，toolCall 部件开一张工具卡
 * - toolResult 按 toolCallId 对回卡片：isError 定成败，首段文字当结果摘要
 * - custom 的子 Agent 完成通知 → 一张通知卡片（别的 custom 消息照旧忽略）
 * - 每条 assistant 的 usage.output 累加 = 这一轮回答自己生成的量
 * - 玩家的话剥掉注入块（[本机状态]/[助手记忆]/[页面上下文]），只留「玩家说：」后面的部分
 * - 一轮没收尾（引擎没了：最后没有 stopReason 为 stop/aborted 的 assistant）→ 玩家那条标
 *   「已中断」；玩家按停止（aborted）是自己的收尾，不算中断
 */
object PiHistoryMapper {

    /** 一轮的两种收尾：答完（stop）与玩家按了停止（aborted）。 */
    private val TERMINAL = setOf("stop", "aborted")

    /** 注入块与玩家原话的分界。 */
    private const val PLAYER_SAYS = "player says:"

    fun map(messages: List<JSONObject>): List<AgentMessage> {
        val out = mutableListOf<AgentMessage>()
        var nextId = 1L

        // 正在归拢的一轮
        var question: String? = null
        var cards = mutableListOf<AgentToolCard>()
        var text = StringBuilder()
        var thinking = StringBuilder()
        var tokens = 0L
        var terminated = false
        val notices = mutableListOf<AgentNotification>()

        fun closeTurn() {
            val words = question ?: return
            out += AgentMessage(nextId++, AgentRole.USER, words, pending = !terminated)
            if (text.isNotEmpty() || cards.isNotEmpty() || notices.isNotEmpty() || thinking.isNotEmpty()) {
                out += AgentMessage(
                    nextId++, AgentRole.ASSISTANT, text.toString(),
                    tools = cards.toList(), thinking = thinking.toString(), tokens = tokens,
                    notifications = notices.toList(),
                )
            }
            cards = mutableListOf()
            text = StringBuilder()
            thinking = StringBuilder()
            tokens = 0L
            terminated = false
            notices.clear()
        }

        for (raw in messages) {
            when (raw.optString("role")) {
                "system" -> Unit

                "user" -> {
                    closeTurn()
                    question = playerWords(raw)
                }

                "assistant" -> {
                    if (question == null) continue   // 还没有玩家的话：会话的引导部分
                    val parts = raw.optJSONArray("content") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        when (p.optString("type")) {
                            "text" -> text.append(p.optString("text"))
                            // 思考过程：折起来的那一段（模型自己想的过程，不是回答）
                            "thinking" -> thinking.append(p.optString("thinking", p.optString("text")))
                            "toolCall" -> {
                                val call = AgentSubagentCallParser.of(
                                    p.optString("name"),
                                    p.optJSONObject("arguments"),
                                    p.optString("id"),
                                )
                                cards += AgentToolCard(
                                    id = p.optString("id"),
                                    name = p.optString("name"),
                                    argsSummary = if (call != null) {
                                        call.description.ifBlank { call.type }
                                    } else {
                                        p.optJSONObject("arguments")?.toString()?.take(60) ?: ""
                                    },
                                    state = AgentToolState.RUNNING,
                                    subagent = call,
                                )
                            }
                        }
                    }
                    raw.optJSONObject("usage")?.let { tokens += it.optLong("output", 0L) }
                    if (raw.optString("stopReason") in TERMINAL) terminated = true
                }

                // 子 Agent 跑完了：引擎把它完成的通知塞进对话，这一段是一张卡片
                "custom" -> {
                    val notice = noticeOf(raw) ?: continue
                    if (question == null) {
                        out += AgentMessage(
                            nextId++, AgentRole.ASSISTANT, "", notifications = listOf(notice),
                        )
                    } else {
                        notices.add(notice)
                    }
                }

                "toolResult" -> {
                    if (question == null) continue
                    val idx = cards.indexOfFirst { it.id == raw.optString("toolCallId") }
                    if (idx >= 0) {
                        cards[idx] = cards[idx].copy(
                            state = if (raw.optBoolean("isError")) AgentToolState.FAILED else AgentToolState.DONE,
                            resultSummary = firstText(raw)?.take(80),
                        )
                    }
                }
            }
        }
        closeTurn()
        return out
    }

    /** 玩家的话：拼接文本部件，再剥掉注入块。 */
    private fun playerWords(m: JSONObject): String {
        val parts = m.optJSONArray("content")
        val joined = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optString("type") == "text") joined.append(p.optString("text"))
            }
        } else if (m.optString("content").isNotBlank()) {
            joined.append(m.optString("content"))
        }
        val words = joined.toString()
        return if (words.contains(PLAYER_SAYS)) words.substringAfterLast(PLAYER_SAYS).trim() else words
    }

    /** 结果摘要：首个文本部件。 */
    private fun firstText(m: JSONObject): String? {
        val parts = m.optJSONArray("content")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optString("type") == "text") return p.optString("text")
            }
            return null
        }
        return m.optString("content").takeIf { it.isNotBlank() }
    }

    /**
     * 子 Agent 的完成通知：只有引擎自己发的那一种才算，其余的 custom 消息照旧忽略。
     */
    private fun noticeOf(m: JSONObject): AgentNotification? {
        if (m.optString("customType") != SubagentNotifications.MESSAGE_TYPE) return null
        val text = firstText(m) ?: m.optString("content")
        if (text.isBlank()) return null
        return SubagentNotifications.parse(text)
    }
}
