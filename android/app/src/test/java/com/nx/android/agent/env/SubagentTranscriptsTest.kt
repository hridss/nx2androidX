package com.nx.android.agent.env

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * transcript 的尾巴：只读最后几行完整的，半行不算，坏行跳过。
 */
class SubagentTranscriptsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 一行 transcript 的样子：外层是壳，里面是引擎自己的消息。 */
    private fun line(role: String, text: String) = JSONObject().apply {
        put("isSidechain", true)
        put("agentId", "abc")
        put("type", role)
        put("message", JSONObject().put("role", role).put("content", text))
    }.toString()

    /** 一条工具调用：面板上数「干了多少活」数的就是它。 */
    private fun toolCall(name: String, argument: String) = JSONObject().apply {
        put("isSidechain", true)
        put("agentId", "abc")
        put("type", "assistant")
        put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "toolCall").put("id", "call-1").put("name", name)
                .put("arguments", JSONObject().put("command", argument)),
        )))
    }.toString()

    @Test
    fun `an empty file gives nothing`() {
        val file = folder.newFile("a.output")
        assertTrue(SubagentTranscripts.tail(file, 10).isEmpty())
    }

    @Test
    fun `a file that is not there gives nothing`() {
        assertTrue(SubagentTranscripts.tail(File(folder.root, "absent.output"), 10).isEmpty())
    }

    @Test
    fun `the whole lines come back`() {
        val file = folder.newFile("a.output")
        file.writeText(listOf(line("user", "hello"), line("assistant", "hi")).joinToString("\n") + "\n")
        val messages = SubagentTranscripts.tail(file, 10)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].optString("role"))
        assertEquals("assistant", messages[1].optString("role"))
    }

    @Test
    fun `a broken line in the middle is skipped`() {
        val file = folder.newFile("a.output")
        file.writeText(
            listOf(line("user", "hello"), "{ not json", line("assistant", "hi")).joinToString("\n") + "\n",
        )
        val messages = SubagentTranscripts.tail(file, 10)
        assertEquals(2, messages.size)
    }

    @Test
    fun `only the last lines are kept`() {
        val file = folder.newFile("a.output")
        file.writeText((1..50).joinToString("\n") { line("user", "n$it") } + "\n")
        val messages = SubagentTranscripts.tail(file, 5)
        assertEquals(5, messages.size)
        assertEquals("n50", messages.last().optString("content"))
    }

    @Test
    fun `a half line at the front is not read`() {
        val file = folder.newFile("a.output")
        // 比一块还大：读文件头的时候第一行被切开了
        file.writeText(line("user", "x".repeat(200_000)) + "\n" + line("assistant", "hi") + "\n")
        val messages = SubagentTranscripts.tail(file, 10)
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].optString("role"))
    }

    @Test
    fun `the tasks folder is found by the session's id`() {
        val scratch = folder.newFolder("tmp")
        val tasks = File(scratch, "pi-subagents-10333/data-user-0-x/parent-1/tasks")
        tasks.mkdirs()
        File(tasks, "agent-1.output").writeText(line("user", "hello") + "\n")

        val found = SubagentTranscripts.taskDir(scratch, "parent-1")
        assertTrue(found != null)
        assertEquals(tasks.absolutePath, found!!.absolutePath)
        assertTrue(SubagentTranscripts.taskDir(scratch, "other") == null)
    }

    @Test
    fun `the transcripts of a session come back with their prompt`() {
        val scratch = folder.newFolder("tmp")
        val tasks = File(scratch, "pi-subagents-10333/data-user-0-x/parent-1/tasks")
        tasks.mkdirs()
        File(tasks, "agent-1.output").writeText(line("user", "do the thing") + "\n")

        val transcripts = SubagentTranscripts.of(scratch, "parent-1")
        assertEquals(1, transcripts.size)
        assertEquals("agent-1", transcripts[0].agentId)
        assertEquals("do the thing", transcripts[0].prompt)
    }

    @Test
    fun `the task of the agent is read from the head of the file`() {
        // 一次长活：尾部早就滚过去了，任务还在开头
        val file = folder.newFile("a.output")
        file.writeText(
            listOf(line("user", "the first task"), line("assistant", "working")).joinToString("\n") + "\n" +
                (1..50).joinToString("\n") { line("assistant", "step $it") } + "\n",
        )
        assertEquals("the first task", SubagentTranscripts.headPrompt(file))
    }

    @Test
    fun `a file without a task at the head has none`() {
        val file = folder.newFile("a.output")
        file.writeText(line("assistant", "just talking") + "\n")
        assertNull(SubagentTranscripts.headPrompt(file))
    }

    @Test
    fun `the runs of an agent are the user messages in its transcript`() {
        val file = folder.newFile("a.output")
        file.writeText(
            listOf(
                line("user", "first task"),
                line("assistant", "working"),
                line("user", "second task"),
                line("assistant", "working"),
            ).joinToString("\n") + "\n",
        )
        val counter = RunCounter()
        assertEquals(2, counter.count(file).runs)
    }

    @Test
    fun `a transcript that grows counts the new run too`() {
        val file = folder.newFile("a.output")
        file.writeText(line("user", "first task") + "\n")
        val counter = RunCounter()
        assertEquals(1, counter.count(file).runs)
        file.appendText(line("user", "second task") + "\n")
        assertEquals(2, counter.count(file).runs)
    }

    @Test
    fun `the tool calls of the newest run are counted apart`() {
        val file = folder.newFile("a.output")
        file.writeText(line("user", "first task") + "\n")
        file.appendText(toolCall("bash", "ls") + "\n")
        file.appendText(toolCall("read", "x.txt") + "\n")
        val counter = RunCounter()
        val first = counter.count(file)
        assertEquals(1, first.runs)
        assertEquals(2, first.toolUses)

        // 第二轮开始：这一轮自己的调用数从零算起
        file.appendText(line("user", "second task") + "\n")
        file.appendText(toolCall("bash", "grep") + "\n")
        val second = counter.count(file)
        assertEquals(2, second.runs)
        assertEquals(1, second.toolUses)
    }

    @Test
    fun `a running agent's whole conversation is read from the head`() {
        // 一次长活：任务在开头，尾部早就滚过去了（面板只读尾部）
        val file = folder.newFile("long.output")
        file.writeText(line("user", "the task") + "\n")
        repeat(400) { file.appendText(line("assistant", "step $it") + "\n") }

        val transcript = SubagentTranscripts.Transcript(
            agentId = "long",
            prompt = "the task",
            messages = SubagentTranscripts.tail(file, 200),
            file = file,
        )
        // 尾部里没有那条 user 消息了：只读尾部，界面就什么都读不出来
        assertTrue(transcript.messages.none { it.optString("role") == "user" })

        val whole = SubagentConversations.ofRunning(transcript)
        assertTrue(whole.isNotEmpty())
        assertEquals("user", whole.first().optString("role"))
        assertEquals("the task", SubagentTranscripts.textOf(whole.first()))
    }

    @Test
    fun `a running agent without a file keeps what it has`() {
        val transcript = SubagentTranscripts.Transcript(
            agentId = "x",
            prompt = "task",
            messages = listOf(JSONObject().put("role", "user").put("content", "hi")),
        )
        val whole = SubagentConversations.ofRunning(transcript)
        assertEquals(1, whole.size)
        assertEquals("hi", SubagentTranscripts.textOf(whole.first()))
    }

    @Test
    fun `the messages of a session file come back in order`() {
        val file = folder.newFile("session.jsonl")
        file.writeText(
            listOf(
                """{"type":"session","version":3,"id":"abc"}""",
                """{"type":"message","message":{"role":"user","content":"hi"}}""",
                """{"type":"custom","customType":"subagents:record","data":{}}""",
                """{"type":"message","message":{"role":"assistant","content":"yo"}}""",
            ).joinToString("\n"),
        )
        val messages = SubagentTranscripts.messages(file)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].optString("role"))
        assertEquals("assistant", messages[1].optString("role"))
    }

    @Test
    fun `the step a running agent is at is its last tool call`() {
        val message = JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "toolCall").put("name", "bash")
                .put("arguments", JSONObject().put("command", "python3 tools/log_triage.py --port x")),
        ))
        val step = SubagentTranscripts.lastStep(listOf(message))
        assertTrue(step != null)
        assertTrue(step!!.startsWith("bash"))
        assertTrue(step.contains("log_triage.py"))
    }

    @Test
    fun `a reading agent says which file it reads`() {
        val message = JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "toolCall").put("name", "read")
                .put("arguments", JSONObject().put("path", "/data/tool/nxnative/dm/android/gfx_vk_core.c")),
        ))
        val step = SubagentTranscripts.lastStep(listOf(message))
        assertTrue(step != null)
        assertTrue(step!!.contains("gfx_vk_core.c"))
    }

    @Test
    fun `an agent that has not called anything yet has no step`() {
        assertNull(SubagentTranscripts.lastStep(emptyList()))
    }

}
