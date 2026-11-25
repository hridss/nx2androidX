package com.nx.android.agent.env

import com.nx.android.agent.AgentNotification

/**
 * The completion notice the sub-agent extension sends into the conversation.
 *
 * It arrives as a message of its own (a `custom` message), and its text is a small XML document
 * — the same shape Claude Code uses. The app reads that document into a card, so the player sees
 * what happened instead of the markup. A text that is not a notice is left alone: the caller then
 * shows it as it is.
 */
object SubagentNotifications {

    /** The message type the extension sends the notice under. */
    const val MESSAGE_TYPE = "subagent-notification"

    /** The notice, or null when the text is not one. */
    fun parse(text: String): AgentNotification? {
        val xml = text.trim()
        if (!xml.contains("<task-notification>")) return null
        val id = element(xml, "task-id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        val usage = element(xml, "usage")
        return AgentNotification(
            id = id,
            status = element(xml, "status")?.trim().orEmpty().ifEmpty { "Done" },
            summary = element(xml, "summary")?.trim().orEmpty(),
            result = element(xml, "result")?.trim()?.takeIf { it.isNotEmpty() },
            totalTokens = usage?.let { element(it, "total_tokens") }?.toLongOrNull() ?: 0L,
            toolUses = usage?.let { element(it, "tool_uses") }?.toLongOrNull() ?: 0L,
            durationMs = usage?.let { element(it, "duration_ms") }?.toLongOrNull() ?: 0L,
            toolCallId = element(xml, "tool-use-id")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** The text of one element, without its tags. */
    private fun element(xml: String, name: String): String? {
        val open = "<$name>"
        val close = "</$name>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = xml.indexOf(close, from)
        if (end < 0) return null
        return unescape(xml.substring(from, end))
    }

    /** The entities the engine escapes its text with. */
    private fun unescape(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i)
            if (end < 0 || end - i > 10) {
                out.append(c)
                i++
                continue
            }
            val entity = text.substring(i + 1, end)
            val replacement = when (entity) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                else -> numeric(entity)
            }
            if (replacement == null) {
                out.append(c)
                i++
            } else {
                out.append(replacement)
                i = end + 1
            }
        }
        return out.toString()
    }

    /** A numeric character reference, or null when it is not one. */
    private fun numeric(entity: String): String? {
        val code = when {
            entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
            else -> null
        } ?: return null
        if (code < 1 || code > 0x10FFFF) return null
        return String(Character.toChars(code))
    }
}
