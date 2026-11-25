package com.nx.android.agent

import com.nx.android.agent.runtime.composeTurn
import com.nx.android.agent.runtime.deviceState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device state block the model reads: the app's own version leads it, and a long library
 * summary cannot push the version out of the block.
 *
 * The block is English and says nothing about a language: the player's message carries the
 * language of the answer.
 */
class AgentDeviceStateTest {

    private val library = "the library has 12 game(s), 10 of them ported:\n" +
        (1..10).joinToString("\n") { "$it. 某游戏 (0100000000010000) - done" }

    @Test
    fun `the block leads with the app's version`() {
        val state = deviceState("0.4.0-alpha03", library, 600)

        assertTrue(state.startsWith("app version: 0.4.0-alpha03"))
        assertTrue(state.contains("the library has 12 game(s)"))
    }

    @Test
    fun `the version survives a chatty library`() {
        val chatty = library + "\n" + "11. 还有一个游戏 (0100000000020000) - done\n".repeat(40)
        val state = deviceState("0.4.0-alpha03", chatty, 600)

        assertTrue("the block stays within its budget", state.length <= 600)
        assertTrue("the version is still the first thing said", state.startsWith("app version: 0.4.0-alpha03"))
    }

    @Test
    fun `the composed turn carries the version first`() {
        val state = deviceState("0.4.0-alpha03", library, 600)
        val composed = composeTurn("我的 App 什么版本", "", state)

        assertTrue(composed.startsWith("[device] app version: 0.4.0-alpha03"))
        assertTrue(composed.contains("the library has 12 game(s)"))
        assertTrue(composed.endsWith("player says: 我的 App 什么版本"))
    }

    @Test
    fun `the block says nothing about a language`() {
        val state = deviceState("0.4.0-alpha03", library, 600)

        assertTrue(!state.contains("player language"))
    }
}
