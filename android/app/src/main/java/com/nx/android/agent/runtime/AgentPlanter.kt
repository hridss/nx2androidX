package com.nx.android.agent.runtime

import com.nx.android.core.storage.AgentLayout
import java.io.File
import javax.inject.Singleton

/**
 * Planting the material: the App's prompt material onto the device, where the engine reads it.
 *
 * The material is a tree whose paths mirror the player's nx2android folder, so planting is a copy:
 * one rule for every file, and adding a skill is adding a file.
 *
 * The engine looks for the skills and the agent definitions in hidden folders. The packer drops
 * hidden folders from the assets, so they are written `dot-` there and go back to their hidden
 * names here.
 *
 * Free of Android, so the JVM tests drive it.
 */
@Singleton
class AgentPlanter(
    private val source: AgentSeedSource,
    private val root: File,
) {

    /** Plant the material: every piece of it, where the engine reads it. */
    fun plant() {
        for (path in source.files()) {
            val text = source.text(path) ?: continue
            AgentLayout.seedOwned(target(path), text)
        }
    }

    /** Where a piece of material goes: the mirror path, with the hidden folders restored. */
    private fun target(path: String): File {
        val segments = path.split("/").map { segment ->
            if (segment.startsWith(HIDDEN)) "." + segment.removePrefix(HIDDEN) else segment
        }
        return File(root, segments.joinToString("/"))
    }

    private companion object {
        /** How a hidden folder is written in the assets (the packer drops hidden folders). */
        const val HIDDEN = "dot-"
    }
}
