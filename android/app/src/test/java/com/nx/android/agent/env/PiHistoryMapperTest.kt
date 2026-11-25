package com.nx.android.agent.env

import com.nx.android.agent.AgentRole
import com.nx.android.agent.AgentToolState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎消息 → 面板消息的映射：归拢一轮、剥注入块、工具卡对回、中断推导。
 * 喂的 JSON 全部按真机 spike 抓到的字段形状造。
 */
class PiHistoryMapperTest {

    private fun system() = JSONObject("""{"role":"system","content":"","sections":{"preamble":"pi"}}""")

    private fun user(text: String) = JSONObject(
        mapOf("role" to "user", "content" to JSONArray().put(JSONObject(mapOf("type" to "text", "text" to text)))),
    )

    private fun assistant(vararg parts: JSONObject, stop: String = "stop", output: Long = 0L) = JSONObject().apply {
        put("role", "assistant")
        put("content", JSONArray().apply { parts.forEach { put(it) } })
        put("stopReason", stop)
        if (output > 0) put("usage", JSONObject().put("output", output))
    }

    private fun thinking() = JSONObject("""{"type":"thinking","thinking":"先想想"}""")
    private fun text(t: String) = JSONObject("""{"type":"text","text":"$t"}""")
    private fun toolCall(id: String, name: String = "bash") = JSONObject(
        mapOf("type" to "toolCall", "id" to id, "name" to name, "arguments" to JSONObject(mapOf("command" to "ls"))),
    )

    private fun toolResult(id: String, result: String, error: Boolean = false) = JSONObject().apply {
        put("role", "toolResult")
        put("toolCallId", id)
        put("toolName", "bash")
        put("content", JSONArray().put(JSONObject(mapOf("type" to "text", "text" to result))))
        put("isError", error)
    }

