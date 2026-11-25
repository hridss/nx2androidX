package com.nx.android.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用量面板上的写法：token 怎么写、成本怎么写。
 */
class AgentUsageFormatTest {

    @Test
    fun `small numbers stay as they are`() {
        assertEquals("0", formatUsageTokens(0))
        assertEquals("999", formatUsageTokens(999))
    }

    @Test
    fun `a thousand gets a k`() {
        assertEquals("1.0k", formatUsageTokens(1_000))
        assertEquals("12.3k", formatUsageTokens(12_345))
    }

    @Test
    fun `a million gets an M`() {
        assertEquals("1.0M", formatUsageTokens(1_000_000))
        assertEquals("12.3M", formatUsageTokens(12_345_678))
    }
}
