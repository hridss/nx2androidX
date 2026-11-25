package com.nx.android.agent.runtime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The extensions the installed package carries, and the engine settings that point at them: the
 * engine finds them only when it is told where they are.
 */
class AgentExtensionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun extension(name: String): File {
        val dir = File(tmp.root, "runtime/extensions/$name")
        dir.mkdirs()
        File(dir, "index.js").writeText("export default 1;")
        return dir
    }

    @Test
    fun `the paths are the extensions that are really there`() {
        extension("pi-subagents")

        val paths = AgentExtensions.paths(File(tmp.root, "runtime"), listOf("pi-subagents", "absent"))

        assertEquals(1, paths.size)
        assertTrue(paths[0].endsWith("runtime/extensions/pi-subagents/index.js"))
    }

    @Test
    fun `an extension that is built into a folder of its own is found there`() {
        // the packaged extension keeps its sources and the built one side by side
        val dir = File(tmp.root, "runtime/extensions/pi-subagents/dist")
        dir.mkdirs()
        File(dir, "index.js").writeText("export default 1;")

        val paths = AgentExtensions.paths(File(tmp.root, "runtime"), listOf("pi-subagents"))

        assertEquals(1, paths.size)
        assertTrue(paths[0].endsWith("runtime/extensions/pi-subagents/dist/index.js"))
    }

    @Test
    fun `the settings keep what the engine already wrote`() {
        val settings = AgentExtensions.settingsWith("""{"packages":["npm:foo"]}""", listOf("/a/index.js"))

        val json = JSONObject(settings)
        assertEquals("/a/index.js", json.getJSONArray("extensions").getString(0))
        assertEquals("npm:foo", json.getJSONArray("packages").getString(0))
    }

    @Test
    fun `the settings replace the extensions of the last time`() {
        val once = AgentExtensions.settingsWith("", listOf("/a/index.js"))
        val twice = AgentExtensions.settingsWith(once, listOf("/b/index.js"))

        val array = JSONObject(twice).getJSONArray("extensions")
        assertEquals(1, array.length())
        assertEquals("/b/index.js", array.getString(0))
    }

    @Test
    fun `the engine is pointed at the extensions of the package`() {
        extension("pi-subagents")
        val home = File(tmp.root, "agent")

        AgentExtensions.enable(home, File(tmp.root, "runtime"), listOf("pi-subagents"))

        val settings = AgentExtensions.settingsFile(home)
        assertTrue(settings.isFile)
        val array = JSONObject(settings.readText()).getJSONArray("extensions")
        assertEquals(1, array.length())
        assertTrue(array.getString(0).endsWith("extensions/pi-subagents/index.js"))
    }

    @Test
    fun `a package without extensions leaves the engine alone`() {
        val home = File(tmp.root, "agent")

        AgentExtensions.enable(home, File(tmp.root, "runtime"), emptyList())

        assertFalse("no settings file is written for an engine without extensions",
            AgentExtensions.settingsFile(home).exists())
    }
}
