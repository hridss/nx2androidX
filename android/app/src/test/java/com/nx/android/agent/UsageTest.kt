package com.nx.android.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量：引擎写下的那些数，App 怎么读、怎么加。
 *
 * 数字全是引擎给的——App 只做加法，不自己估。
 */
class UsageTest {

    /** 引擎给一条回答记的那份 usage。 */
    private fun usage(
        input: Long = 0L,
        output: Long = 0L,
        cacheRead: Long = 0L,
        cacheWrite: Long = 0L,
        cost: Double = 0.0,
    ): JSONObject {
        val json = JSONObject()
            .put("input", input)
            .put("output", output)
            .put("cacheRead", cacheRead)
            .put("cacheWrite", cacheWrite)
            .put("totalTokens", input + output + cacheRead + cacheWrite)
        json.put("cost", JSONObject().put("total", cost).put("input", 0.0).put("output", 0.0))
        return json
    }

    /** 一条回答：role 与它自己的 usage。 */
    private fun assistant(usage: JSONObject?): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", "done")
        usage?.let { message.put("usage", it) }
        return message
    }

    private fun user(text: String): JSONObject =
        JSONObject().put("role", "user").put("content", text)

    @Test
    fun `a usage object gives the four parts and the cost`() {
        val read = tokenUsageOf(usage(input = 10, output = 20, cacheRead = 30, cacheWrite = 40, cost = 0.25))
        assertEquals(10L, read.input)
        assertEquals(20L, read.output)
        assertEquals(30L, read.cacheRead)
        assertEquals(40L, read.cacheWrite)
        assertEquals(0.25, read.cost, 0.0001)
        assertEquals(100L, read.total)
    }

    @Test
    fun `no usage means nothing was spent`() {
        assertTrue(tokenUsageOf(null).empty)
        assertTrue(tokenUsageOf(JSONObject()).empty)
    }

    @Test
    fun `the session ledger gives the whole conversation`() {
        // 引擎报的那本账：tokens 是四个分项，cost 是一个数
        val data = JSONObject()
            .put(
                "tokens",
                JSONObject()
                    .put("input", 100)
                    .put("output", 200)
                    .put("cacheRead", 3000)
                    .put("cacheWrite", 400)
                    .put("total", 3700),
            )
            .put("cost", 1.5)
        val read = sessionUsageOf(data)
        assertEquals(100L, read.input)
        assertEquals(200L, read.output)
        assertEquals(3000L, read.cacheRead)
        assertEquals(400L, read.cacheWrite)
        assertEquals(3700L, read.total)
        assertEquals(1.5, read.cost, 0.0001)
    }

    @Test
    fun `a session without stats is empty, not broken`() {
        assertTrue(sessionUsageOf(null).empty)
    }

    @Test
    fun `a run's usage is the sum of its own answers`() {
        val usage = usageOfMessages(
            listOf(
                user("do it"),
                assistant(usage(input = 5, output = 7, cost = 0.1)),
                assistant(usage(input = 1, output = 2, cost = 0.2)),
                user("still there?"),
            ),
        )
        assertEquals(6L, usage.input)
        assertEquals(9L, usage.output)
        assertEquals(0.3, usage.cost, 0.0001)
    }

    @Test
    fun `the running run counts only what came after its last task`() {
        // 尾巴里有两轮：上一轮跑完了，这一轮正在跑——只算后一轮
        val usage = usageOfRunning(
            listOf(
                user("first task"),
                assistant(usage(output = 100)),
                user("second task"),
                assistant(usage(output = 7)),
                assistant(usage(output = 3)),
            ),
        )
        assertEquals(10L, usage.output)
        assertEquals(0L, usage.input)
    }

    @Test
    fun `two usages add up`() {
        val total = tokenUsageOf(usage(input = 1, output = 2, cost = 0.5)) +
            tokenUsageOf(usage(input = 10, output = 20, cost = 0.25))
        assertEquals(11L, total.input)
        assertEquals(22L, total.output)
        assertEquals(0.75, total.cost, 0.0001)
        assertEquals(33L, total.total)
        assertFalse(total.empty)
    }
}
