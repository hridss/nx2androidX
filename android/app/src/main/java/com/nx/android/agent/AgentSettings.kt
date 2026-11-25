package com.nx.android.agent

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

data class AgentPreset(
    val id: String,
    val baseUrl: String,
    val defaultModel: String,
)

object AgentPresets {
    /**
     * The providers the player may choose.
     *
     * The three the engine knows by itself carry no endpoint here: the engine knows where they
     * live, and the player brings the key. The rest are ours to describe.
     *
     * The label the player reads is in the text tables (agent_provider_*), not here: the table
     * carries it in the player's language, and this file carries the wiring.
     */
    val ALL: List<AgentPreset> = listOf(
        AgentPreset("glm", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6"),
        AgentPreset("deepseek", "", ""),
        AgentPreset("doubao", "https://ark.cn-beijing.volces.com/api/v3", ""),
        AgentPreset("kimi", "", ""),
        AgentPreset("minimax", "", ""),
        AgentPreset("custom", "", ""),
    )

    fun byId(id: String): AgentPreset = ALL.firstOrNull { it.id == id } ?: ALL.last()
}

/**
 * One recommended provider: where the player goes for the key.
 *
 * The name the player reads is in the text tables (agent_provider_*), the same one the provider
 * picker uses; this carries the id and the place to go.
 */
data class AgentRecommendation(
    val providerId: String,
    val url: String,
)

object AgentRecommendations {
    /**
     * The providers the settings card points at, in the order the card shows them.
     *
     * The links are the ones we keep: the invitation page where there is one, the platform's own
     * page otherwise. A key is the player's own — the app never holds one.
     */
    val ALL: List<AgentRecommendation> = listOf(
        AgentRecommendation(
            "siliconflow",
            "https://cloud.siliconflow.cn/i/qHZqgDs5",
        ),
        AgentRecommendation(
            "glm",
            "https://www.bigmodel.cn/invite?icode=wJ2z%2BeJ7O%2FOl4%2FcpYI9%2F82czbXFgPRGIalpycrEwJ28%3D",
        ),
        AgentRecommendation(
            "qiniu",
            "https://s.qiniu.com/bYZZj2",
        ),
    )
}

interface AgentSettings {
    val config: AgentModelConfig
    fun update(config: AgentModelConfig)
}
