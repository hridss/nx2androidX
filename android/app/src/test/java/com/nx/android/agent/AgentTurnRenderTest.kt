package com.nx.android.agent

import com.nx.android.agent.runtime.composeTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn the model reads: the memory block rides ahead, the player's words come last.
 *
 * The page state does not ride along any more - it lives in the page state snapshot, and
 * the assistant reads that file when it needs it. So a turn never carries a page block.
 */
class AgentTurnRenderTest {

    @Test
    fun `without a device or a memory the message is just the words`() {
        assertEquals("为啥闪退", composeTurn("为啥闪退"))
    }

    @Test
    fun `no page context rides into the turn`() {
        val composed = composeTurn("为啥闪退", "某设备上某游戏的修法", "app version: 0.4.0")

        assertFalse(composed.contains("[page context]"))
        assertFalse(composed.contains("working on"))
    }

    @Test
    fun `memory rides ahead of the words`() {
        val composed = composeTurn("试试", "某设备上某游戏的修法")

        assertTrue(composed.startsWith("[memory] 某设备上某游戏的修法"))
        assertTrue(composed.endsWith("player says: 试试"))
    }

    @Test
    fun `the device block rides ahead of the memory`() {
        val composed = composeTurn("试试", "记得的那件事", "app version: 0.4.0")

        assertTrue(composed.indexOf("[device]") < composed.indexOf("[memory]"))
        assertTrue(composed.endsWith("player says: 试试"))
    }
}
