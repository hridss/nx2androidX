package com.nx.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delta buffer: the words wait in it and leave in one batch, so the answer is re-laid ten
 * times a second instead of once per token. Plain Kotlin, JVM-fast.
 */
class AgentTextBatchTest {

    private var now = 0L

    private fun batch(interval: Long = 100L) = AgentTextBatch(interval) { now }

    @Test
    fun `the batch is not due before the interval passed`() {
        val b = batch()
        now = 0L
        assertFalse(b.due())
        b.add("看")
        now = 50L
        assertFalse(b.due())
    }

    @Test
    fun `the batch is due once the interval passed`() {
        val b = batch()
        now = 0L
        b.add("看")
        now = 120L
        assertTrue(b.due())
        assertTrue(b.add("日志"))
    }

    @Test
    fun `several deltas leave as one piece of text`() {
        val b = batch()
        now = 0L
        b.add("看")
        b.add("日志")
        b.add("：E-SHADER-010")
        assertEquals("看日志：E-SHADER-010", b.take())
    }

    @Test
    fun `the buffer starts over after a flush`() {
        val b = batch()
        now = 0L
        b.add("看")
        assertEquals("看", b.take())
        assertEquals("", b.take())
        b.add("日志")
        assertEquals("日志", b.take())
    }

    @Test
    fun `a flush restarts the interval`() {
        val b = batch()
        now = 0L
        b.add("看")
        now = 120L
        b.take()
        now = 180L
        assertFalse(b.due())   // the flush was at 120, the next one is due at 220
        now = 240L
        assertTrue(b.due())
    }
}
