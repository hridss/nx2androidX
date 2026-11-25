package com.nx.android.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The models file: the list of models the endpoint offered travels with the profile.
 */
class AgentModelListFileTest {

    /** A stand-in for the keystore: the blob is the text, upside down. */
    private fun fakeEncrypt(text: String) = "enc:" + text.reversed()
    private fun fakeDecrypt(blob: String) = blob.removePrefix("enc:").reversed()

    private fun profile(id: String, models: List<String> = emptyList()) = AgentModelProfile(
        id = id,
        providerId = "glm",
        label = "GLM $id",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        modelName = "glm-4.6",
        apiKey = "sk-$id",
        modelIds = models,
    )

    @Test
    fun theListSurvivesTheRoundTrip() {
        val text = AgentModelFile.write(listOf(profile("p1", listOf("glm-4.6", "glm-4.5"))), "p1", ::fakeEncrypt)

        val content = AgentModelFile.read(text, ::fakeDecrypt)

        assertEquals(listOf("glm-4.6", "glm-4.5"), content.profiles[0].modelIds)
    }

    @Test
    fun aProfileWithoutAListIsReadWithNone() {
        val text = AgentModelFile.write(listOf(profile("p1")), "p1", ::fakeEncrypt)

        val content = AgentModelFile.read(text, ::fakeDecrypt)

        assertTrue(content.profiles[0].modelIds.isEmpty())
    }

    @Test
    fun aFileWrittenBeforeTheListExistedIsStillRead() {
        val old = """
            {
              "schema": 1,
              "activeId": "p1",
              "profiles": [
                {
                  "id": "p1",
                  "providerId": "glm",
                  "label": "GLM",
                  "baseUrl": "https://open.bigmodel.cn/api/paas/v4",
                  "modelName": "glm-4.6",
                  "apiKeyEnc": "enc:sk-p1"
                }
              ]
            }
        """.trimIndent()

        val content = AgentModelFile.read(old, ::fakeDecrypt)

        assertEquals(1, content.profiles.size)
        assertEquals("glm-4.6", content.profiles[0].modelName)
        assertTrue(content.profiles[0].modelIds.isEmpty())
    }

    @Test
    fun theListIsNotSecretAndTheKeyStillIs() {
        val text = AgentModelFile.write(listOf(profile("p1", listOf("glm-4.6"))), "p1", ::fakeEncrypt)

        assertTrue(text.contains("glm-4.6"))
        assertFalse(text.contains("sk-p1"))
    }
}
