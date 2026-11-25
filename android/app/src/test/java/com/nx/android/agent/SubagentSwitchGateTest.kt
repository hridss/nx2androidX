package com.nx.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切会话前的闸：当前会话有正在跑的子任务时，要先问玩家一句。
 *
 * 子代理跟着会话活——切走会把正在跑的那些掐断。这道闸只认一件事：有没有还在跑的。
 */
class SubagentSwitchGateTest {

    private fun run(status: String) = AgentSubagent(
        id = "a1",
        type = "gamespec",
        description = "fix the crash",
        status = status,
    )

    @Test
    fun `a running sub-agent asks before the session is switched away`() {
        assertTrue(needsConfirmBeforeSwitch(listOf(run("running"))))
    }

    @Test
    fun `a finished one does not ask`() {
        assertFalse(needsConfirmBeforeSwitch(listOf(run("completed"))))
    }

    @Test
    fun `an interrupted one does not ask`() {
        // 已经被掐断的那一次不再是在跑的事：它不会再动，也拦不住玩家去别处
        assertFalse(needsConfirmBeforeSwitch(listOf(run("interrupted"))))
    }

    @Test
    fun `a failed one does not ask`() {
        assertFalse(needsConfirmBeforeSwitch(listOf(run("error"))))
    }

    @Test
    fun `nothing running means nothing to ask`() {
        assertFalse(needsConfirmBeforeSwitch(emptyList()))
    }

    @Test
    fun `one running among the finished ones still asks`() {
        assertTrue(needsConfirmBeforeSwitch(listOf(run("completed"), run("running"))))
    }
}
