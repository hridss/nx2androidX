package com.nx.android.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The models file: several profiles, one of them the default, and the keys written as blobs.
 */
class AgentModelFileTest {

    /** A stand-in for the keystore: the blob is the text, upside down. */
    private fun fakeEncrypt(text: String) = "enc:" + text.reversed()
    private fun fakeDecrypt(blob: String) = blob.removePrefix("enc:").reversed()

    private fun profile(id: String, name: String = "glm-4.6") = AgentModelProfile(
        id = id,
        providerId = "glm",
        label = "GLM $id",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        modelName = name,
        apiKey = "sk-$id",
    )

    @Test
    fun theProfilesSurviveTheRoundTrip() {
        val text = AgentModelFile.write(listOf(profile("p1"), profile("p2")), "p2", ::fakeEncrypt)

        val content = AgentModelFile.read(text, ::fakeDecrypt)

        assertEquals(2, content.profiles.size)
        assertEquals("p2", content.activeId)
        assertEquals("sk-p1", content.profiles[0].apiKey)
        assertEquals("glm-4.6", content.profiles[0].modelName)
    }

    @Test
    fun theKeyIsNotWrittenInPlainText() {
        val text = AgentModelFile.write(listOf(profile("p1")), "p1", ::fakeEncrypt)

        assertFalse(text.contains("sk-p1"))
        assertTrue(text.contains("enc:"))
    }

    @Test
    fun addingAProfileMakesItTheDefaultWhenThereIsNone() {
        val empty = AgentModelFile.Content("", emptyList())

        val content = AgentModelFile.withProfile(empty, profile("p1"))

        assertEquals("p1", content.activeId)
        assertEquals(1, content.profiles.size)
    }

    @Test
    fun savingTheSameIdReplacesTheProfile() {
        val content = AgentModelFile.withProfile(AgentModelFile.Content("", emptyList()), profile("p1"))

        val again = AgentModelFile.withProfile(content, profile("p1", "glm-4.5"))

        assertEquals(1, again.profiles.size)
        assertEquals("glm-4.5", again.profiles[0].modelName)
    }

    @Test
    fun removingTheDefaultHandsItToAnotherProfile() {
        val content = AgentModelFile.Content("p1", listOf(profile("p1"), profile("p2")))

        val left = AgentModelFile.withoutProfile(content, "p1")

        assertEquals("p2", left?.activeId)
        assertEquals(1, left?.profiles?.size)
    }

    @Test
    fun removingTheLastProfileLeavesNoDefault() {
        val content = AgentModelFile.Content("p1", listOf(profile("p1")))

        val left = AgentModelFile.withoutProfile(content, "p1")

        assertEquals("", left?.activeId)
        assertTrue(left?.profiles?.isEmpty() == true)
    }

    @Test
    fun removingAProfileThatIsNotThereChangesNothing() {
        val content = AgentModelFile.Content("p1", listOf(profile("p1")))

        assertEquals(null, AgentModelFile.withoutProfile(content, "nope"))
    }

    @Test
    fun theDefaultCanBeMovedToAnotherProfile() {
        val content = AgentModelFile.Content("p1", listOf(profile("p1"), profile("p2")))

        val moved = AgentModelFile.withActive(content, "p2")

        assertEquals("p2", moved.activeId)
    }
}
