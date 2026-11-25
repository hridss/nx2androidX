package com.nx.android.agent

import com.nx.android.agent.env.PiCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A model the player configured: what makes it ready to run, and what the presets carry.
 */
class AgentModelConfigTest {

    private fun config(
        providerId: String,
        baseUrl: String = "https://example.test/v1",
        apiKey: String = "sk-test",
        modelName: String = "some-model",
    ) = AgentModelConfig(providerId, baseUrl, apiKey, modelName)

    @Test
    fun aModelWithEverythingIsReady() {
        assertTrue(config("custom").configured)
    }

    @Test
    fun aModelWithoutAKeyIsNotReady() {
        assertFalse(config("custom", apiKey = "").configured)
    }

    @Test
    fun aModelWithoutAModelNameIsNotReady() {
        assertFalse(config("custom", modelName = "").configured)
    }

    @Test
    fun aModelWithoutAnEndpointIsNotReady() {
        assertFalse(config("custom", baseUrl = "").configured)
    }

    @Test
    fun aProviderTheEngineCarriesNeedsNoEndpoint() {
        // 引擎自己知道端点在哪：没有地址也不妨碍它跑
        assertTrue(config("kimi", baseUrl = "").configured)
        assertTrue(config("deepseek", baseUrl = "").configured)
        assertTrue(config("minimax", baseUrl = "").configured)
    }

    @Test
    fun thePresetsTheEngineCarriesBringNoEndpoint() {
        for (id in listOf("minimax", "deepseek", "kimi")) {
            val preset = AgentPresets.byId(id)
            assertTrue(PiCatalog.engineManaged(id))
            assertEquals("", preset.baseUrl)
            assertEquals("", preset.defaultModel)
        }
    }

    @Test
    fun thePresetsWeDescribeBringTheirEndpoint() {
        val glm = AgentPresets.byId("glm")
        assertEquals("https://open.bigmodel.cn/api/paas/v4", glm.baseUrl)
        assertFalse(PiCatalog.engineManaged("glm"))

        val doubao = AgentPresets.byId("doubao")
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", doubao.baseUrl)
    }
}
