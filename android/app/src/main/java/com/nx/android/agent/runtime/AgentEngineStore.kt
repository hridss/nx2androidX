package com.nx.android.agent.runtime

import com.nx.android.core.storage.AgentLayout
import com.nx.android.core.storage.AppPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * The engine's own place: the binary, the home it runs under, and the manifest that says which
 * engine it is.
 *
 * It sits inside the player's nx2android folder, beside the imported profiles and the build
 * products: the environment's rebuild throws away the rootfs, the tool tree and the engine itself,
 * and nothing else, so the engine comes back with the environment while the home, the models and
 * the conversations stay.
 */
@Singleton
class AgentEngineStore @Inject constructor(
    private val paths: AppPaths,
) {

    /** The assistant's home: the engine runs with HOME pointed here, so its own tree lands in `<home>/.pi`. */
    val home: File get() = paths.agent

    /** Where the engine binary and its manifest live. */
    val runtimeDir: File get() = paths.agentRuntime

    /** The engine binary. */
    val binary: File get() = File(runtimeDir, AgentLayout.BINARY)

    /** The manifest of the engine that is in: what it is, and what it needs. */
    val manifest: File get() = File(runtimeDir, AgentLayout.MANIFEST)

    /** Whether the engine is there. */
    val installed: Boolean get() = binary.isFile

    /** The version of the engine that is in (empty when there is none). */
    fun installedVersion(): String {
        val text = manifest.takeIf { it.isFile }?.readText().orEmpty()
        return runCatching { JSONObject(text).optString("version").trim() }.getOrDefault("")
    }

    /** The extensions the engine carries (empty when it carries none). */
    fun installedExtensions(): List<String> {
        val text = manifest.takeIf { it.isFile }?.readText().orEmpty()
        return runCatching { JSONObject(text).optJSONArray("extensions") }.getOrNull()?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optString(i).trim().takeIf { it.isNotEmpty() } }
        } ?: emptyList()
    }
}
