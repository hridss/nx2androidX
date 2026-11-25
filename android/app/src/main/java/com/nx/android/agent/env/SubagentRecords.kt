package com.nx.android.agent.env

import com.nx.android.agent.AgentSubagent
import java.io.File
import org.json.JSONObject

/**
 * The sub-agents a session spawned, read out of the session file.
 *
 * The engine's extension writes one entry per finished sub-agent into the parent session: a
 * `custom` entry, which never reaches the model (it is the extension's own state). That entry is
 * the list of runs — their outcomes, their timing, and the file each one's own conversation
 * lives in.
 *
 * A broken line is skipped: one dirty record must not take the whole list down.
 */
object SubagentRecords {

    /** The entry type the extension writes its records under. */
    const val ENTRY_TYPE = "subagents:record"

    /**
     * The records of one session file, oldest first.
     *
     * A record is a **run**, not an agent: the same agent id shows up once per run (the engine
     * reports a run when it ends). Two records of the same id with the same start are the same
     * run reported twice — the later one wins. Two with different starts are two runs and both
     * stay.
     */
    fun of(file: File): List<AgentSubagent> {
        if (!file.isFile) return emptyList()
        val byRun = LinkedHashMap<String, AgentSubagent>()
        runCatching {
            file.forEachLine { line ->
                record(line)?.let { byRun[it.runId] = it }
            }
        }
        return byRun.values.sortedBy { it.startedAt }
    }

    /** The record of one line, or null when the line is not a record (or does not parse). */
    fun record(line: String): AgentSubagent? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (json.optString("type") != "custom") return null
        if (json.optString("customType") != ENTRY_TYPE) return null
        val data = json.optJSONObject("data") ?: return null
        val id = data.optString("id").trim()
        if (id.isEmpty()) return null
        return AgentSubagent(
            id = id,
            type = data.optString("type").trim(),
            description = data.optString("description").trim(),
            status = data.optString("status").trim().ifEmpty { "completed" },
            result = data.textOrNull("result"),
            error = data.textOrNull("error"),
            startedAt = data.optLong("startedAt", 0L),
            completedAt = if (data.has("completedAt") && !data.isNull("completedAt")) {
                data.optLong("completedAt")
            } else {
                null
            },
            sessionFile = data.textOrNull("sessionFile"),
        )
    }
}

/** A string field, or null when it is missing, JSON null, or empty. */
internal fun JSONObject.textOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotEmpty() }
}
