package com.nx.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The case status the assistant sends itself: a block in its answer.
 *
 * The app does not guess a case's state from the case file any more - the assistant says
 * it, in this format, and the app renders it.
 */
class CaseStatusTest {

    @Test
    fun `the block is taken out of the answer`() {
        val answer = """
            The crash is gone: the second renderer has its own thread now.

            ```nx-case
            {"status": "fixed", "ids": "0100E8B028B54000", "summary": "black screen after the intro"}
            ```
        """.trimIndent()

        val (text, status) = CaseStatusFormat.extract(answer)

        assertTrue("the block is not in the text any more: $text", !text.contains("nx-case"))
        assertTrue(text.contains("The crash is gone"))
        assertEquals("fixed", status?.status)
        assertEquals("0100E8B028B54000", status?.ids)
        assertEquals("black screen after the intro", status?.summary)
    }

    @Test
    fun `an answer without the block has no status`() {
        val (text, status) = CaseStatusFormat.extract("Here is what the app can do.")

        assertNull(status)
        assertEquals("Here is what the app can do.", text)
    }

    @Test
    fun `a block that is not json is not a status`() {
        val (text, status) = CaseStatusFormat.extract("```nx-case\nnot json at all\n```")

        assertNull(status)
        assertTrue(!text.contains("not json"))
    }

    @Test
    fun `a state the app does not know is not a status`() {
        val (text, status) = CaseStatusFormat.extract("```nx-case\n{\"status\": \"pending\"}\n```")

        assertNull(status)
        assertTrue(!text.contains("pending"))
    }

    @Test
    fun `the last block wins`() {
        val answer = """
            ```nx-case
            {"status": "open", "ids": "0100E8B028B54000"}
            ```

            And now it is fixed.

            ```nx-case
            {"status": "fixed", "ids": "0100E8B028B54000"}
            ```
        """.trimIndent()

        val (_, status) = CaseStatusFormat.extract(answer)

        assertEquals("fixed", status?.status)
    }

    @Test
    fun `a code block of another language stays where it is`() {
        val answer = """
            Here is the patch:

            ```c
            int main() { return 0; }
            ```
        """.trimIndent()

        val (text, status) = CaseStatusFormat.extract(answer)

        assertNull(status)
        assertTrue(text.contains("int main()"))
    }
}
