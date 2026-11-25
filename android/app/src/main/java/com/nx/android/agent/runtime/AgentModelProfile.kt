package com.nx.android.agent.runtime

import com.nx.android.agent.AgentModelConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * One model the player configured: a provider, an endpoint, a model name and a key.
 *
 * @param apiKey the key in plain text — it is only ever in memory; what is written down is a blob
 * @param modelIds the models the endpoint offered when the player last asked for them; it saves the
 *   player a second trip, and it is a list of names — nothing secret
 */
data class AgentModelProfile(
    val id: String,
    val providerId: String,
    val label: String,
    val baseUrl: String,
    val modelName: String,
    val apiKey: String,
    val modelIds: List<String> = emptyList(),
) {
    /** The config the engine works with. */
    fun toConfig(): AgentModelConfig = AgentModelConfig(providerId, baseUrl, apiKey, modelName)
}

/**
 * The models file: the profiles and which one answers now. The keys are written as blobs — the
 * plain text never reaches the file.
 *
 * Free of Android, so the JVM tests drive it.
 */
object AgentModelFile {

    /** What the file says. */
    data class Content(val activeId: String, val profiles: List<AgentModelProfile>)

    /** The file as it is written. */
    fun write(profiles: List<AgentModelProfile>, activeId: String, encrypt: (String) -> String): String {
        val array = JSONArray()
        for (p in profiles) {
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("providerId", p.providerId)
                    .put("label", p.label)
                    .put("baseUrl", p.baseUrl)
                    .put("modelName", p.modelName)
                    .put("apiKeyEnc", encrypt(p.apiKey))
                    .put("modelIds", JSONArray(p.modelIds)),
            )
        }
        return JSONObject()
            .put("schema", 1)
            .put("activeId", activeId)
            .put("profiles", array)
            .toString(2)
    }

    /** The file read back: the keys are opened with the given one. */
    fun read(text: String, decrypt: (String) -> String): Content {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return Content("", emptyList())
        val array = o.optJSONArray("profiles") ?: return Content(o.optString("activeId").trim(), emptyList())
        val out = mutableListOf<AgentModelProfile>()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val id = p.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                AgentModelProfile(
                    id = id,
                    providerId = p.optString("providerId").trim(),
                    label = p.optString("label").trim(),
                    baseUrl = p.optString("baseUrl").trim(),
                    modelName = p.optString("modelName").trim(),
                    apiKey = decrypt(p.optString("apiKeyEnc")),
                    modelIds = readModelIds(p.optJSONArray("modelIds")),
                ),
            )
        }
        return Content(o.optString("activeId").trim(), out)
    }

    /** The list of model names the file carries (empty when it carries none). */
    private fun readModelIds(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val id = array.optString(i).trim()
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    /** The content with a profile added, or with the one that carries the same id replaced. */
    fun withProfile(content: Content, profile: AgentModelProfile): Content {
        val profiles = content.profiles.toMutableList()
        val at = profiles.indexOfFirst { it.id == profile.id }
        if (at >= 0) profiles[at] = profile else profiles.add(profile)
        val active = if (content.activeId.isBlank()) profile.id else content.activeId
        return Content(active, profiles)
    }

    /** The content without a profile (null when it was not there). */
    fun withoutProfile(content: Content, id: String): Content? {
        val profiles = content.profiles.filterNot { it.id == id }
        if (profiles.size == content.profiles.size) return null
        // the default goes to another one, or to none
        val active = if (content.activeId == id) profiles.firstOrNull()?.id.orEmpty() else content.activeId
        return Content(active, profiles)
    }

    /** The content with another default (unchanged when the id is not there). */
    fun withActive(content: Content, id: String): Content {
        if (content.profiles.none { it.id == id }) return content
        return Content(id, content.profiles)
    }

    /** How many profiles the file says — the keys are not opened to count them. */
    fun count(text: String): Int {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return 0
        return o.optJSONArray("profiles")?.length() ?: 0
    }
}
