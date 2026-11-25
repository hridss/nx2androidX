package com.nx.android.agent

import org.json.JSONObject

/**
 * The sub-agents the assistant spawned: what they were asked to do, how they went, and where
 * their own conversation lives.
 *
 * The engine's extension writes these into the session; the app only reads them.
 */
data class AgentSubagent(
    /** The sub-agent's own id (the extension's handle for that run). */
    val id: String,
    /** The kind of agent ("general-purpose"). */
    val type: String,
    /** The short task, in the model's own words. */
    val description: String,
    /** "running", "completed", "error", "aborted", "steered", "stopped". */
    val status: String,
    /** What it answered, when it answered. */
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
    val completedAt: Long? = null,
    /**
     * 这一次运行的标识：同一个 agent 跑第二次就是另一个标识。
     *
     * 引擎给的 id 是那个 agent 的，续跑时它不变——界面上要分清「哪一次」，不能只靠它。
     */
    val runId: String = "$id#$startedAt",
    /** The sub-agent's own session file: its whole conversation. */
    val sessionFile: String? = null,
    /** The task it was given, read out of its own conversation. */
    val prompt: String? = null,
    /** What it generated on its own: the tokens it spent. */
    val usage: AgentTokenUsage = AgentTokenUsage(),
    /** While it runs: the step it is at right now ("bash · log_triage.py"). */
    val step: String? = null,
    /** While it runs: how many tool calls it has made (the work it has done so far). */
    val toolUses: Int = 0,
) {
    /** It is still working. */
    val running: Boolean get() = status == "running"

    /** It stopped without an answer. */
    val failed: Boolean get() = status == "error" || status == "aborted"

    /** How long it took, in milliseconds (null while it still runs). */
    val durationMs: Long? get() = completedAt?.let { (it - startedAt).coerceAtLeast(0L) }
}

/**
 * The completion notice: a sub-agent is done, and here is what it says.
 *
 * The engine sends it into the conversation as a message of its own; the app turns it into a card.
 */
data class AgentNotification(
    /** The sub-agent's id (the notice names it). */
    val id: String,
    /** "Done", "Error: …", "Stopped". */
    val status: String,
    /** The one-line summary the engine wrote. */
    val summary: String,
    /** The answer, trimmed. */
    val result: String? = null,
    val totalTokens: Long = 0L,
    val toolUses: Long = 0L,
    val durationMs: Long = 0L,
    /** The tool call that spawned the sub-agent: the notice points back at it. */
    val toolCallId: String? = null,
)

/**
 * The sub-agent a tool call spawned: its type, the short task and the prompt it was given.
 *
 * The prompt is kept because it is the only exact link between a running sub-agent's transcript
 * and the call that started it.
 */
data class AgentSubagentCall(
    val type: String,
    val description: String,
    val prompt: String,
    /** The agent this call continues (the engine's `resume` argument); null when it starts a new one. */
    val resumeId: String? = null,
    /** The tool call's own id: the link between the card in the conversation and the run. */
    val toolCallId: String? = null,
)

/**
 * 切会话之前要不要问玩家一句：当前会话有正在跑的子任务时，要问。
 *
 * 子代理跟着会话活——切走会把正在跑的那些掐断（引擎那边不会为它们留下记录）。这个判断
 * 只有一条：有没有还在跑的。
 */
fun needsConfirmBeforeSwitch(runs: List<AgentSubagent>): Boolean = runs.any { it.running }

/** Reads the sub-agent out of a tool call's arguments. */
object AgentSubagentCallParser {

    /** The tool the assistant uses to spawn a sub-agent. */
    const val TOOL_NAME = "Agent"

    /** The call's own words, or null when this is not a sub-agent call. */
    fun of(name: String, arguments: JSONObject?, toolCallId: String? = null): AgentSubagentCall? {
        if (name != TOOL_NAME) return null
        val args = arguments ?: return null
        val type = args.optString("subagent_type").trim()
        val description = args.optString("description").trim()
        val prompt = args.optString("prompt").trim()
        val resume = args.optString("resume").trim().takeIf { it.isNotEmpty() }
        if (type.isEmpty() && description.isEmpty() && prompt.isEmpty()) return null
        return AgentSubagentCall(type, description, prompt, resume, toolCallId)
    }
}
