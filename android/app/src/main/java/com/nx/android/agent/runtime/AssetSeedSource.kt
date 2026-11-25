package com.nx.android.agent.runtime

import android.content.res.AssetManager
import android.util.Log
import java.io.IOException

/**
 * The material out of the App's own assets.
 *
 * The assets tree mirrors the player's nx2android folder, so the planter can copy it onto the
 * device without knowing what is in it. The placeholders in it are filled by the planter (it
 * knows the language the player reads).
 */
class AssetSeedSource(private val assets: AssetManager) : AgentSeedSource {

    override fun files(): List<String> {
        val found = mutableListOf<String>()
        collect(ROOT, "", found)
        if (found.isEmpty()) {
            // the material is not in the package at all: the assistant would work without its
            // agreement, its skills and its agent definitions
            Log.e(TAG, "the material is not in the package ($ROOT is empty)")
        }
        return found
    }

    override fun text(path: String): String? = try {
        assets.open("$ROOT/$path").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        // a material that is in the tree but not in the package: the packer left it out
        Log.e(TAG, "the material at $path cannot be read: ${e.message}")
        null
    }

    /**
     * Every file under a folder, recursively: the path in the assets, and the path relative
     * to the seed root (the one the planter writes to the device).
     */
    private fun collect(folder: String, prefix: String, into: MutableList<String>) {
        val entries = assets.list(folder) ?: return
        for (entry in entries) {
            val path = "$folder/$entry"
            val relative = if (prefix.isEmpty()) entry else "$prefix/$entry"
            val children = assets.list(path)
            if (children.isNullOrEmpty()) into += relative else collect(path, relative, into)
        }
    }

    private companion object {
        const val TAG = "nx2a-agent-seeds"

        /** The assets folder that mirrors the player's nx2android folder. */
        const val ROOT = "nx2a"
    }
}
