package com.nx.android.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 攻关档案的运行事故流水：三种事件、只追加、每个游戏各写各的。
 */
class GamespecTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val game = "0100A3501946E000"

    private fun events(): List<String> {
        val file = File(Gamespec.dir(folder.root, game), Gamespec.EVENTS_FILE)
        return if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    @Test
    fun `an event is one line in the game's own file`() {
        Gamespec.appendEvent(folder.root, game, Gamespec.EXIT, "进程异常退出", at = 100L)

        assertEquals(1, events().size)
        val line = events().first()
        assertTrue(line, line.contains("\"kind\":\"process_exit\""))
        assertTrue(line, line.contains("\"ts\":100"))
        assertTrue(line, line.contains("进程异常退出"))
    }

    @Test
    fun `the three events are three kinds`() {
        Gamespec.appendEvent(folder.root, game, Gamespec.EXIT, "崩了", at = 1L)
        Gamespec.appendEvent(folder.root, game, Gamespec.REBUILD, "重编了", at = 2L)
        Gamespec.appendEvent(folder.root, game, Gamespec.LAUNCH_FAILED, "起不来", at = 3L)

        assertEquals(3, events().size)
        assertTrue(events()[0].contains("process_exit"))
        assertTrue(events()[1].contains("runtime_rebuild"))
        assertTrue(events()[2].contains("launch_failed"))
    }

    @Test
    fun `the file only grows`() {
        Gamespec.appendEvent(folder.root, game, Gamespec.EXIT, "第一次", at = 1L)
        Gamespec.appendEvent(folder.root, game, Gamespec.EXIT, "第二次", at = 2L)

        assertEquals(2, events().size)
        assertTrue(events()[0].contains("第一次"))
        assertTrue(events()[1].contains("第二次"))
    }

    @Test
    fun `the folder is made when the first event lands`() {
        assertTrue(!Gamespec.dir(folder.root, game).exists())
        Gamespec.appendEvent(folder.root, game, Gamespec.REBUILD, "重编了")
        assertTrue(Gamespec.dir(folder.root, game).isDirectory)
    }

    @Test
    fun `every game has its own file`() {
        val other = "0100000000010000"
        Gamespec.appendEvent(folder.root, game, Gamespec.EXIT, "这局崩了")
        Gamespec.appendEvent(folder.root, other, Gamespec.EXIT, "那局崩了")

        assertEquals(1, events().size)
        val otherFile = File(Gamespec.dir(folder.root, other), Gamespec.EVENTS_FILE)
        assertEquals(1, otherFile.readLines().count { it.isNotBlank() })
        assertTrue(otherFile.readText().contains("那局崩了"))
    }

    @Test
    fun `a game without a title id is not written to`() {
        Gamespec.appendEvent(folder.root, "", Gamespec.EXIT, "不知道是哪一局")
        assertTrue(!File(folder.root, "gamespec").exists())
    }

    @Test
    fun `the events are read back`() {
        Gamespec.appendEvent(folder.root, game, Gamespec.EXIT, "崩了", at = 7L)
        val read = Gamespec.events(folder.root, game)
        assertEquals(1, read.size)
        assertEquals("process_exit", read.first().optString("kind"))
        assertEquals(7L, read.first().optLong("ts"))
    }

    @Test
    fun `a game without events has none`() {
        assertTrue(Gamespec.events(folder.root, "0100").isEmpty())
    }

    @Test
    fun `the plan waiting for the player is read out of the document`() {
        val spec = """# Fire Emblem (010055D009F78000) case file

## Runtime problems
### 2026-11-23 cross-chunk copy dropped whole (status: pending approval)
Symptom: SIGSEGV 4.5 s after the copy
Cause: vk_buf_at does not find the source buffer across chunks
Proposed fix: split the copy per chunk
Evidence: log.prev.txt line 412

## Timeline
- 2026-11-23 13:20 the player reported a crash
"""
        val plan = Gamespec.parsePlan(spec)
        assertTrue(plan != null)
        assertEquals("2026-11-23 cross-chunk copy dropped whole", plan!!.headline)
        assertEquals("vk_buf_at does not find the source buffer across chunks", plan.cause)
        assertEquals("split the copy per chunk", plan.fix)
    }

    @Test
    fun `a plan that was already fixed is not waiting any more`() {
        val spec = """## Runtime problems
### 2026-11-23 cross-chunk copy (status: fixed)
Cause: cross chunk
Proposed fix: split it
"""
        assertNull(Gamespec.parsePlan(spec))
    }

    @Test
    fun `the last pending plan is the one that counts`() {
        val spec = """### 2026-11-20 old problem (status: pending approval)
Cause: old
Proposed fix: old fix

### 2026-11-23 new problem (status: pending approval)
Cause: new
Proposed fix: new fix
"""
        val plan = Gamespec.parsePlan(spec)
        assertTrue(plan != null)
        assertEquals("2026-11-23 new problem", plan!!.headline)
        assertEquals("new", plan.cause)
    }

    @Test
    fun `a game without a document has no plan`() {
        assertNull(Gamespec.pendingPlan(Gamespec.dir(folder.root, game)))
    }

    @Test
    fun `the plan reads the English lines of the case file`() {
        val spec = """### 2026-11-23 shader split finds no shaders (status: pending approval)
Cause: the splitter only reads MGFX v8
Proposed fix: merge upstream #2546
"""
        val plan = Gamespec.parsePlan(spec)
        assertTrue(plan != null)
        assertEquals("2026-11-23 shader split finds no shaders", plan!!.headline)
        assertEquals("the splitter only reads MGFX v8", plan.cause)
        assertEquals("merge upstream #2546", plan.fix)
    }

    @Test
    fun `the latest line of the timeline is the case's progress`() {
        val spec = """## Timeline
- 2026-11-23 19:18 the player reported a crash
- 2026-11-23 21:04 the sub-agent is disassembling main.img
"""
        assertEquals("2026-11-23 21:04 the sub-agent is disassembling main.img",
            Gamespec.latestTimelineOf(spec))
    }

    @Test
    fun `a case without a timeline has no progress`() {
        assertNull(Gamespec.latestTimelineOf("## Runtime problems\n\n### something (status: open)\n"))
    }
}
