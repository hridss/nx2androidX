package com.nx.android.agent.env

import com.nx.android.agent.AgentModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's side of a model: the names it knows, the models.json it is given.
 *
 * A model is looked up by the pair (provider, id) — a name of our own would not be found.
 */
class PiCatalogTest {

    private fun config(providerId: String, modelName: String) = AgentModelConfig(
        providerId = providerId,
        baseUrl = "https://example.test/v1",
        apiKey = "sk-test",
        modelName = modelName,
    )

    @Test
    fun theProviderNamesAreTheOnesTheEngineKnows() {
        assertEquals("minimax-cn", PiCatalog.providerKey("minimax"))
        assertEquals("deepseek", PiCatalog.providerKey("deepseek"))
        assertEquals("moonshotai-cn", PiCatalog.providerKey("kimi"))
        assertEquals("zhipu", PiCatalog.providerKey("glm"))
        assertEquals("doubao", PiCatalog.providerKey("doubao"))
    }

    @Test
    fun theModelIdCarriesTheProviderName() {
        assertEquals("moonshotai-cn/kimi-latest", PiCatalog.fullModelId(config("kimi", "kimi-latest")))
        assertEquals("minimax-cn/MiniMax-M2.7", PiCatalog.fullModelId(config("minimax", "MiniMax-M2.7")))
        assertEquals("zhipu/glm-4.6", PiCatalog.fullModelId(config("glm", "glm-4.6")))
        assertEquals("doubao/doubao-seed", PiCatalog.fullModelId(config("doubao", "doubao-seed")))
    }

    @Test
    fun theProvidersTheEngineKnowsAreNotDeclared() {
        assertNull(PiCatalog.modelsJson(config("minimax", "MiniMax-M2.7")))
        assertNull(PiCatalog.modelsJson(config("deepseek", "deepseek-chat")))
        assertNull(PiCatalog.modelsJson(config("kimi", "kimi-latest")))
    }

    @Test
    fun theProvidersTheEngineKnowsAreTheOnesItCarries() {
        assertTrue(PiCatalog.engineManaged("minimax"))
        assertTrue(PiCatalog.engineManaged("deepseek"))
        assertTrue(PiCatalog.engineManaged("kimi"))
        // 这几家引擎目录里没有：端点与模型清单是我们的事
        assertFalse(PiCatalog.engineManaged("glm"))
        assertFalse(PiCatalog.engineManaged("doubao"))
        assertFalse(PiCatalog.engineManaged("custom"))
    }

    @Test
    fun theDeclaredProviderCarriesTheModelThePlayerPicked() {
        val json = PiCatalog.modelsJson(config("glm", "glm-4.5-air"))

        assertTrue(json!!.contains("\"id\": \"glm-4.5-air\""))
        // 写死的那几个模型不在了：玩家选哪个就是哪个
        assertFalse(json.contains("glm-4.6"))
    }

    @Test
    fun theDeclaredProviderIsOnTheEndpointThePlayerGave() {
        val json = PiCatalog.modelsJson(config("doubao", "doubao-seed"))

        assertTrue(json!!.contains("https://example.test/v1"))
        assertTrue(json.contains("\"api\": \"openai-completions\""))
    }

    @Test
    fun theDeclaredProviderAsksForMaxTokens() {
        val json = PiCatalog.modelsJson(config("doubao", "doubao-seed"))

        assertTrue(json!!.contains("\"maxTokensField\": \"max_tokens\""))
    }

    @Test
    fun theKeyTravelsAsAnEnvironmentName() {
        assertEquals("ZHIPU_API_KEY", PiCatalog.keyEnv("glm", "sk-x").first)
        assertEquals("MOONSHOT_API_KEY", PiCatalog.keyEnv("kimi", "sk-x").first)
        assertEquals("MINIMAX_CN_API_KEY", PiCatalog.keyEnv("minimax", "sk-x").first)
        assertEquals("DEEPSEEK_API_KEY", PiCatalog.keyEnv("deepseek", "sk-x").first)
        assertEquals("ARK_API_KEY", PiCatalog.keyEnv("doubao", "sk-x").first)
        assertEquals("OPENAI_API_KEY", PiCatalog.keyEnv("custom", "sk-x").first)
    }
}
