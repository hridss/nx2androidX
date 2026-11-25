package com.nx.android.agent

/**
 * The text buffer: the deltas pile up here and leave in one batch every [intervalMs].
 *
 * Streaming word by word re-lays the whole answer on every single token — a long answer gets
 * slower the longer it gets. Batching keeps the pace readable (ten refreshes a second is enough
 * to look continuous) and the answer's layout still settles when the turn ends.
 */
class AgentTextBatch(
    private val intervalMs: Long = 100L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val buffer = StringBuilder()
    private var lastFlush = clock()

    /** One delta arrives: it joins the buffer, and the batch is due when enough time passed. */
    fun add(text: String): Boolean {
        buffer.append(text)
        return due()
    }

    /** Is the batch due (enough time since the last flush)? */
    fun due(): Boolean = clock() - lastFlush >= intervalMs

    /** The batch leaves: the buffered text goes out, the buffer starts over. */
    fun take(): String {
        val text = buffer.toString()
        buffer.clear()
        lastFlush = clock()
        return text
    }
}
