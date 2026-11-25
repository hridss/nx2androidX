package com.nx.android.agent.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 活跃会话的指针：指得到、指得回、新会话清空；旧内容（上一个版本的会话 id）读出来
 * 也只是一个不匹配的字符串，不当回事。
 */
class AgentSessionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): AgentSessionStore = AgentSessionStore(folder.newFolder("sessions"))

    @Test
    fun `the pointer starts empty`() {
        assertNull(store().activePath())
    }

    @Test
    fun `the pointer lands where it is put`() {
        val store = store()
        store.setActive("/ somewhere /a-session.jsonl".replace(" ", ""))
        assertEquals("/somewhere/a-session.jsonl", store.activePath())
    }

    @Test
    fun `a new session clears the pointer`() {
        val store = store()
        store.setActive("/somewhere/a.jsonl")
        store.clearActive()
        assertNull(store.activePath())
    }

    @Test
    fun `an old pointer value is just an opaque string`() {
        val dir = folder.newFolder("sessions")
        File(dir, "_active").writeText("s-1795305443005")
        assertEquals("s-1795305443005", AgentSessionStore(dir).activePath())
    }
}
