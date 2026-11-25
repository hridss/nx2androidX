package com.nx.android.feature.builds

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marks of the game list: a game that is ported to Android gets a check, a game that has only
 * a profile gets an empty box. The list says it the Markdown way (a task list), and the marks are
 * put in before the renderer sees it.
 */
class GameListContentTest {

    @Test
    fun `a ported game gets a check`() {
        val out = GameListContent.withStatusMarks("- [x] Super Mario Odyssey · `0100000000010000`")
        assertTrue(out.contains("✅"))
        assertFalse(out.contains("[x]"))
    }

    @Test
    fun `a game with only a profile gets an empty box`() {
        val out = GameListContent.withStatusMarks("- [ ] Mario Kart 8 Deluxe · `0100152000022000`")
        assertTrue(out.contains("⬜"))
        assertFalse(out.contains("[ ]"))
    }

    @Test
    fun `the rest of the line is left alone`() {
        val line = "Super Mario Odyssey · `0100000000010000` · playable · ported by @shirai91"
        assertEquals("- ✅ $line", GameListContent.withStatusMarks("- [x] $line"))
    }

    @Test
    fun `a line that is not a task list is not touched`() {
        val text = "# Port checklist\n\n**73 / 188 ported**\n\nThe mark of a ported game is [x]."
        assertEquals(text, GameListContent.withStatusMarks(text))
    }

    @Test
    fun `a task list item that is indented gets its mark too`() {
        val out = GameListContent.withStatusMarks("  - [x] Super Mario Odyssey")
        assertEquals("  - ✅ Super Mario Odyssey", out)
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals("", GameListContent.withStatusMarks(""))
    }

    @Test
    fun `the list parses as the markdown the renderer draws`() {
        // The page renders the list with the assistant's renderer: the list it gets has to parse
        val list = """
            # Port checklist

            **73 / 188 ported**

            - [x] Super Mario Odyssey · `0100000000010000` · playable · ported by @shirai91
            - [ ] Mario Kart 8 Deluxe · `0100152000022000` · 4.0.0
        """.trimIndent()
        val parsed = parseMarkdown(GameListContent.withStatusMarks(list))
        assertTrue(parsed is State.Success)
    }
}
