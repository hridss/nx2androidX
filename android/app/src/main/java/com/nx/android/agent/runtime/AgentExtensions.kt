package com.nx.android.agent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The extensions the installed engine package carries, and the engine's own settings that point at
 * them.
 *
 * The package keeps its extensions beside the engine, not in the engine's home: pi does not find
 * them on its own. The engine reads a settings file in its home, and this writes that file — the
 * app decides what the engine loads, the player does not.
 *
 * Free of Android, so the JVM tests drive it.
 */
object AgentExtensions {

    /** The engine's own settings file, in the place it reads it from. */
    fun settingsFile(home: File): File = File(home, ".pi/agent/settings.json")

    /**
     * The paths of the extensions that are really there: a package may list one that its own
     * archive did not carry, and a path that does not exist would keep the engine from loading
     * anything at all.
     */
    fun paths(runtimeDir: File, names: List<String>): List<String> = names.mapNotNull { name ->
        entryPoint(File(runtimeDir, "extensions/$name"))?.absolutePath
    }

    /**
     * The extension's own entry point: the folder's index.js, or the one inside the folder it is
     * built into (a packaged extension keeps its sources and its dist/ side by side).
     */
    private fun entryPoint(folder: File): File? {
        val direct = File(folder, "index.js")
        if (direct.isFile) return direct
        if (!folder.isDirectory) return null
        return folder.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.name == "index.js" }
            .minByOrNull { it.path.length }
    }

    /**
     * The settings as they should read: the extensions we manage, and everything else the file
     * already said (the engine writes its own keys there too).
     */
    fun settingsWith(existing: String, paths: List<String>): String {
        val root = runCatching { JSONObject(existing) }.getOrNull() ?: JSONObject()
        val array = JSONArray()
        paths.forEach { array.put(it) }
        root.put("extensions", array)
        return root.toString(2)
    }

    /**
     * Points the engine at the extensions the installed package carries.
     *
     * Nothing is written when the package carries none: an engine without extensions is the one
     * that was there before, and a settings file that says so is worse than no file at all.
     */
    fun enable(home: File, runtimeDir: File, names: List<String>) {
        val paths = paths(runtimeDir, names)
        if (paths.isEmpty()) return
        val file = settingsFile(home)
        file.parentFile?.mkdirs()
        file.writeText(settingsWith(file.takeIf { it.isFile }?.readText().orEmpty(), paths))
    }
}
