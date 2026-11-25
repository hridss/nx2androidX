package com.nx.android.agent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子 Agent 完成的通知：那��� `<task-notification>` 的读法。
 *
 * 形状按 pi-subagents 0.26.2 写出来的那份来。
 */
class SubagentNotificationsTest {

    private val notice = """
        <task-notification>
        <task-id>4e69faff-f37e-417</task-id>
        <tool-use-id>call_01a2063463f271c8bcd68cd4</tool-use-id>
        <output-file>/data/user/0/com.nx.android/files/tmp/pi-subagents-10333/x/tasks/4e69faff.output</output-file>
        <status>Done</status>
        <summary>Agent "Run marker echo" completed</summary>
        <result>```
        MARKER_TEST
        ```</result>
        <usage><total_tokens>4932</total_tokens><tool_uses>1</tool_uses><duration_ms>3235</duration_ms></usage>
        </task-notification>
    """.trimIndent()

    @Test
    fun `a notice becomes a notification`() {
        val parsed = SubagentNotifications.parse(notice)
        assertNotNull(parsed)
        assertEquals("4e69faff-f37e-417", parsed!!.id)
        assertEquals("Done", parsed.status)
        assertEquals("Agent \"Run marker echo\" completed", parsed.summary)
        assertTrue(parsed.result!!.contains("MARKER_TEST"))
        assertEquals(4932L, parsed.totalTokens)
        assertEquals(1L, parsed.toolUses)
        assertEquals(3235L, parsed.durationMs)
        assertEquals("call_01a2063463f271c8bcd68cd4", parsed.toolCallId)
    }

    @Test
    fun `the escaped text comes back readable`() {
        val parsed = SubagentNotifications.parse(
            "<task-notification><task-id>x</task-id><summary>a &amp; b &lt;c&gt;</summary></task-notification>",
        )
        assertEquals("a & b <c>", parsed!!.summary)
    }

    @Test
    fun `a notice without usage still parses`() {
        val parsed = SubagentNotifications.parse(
            "<task-notification><task-id>x</task-id><status>Done</status><summary>s</summary></task-notification>",
        )
        assertNotNull(parsed)
        assertEquals(0L, parsed!!.totalTokens)
        assertEquals(0L, parsed.durationMs)
        assertNull(parsed.toolCallId)
    }

    @Test
    fun `text that is not a notice is not one`() {
        assertNull(SubagentNotifications.parse(""))
        assertNull(SubagentNotifications.parse("just some words"))
        assertNull(SubagentNotifications.parse("<other><task-id>x</task-id></other>"))
    }

    @Test
    fun `a notice without a task id is not one`() {
        assertNull(SubagentNotifications.parse("<task-notification><status>Done</status></task-notification>"))
    }
}
