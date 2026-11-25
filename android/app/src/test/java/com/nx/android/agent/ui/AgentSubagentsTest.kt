package com.nx.android.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 结果的预览：带围栏的回答给出围栏里的字，不给出围栏本身。
 */
class AgentSubagentsTest {

    @Test
    fun `a fenced answer shows its own words`() {
        assertEquals("MARKER_TEST", previewOf("```\nMARKER_TEST\n```"))
        assertEquals("MARKER_TEST", previewOf("```\nMARKER_TEST\n```\n"))
    }

    @Test
    fun `a plain answer stays as it is`() {
        assertEquals("just words", previewOf("just words"))
        assertEquals("just words", previewOf("  just words  "))
    }

    @Test
    fun `a lonely fence is left alone`() {
        assertEquals("```", previewOf("```"))
        assertEquals("```text", previewOf("```text"))
    }

    @Test
    fun `the preview is the first paragraph, not the first line`() {
        assertEquals(
            "the digest the player reads",
            previewOf("the digest the player reads\n\nthe English detail follows"),
        )
    }

    @Test
    fun `a fenced answer keeps its first paragraph`() {
        assertEquals(
            "MARKER_TEST",
            previewOf("```\nMARKER_TEST\n\nmore detail\n```"),
        )
    }

    @Test
    fun `a duration reads in seconds or minutes`() {
        assertEquals("850ms", formatDuration(850L))
        assertEquals("3.2s", formatDuration(3235L))
        assertEquals("1m 5s", formatDuration(65_000L))
    }
}
