package com.nx.android.appstate

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page state snapshot: what the App writes and the assistant reads.
 *
 * It says where the player is, which game is on that page, and what the page carries -
 * the build stage, the last build, the run options. The build's progress is not in it:
 * it changes every second and would only churn the file.
 */
class PageStateTest {

    @Test
    fun `the snapshot says where the player is`() {
        val state = PageState(
            page = "builds",
            titleId = "0100F28011892000",
            name = "Overcooked Special Edition",
            facts = PageFacts.builds("3/8 shader split", null),
        )
        val json = JSONObject(PageStateJson.encode(state, 1_700_000_000_000L))

        assertEquals("nx2a.page_state.v1", json.getString("schema"))
        assertEquals("builds", json.getString("page"))
        assertEquals("0100F28011892000", json.getString("titleId"))
        assertEquals("Overcooked Special Edition", json.getString("name"))
        assertEquals(1_700_000_000_000L, json.getLong("generatedAt"))
    }

    @Test
    fun `the last build names the game, the outcome and the log`() {
        val last = LastBuild(
            game = "/storage/emulated/0/本体v1.0.0.nsp",
            titleId = "0100F28011892000",
            phase = "failed",
            startedAt = 1_700_000_000_000L,
            logPath = "/data/user/0/com.nx.android/files/nx2a/builds/abc/log.txt",
        )
        val facts = PageFacts.builds(null, last)

        val line = facts.getValue(PageFacts.LAST_BUILD)
        assertTrue(line.contains("0100F28011892000"))
        assertTrue(line.contains("failed"))
        assertTrue(line.contains("builds/abc/log.txt"))
        // the file's name, not its whole path
        assertTrue(line.startsWith("本体v1.0.0.nsp"))
    }

    @Test
    fun `the stage rides along while a build runs`() {
        val facts = PageFacts.builds("3/8 shader split", null)

        assertEquals("3/8 shader split", facts.getValue(PageFacts.BUILD_STAGE))
        assertFalse(facts.containsKey(PageFacts.LAST_BUILD))
    }

    @Test
    fun `the build's progress is not in the snapshot`() {
        // the progress changes every second: it would only churn the file
        val facts = PageFacts.builds("3/8 shader split", null)

        assertFalse(facts.values.any { it.contains("%") })
        assertFalse(facts.keys.any { it.contains("progress") })
    }

    @Test
    fun `a page without an object still says which page it is`() {
        val state = PageState(page = "library")
        val json = JSONObject(PageStateJson.encode(state, 0L))

        assertEquals("library", json.getString("page"))
        assertEquals("", json.getString("titleId"))
    }

    @Test
    fun `the snapshot reads back`() {
        val state = PageState("game", "0100F2C0115B6000", "Zelda", mapOf(PageFacts.LANGUAGE to "zh-Hans"))

        val back = PageStateJson.decode(PageStateJson.encode(state, 0L))

        assertNotNull(back)
        assertEquals("game", back!!.page)
        assertEquals("0100F2C0115B6000", back.titleId)
        assertEquals("Zelda", back.name)
        assertEquals("zh-Hans", back.facts[PageFacts.LANGUAGE])
    }

    @Test
    fun `the snapshot says how much room is left`() {
        val state = PageState("builds", storage = "2.2G free of 450G (99% used)")
        val json = JSONObject(PageStateJson.encode(state, 0L))

        assertEquals("2.2G free of 450G (99% used)", json.getString("storage"))
    }

    @Test
    fun `the room left reads like a human reads it`() {
        val gb = 1024L * 1024 * 1024
        assertEquals("2.2G free of 450G (99% used)", storageFact((2.2 * gb).toLong(), 450 * gb))
        assertEquals("450M free of 1.0G (56% used)", storageFact(450L * 1024 * 1024, gb))
        assertEquals("512M free of 1.0G (50% used)", storageFact(512L * 1024 * 1024, gb))
    }

    @Test
    fun `no room to speak of means no line`() {
        assertNull(storageFact(0, 0))
    }

    @Test
    fun `a file of another shape is not a snapshot`() {
        assertNull(PageStateJson.decode("{\"schema\":\"something.else\"}"))
        assertNull(PageStateJson.decode("not json at all"))
    }

    @Test
    fun `a reader never sees half a snapshot`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nx2a-page-state-${System.nanoTime()}")
        val file = File(dir, "page-state.json")
        try {
            repeat(50) { i ->
                val state = PageState("builds", "0100F28011892000", "game $i", mapOf(PageFacts.LAST_BUILD to "line $i"))
                assertTrue(PageStateFile.writeAtomic(file, PageStateJson.encode(state, i.toLong())))
                // what a reader sees is always a whole snapshot
                val text = PageStateFile.read(file)
                assertNotNull(text)
                assertNotNull(PageStateJson.decode(text!!))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
