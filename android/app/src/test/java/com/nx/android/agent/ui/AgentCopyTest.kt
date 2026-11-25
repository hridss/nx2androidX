package com.nx.android.agent.ui

import com.nx.android.agent.AgentMessage
import com.nx.android.agent.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the copy button puts on the clipboard: the answer's own markdown — the code fence, its
 * indent and all — not the rendered look of it.
 */
class AgentCopyTest {

    private val markdown = """
        ## 原因

        着色器切分失败：

        ```
        E-SHADER-010
        ```

        - 先跑 `porter build --experimental`
    """.trimIndent()

    @Test
    fun `the copy carries the answer's own text`() {
        val message = AgentMessage(1L, AgentRole.ASSISTANT, markdown)
        assertEquals(markdown, copyPayloadOf(message))
    }

    @Test
    fun `the code fence and its indent survive the copy`() {
        val message = AgentMessage(2L, AgentRole.ASSISTANT, markdown)
        val payload = copyPayloadOf(message)
        assertTrue(payload.contains("```"))
        assertTrue(payload.contains("E-SHADER-010"))
        assertTrue(payload.contains("## 原因"))
    }
}