    @Test
    fun `a plain turn becomes two messages`() {
        val out = PiHistoryMapper.map(
            listOf(
                system(),
                user("[device] the library is empty\nplayer says:为啥闪退"),
                assistant(thinking(), text("看日志"), output = 128L),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(AgentRole.USER, out[0].role)
        assertEquals("为啥闪退", out[0].text)   // 注入块剥掉，只留玩家的话
        assertEquals("看日志", out[1].text)    // thinking 不进正文
        assertEquals(128L, out[1].tokens)
        assertFalse(out[0].pending)
    }

    @Test
    fun `a turn without the marker keeps the whole words`() {
        val out = PiHistoryMapper.map(listOf(user("直接一句话"), assistant(text("答"))))
        assertEquals("直接一句话", out[0].text)
    }

    @Test
    fun `the thinking lands in its own fold`() {
        val out = PiHistoryMapper.map(
            listOf(
                user("为啥闪退"),
                assistant(thinking(), text("看日志"), stop = "stop"),
            ),
        )
        assertEquals("看日志", out[1].text)      // 正文里没有思考
        assertEquals("先想想", out[1].thinking)   // 思考在自己那一格里
    }

    @Test
    fun `a tool turn folds its calls into the answer`() {
        val out = PiHistoryMapper.map(
            listOf(
                user("player says:查一下"),
                assistant(thinking(), toolCall("c1"), toolCall("c2"), stop = "toolUse", output = 60L),
                toolResult("c1", "3 files"),
                toolResult("c2", "boom", error = true),
                assistant(text("结论"), output = 68L),
            ),
        )
        assertEquals(2, out.size)
        val answer = out[1]
        assertEquals("结论", answer.text)
        assertEquals(2, answer.tools.size)
        assertEquals(AgentToolState.DONE, answer.tools[0].state)
        assertEquals("3 files", answer.tools[0].resultSummary)
        assertEquals(AgentToolState.FAILED, answer.tools[1].state)
        assertEquals(128L, answer.tokens)   // 两条模型消息的 output 累加
    }

    @Test
    fun `an aborted turn is a finished turn`() {
        val out = PiHistoryMapper.map(
            listOf(user("player says:讲个故事"), assistant(text("从前有座山，"), stop = "aborted", output = 4550L)),
        )
        assertFalse(out[0].pending)          // 玩家自己的收尾，不算中断
        assertEquals("从前有座山，", out[1].text)
        assertEquals(4550L, out[1].tokens)
    }

    @Test
    fun `a turn killed before anything is marked interrupted`() {
        val out = PiHistoryMapper.map(listOf(system(), user("player says:讲个故事")))
        assertEquals(1, out.size)
        assertTrue(out[0].pending)
        assertNull(out[0].tools.toList().firstOrNull { it.state == AgentToolState.RUNNING })
    }

    @Test
    fun `a turn killed between tool calls is marked interrupted`() {
        val out = PiHistoryMapper.map(
            listOf(
                user("player says:查一下"),
                assistant(toolCall("c1"), stop = "toolUse"),
                toolResult("c1", "3 files"),
                // 引擎在这里没了：没有收尾的 assistant
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out[0].pending)
        assertEquals(AgentToolState.DONE, out[1].tools[0].state)
    }

    @Test
    fun `many turns keep their order and never share ids`() {
        val out = PiHistoryMapper.map(
            listOf(
                user("player says:一"), assistant(text("答一")),
                user("player says:二"), assistant(text("答二")),
            ),
        )
        assertEquals(4, out.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), out.map { it.id })
        assertEquals(listOf("一", "答一", "二", "答二"), out.map { it.text })
    }

    @Test
    fun `an empty session renders nothing`() {
        assertTrue(PiHistoryMapper.map(listOf(system())).isEmpty())
        assertTrue(PiHistoryMapper.map(emptyList()).isEmpty())
    }

    @Test
    fun `assistant messages before any question are skipped`() {
        val out = PiHistoryMapper.map(
            listOf(system(), assistant(text("没有问题的自言自语")), user("player says:来了"), assistant(text("答"))),
        )
        assertEquals(2, out.size)
        assertEquals("答", out[1].text)
    }

    @Test
    fun `a sub-agent notification becomes a card`() {
        val notice = JSONObject().apply {
            put("role", "custom")
            put("customType", "subagent-notification")
            put("content", "<task-notification><task-id>t1</task-id><status>Done</status>" +
                "<summary>Agent \"run\" completed</summary>" +
                "<usage><total_tokens>10</total_tokens><duration_ms>500</duration_ms></usage>" +
                "</task-notification>")
        }
        val out = PiHistoryMapper.map(listOf(user("player says:派个活"), assistant(text("派了")), notice))
        assertEquals(2, out.size)
        assertEquals(1, out[1].notifications.size)
        val card = out[1].notifications.first()
        assertEquals("t1", card.id)
        assertEquals("Done", card.status)
        assertEquals(10L, card.totalTokens)
    }

    @Test
    fun `other custom messages are ignored`() {
        val other = JSONObject().apply {
            put("role", "custom")
            put("customType", "something-else")
            put("content", "not a notice")
        }
        val out = PiHistoryMapper.map(listOf(user("player says:派个活"), assistant(text("派了")), other))
        assertEquals(2, out.size)
        assertTrue(out[1].notifications.isEmpty())
    }

    @Test
    fun `an agent tool call carries the sub-agent it spawned`() {
        val call = JSONObject().apply {
            put("type", "toolCall")
            put("id", "c9")
            put("name", "Agent")
            put(
                "arguments",
                JSONObject()
                    .put("prompt", "run echo MARKER_TEST")
                    .put("description", "Run marker echo")
                    .put("subagent_type", "general-purpose"),
            )
        }
        val out = PiHistoryMapper.map(listOf(user("player says:派个活"), assistant(call, stop = "toolUse")))
        val card = out[1].tools.first()
        assertNotNull(card.subagent)
        assertEquals("general-purpose", card.subagent!!.type)
        assertEquals("Run marker echo", card.subagent!!.description)
        assertEquals("run echo MARKER_TEST", card.subagent!!.prompt)
        assertEquals("Run marker echo", card.argsSummary)
    }
}
