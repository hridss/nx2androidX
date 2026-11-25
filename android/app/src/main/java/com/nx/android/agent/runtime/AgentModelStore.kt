package com.nx.android.agent.runtime

import android.util.Log
import com.nx.android.core.storage.AppPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The models the player configured: several of them, one of them the default.
 *
 * They live in the assistant's own folder, so the environment's reset does not take them away, and
 * their keys are written as blobs — the plain text never reaches the file.
 */
@Singleton
class AgentModelStore @Inject constructor(
    private val paths: AppPaths,
    private val secrets: AgentSecrets,
) {

    /** Where the profiles are written. */
    private val file: File get() = paths.agentModels

    /** The profiles, in the order they were added. */
    fun list(): List<AgentModelProfile> = read().profiles

    /** How many profiles there are (the keys are not opened to count them). */
    fun count(): Int {
        val text = file.takeIf { it.isFile }?.readText().orEmpty()
        return AgentModelFile.count(text)
    }

    /** Which profile answers now (the first one when none is marked). */
    fun active(): AgentModelProfile? {
        val content = read()
        return content.profiles.firstOrNull { it.id == content.activeId } ?: content.profiles.firstOrNull()
    }

    /** The id of the profile that answers now (empty when there is none). */
    fun activeId(): String = active()?.id.orEmpty()

    /** Adds a profile, or replaces the one that carries the same id. */
    fun save(profile: AgentModelProfile): Result<AgentModelProfile> = runCatching {
        write(AgentModelFile.withProfile(read(), profile))
        profile
    }.onFailure {
        Log.e(TAG, "the model could not be saved: ${it.message}")
    }

    /** Removes a profile: the default goes to another one, or to none. */
    fun remove(id: String): Result<Boolean> = runCatching {
        val content = AgentModelFile.withoutProfile(read(), id) ?: return@runCatching false
        write(content)
        true
    }.onFailure {
        Log.e(TAG, "the model could not be removed: ${it.message}")
    }

    /** Makes a profile the default one. */
    fun setActive(id: String): Result<Unit> = runCatching {
        write(AgentModelFile.withActive(read(), id))
    }.onFailure {
        Log.e(TAG, "the default model could not be changed: ${it.message}")
    }

    private fun read(): AgentModelFile.Content {
        val text = file.takeIf { it.isFile }?.readText().orEmpty()
        return AgentModelFile.read(text, secrets::decrypt)
    }

    private fun write(content: AgentModelFile.Content) {
        file.parentFile?.mkdirs()
        file.writeText(AgentModelFile.write(content.profiles, content.activeId, secrets::encrypt))
    }

    private companion object {
        const val TAG = "Nx2aAgentModels"
    }
}
