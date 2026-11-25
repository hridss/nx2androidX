package com.nx.android.agent.runtime

import com.nx.android.agent.AgentModelConfig
import com.nx.android.agent.AgentSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The assistant's model settings: the default profile of the many the player configured.
 *
 * The profiles themselves live in [AgentModelStore]; this is the face the engine sees.
 */
@Singleton
class AgentSettingsStore @Inject constructor(
    private val models: AgentModelStore,
) : AgentSettings {

    override val config: AgentModelConfig
        get() = models.active()?.toConfig() ?: AgentModelConfig("", "", "", "")

    /**
     * Writes a config: it lands in the profile that is the default one, or in a new profile when
     * there is none yet.
     */
    override fun update(config: AgentModelConfig) {
        val current = models.active()
        models.save(
            AgentModelProfile(
                id = current?.id ?: "p-${System.currentTimeMillis()}",
                providerId = config.providerId,
                label = current?.label.orEmpty(),
                baseUrl = config.baseUrl,
                modelName = config.modelName,
                apiKey = config.apiKey,
                // the list of models the endpoint offered stays: the endpoint did not change
                modelIds = current?.modelIds.orEmpty(),
            ),
        )
    }
}
