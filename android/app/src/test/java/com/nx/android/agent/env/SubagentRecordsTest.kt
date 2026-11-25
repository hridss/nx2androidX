package com.nx.android.agent.env

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 子 Agent 的记录：会话文件里那些 `custom` 条目。
 *
 * 字段形状按真机抓的来（pi-subagents 0.26.2 写的记录）。
 */
class SubagentRecordsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun recordLine(
        id: String = "4e69faff-f37e-417",
        type: String = "general-purpose",
        description: String = "Run marker echo",
        status: String = "completed",
        result: String? = "```\nMARKER_TEST\n```",
        error: String? = null,
        startedAt: Long = 1795402021501L,
        completedAt: Long? = 1795402024736L,
        sessionFile: String? = "/data/user/0/com.nx.android/files/nx2a/agent/.pi/agent/sessions/x.jsonl",
    ): String {
        val data = org.json.JSONObject()
            .put("id", id)
            .put("type", type)
            .put("description", description)
            .put("status", status)
            .put("startedAt", startedAt)
        if (result != null) data.put("result", result)
        if (error != null) data.put("error", error)
        if (completedAt != null) data.put("completedAt", completedAt)
        if (sessionFile != null) data.put("sessionFile", sessionFile)
        return org.json.JSONObject()
            .put("type", "custom")
            .put("customType", "subagents:record")
            .put("data", data)
            .toString()
    }

    @Test
    fun `a record becomes a run`() {
        val record = SubagentRecords.record(recordLine())
        assertNotNull(record)
        assertEquals("4e69faff-f37e-417", record!!.id)
        assertEquals("general-purpose", record.type)
        assertEquals("Run marker echo", record.description)
        assertEquals("completed", record.status)
        assertEquals("```\nMARKER_TEST\n```", record.result)
        assertNull(record.error)
        assertEquals(3235L, record.durationMs)
        assertTrue(record.sessionFile!!.endsWith("x.jsonl"))
    }

    @Test
    fun `a line that is not a record is nothing`() {
        assertNull(SubagentRecords.record("""{"type":"message","message":{"role":"user"}}"""))
        assertNull(SubagentRecords.record("""{"type":"custom","customType":"other","data":{}}"""))
        assertNull(SubagentRecords.record("not json at all"))
    }

    @Test
    fun `a record without a data object is nothing`() {
        assertNull(SubagentRecords.record("""{"type":"custom","customType":"subagents:record"}"""))
        assertNull(SubagentRecords.record("""{"type":"custom","customType":"subagents:record","data":"nope"}"""))
    }

    @Test
    fun `a record without an id is nothing`() {
        assertNull(SubagentRecords.record("""{"type":"custom","customType":"subagents:record","data":{"type":"general-purpose"}}"""))
    }

    @Test
    fun `the session file gives every run it holds`() {
        val file = folder.newFile("session.jsonl")
        file.writeText(
            listOf(
                """{"type":"session","version":3,"id":"abc"}""",
                recordLine(id = "a", startedAt = 10L, completedAt = 20L),
                """{"type":"message","message":{"role":"user"}}""",
                recordLine(id = "b", startedAt = 30L, completedAt = 40L),
            ).joinToString("\n"),
        )
        val records = SubagentRecords.of(file)
        assertEquals(2, records.size)
        assertEquals(listOf("a", "b"), records.map { it.id })
        assertEquals(10L, records.first().startedAt)
    }

    @Test
    fun `a later record of the same run wins`() {
        val file = folder.newFile("session.jsonl")
        file.writeText(
            listOf(
                recordLine(id = "a", status = "running", completedAt = null),
                recordLine(id = "a", status = "completed", completedAt = 99L),
            ).joinToString("\n"),
        )
        val records = SubagentRecords.of(file)
        assertEquals(1, records.size)
        assertEquals("completed", records.first().status)
    }

    @Test
    fun `the same agent twice is two runs`() {
        // 续跑时引擎的 id 不变，但那是两次运行：各自留着
        val file = folder.newFile("session.jsonl")
        file.writeText(
            listOf(
                recordLine(id = "a", startedAt = 10L, completedAt = 20L),
                recordLine(id = "a", startedAt = 30L, completedAt = 40L),
            ).joinToString("\n"),
        )
        val records = SubagentRecords.of(file)
        assertEquals(2, records.size)
        assertEquals(listOf(10L, 30L), records.map { it.startedAt })
        assertEquals(2, records.map { it.runId }.distinct().size)
    }

    @Test
    fun `a broken line does not take the list down`() {
        val file = folder.newFile("session.jsonl")
        file.writeText(
            listOf(
                "{ this is not json",
                recordLine(id = "a"),
                "",
            ).joinToString("\n"),
        )
        assertEquals(1, SubagentRecords.of(file).size)
    }

    @Test
    fun `a file that is not there gives nothing`() {
        assertTrue(SubagentRecords.of(File(folder.root, "absent.jsonl")).isEmpty())
    }
}
