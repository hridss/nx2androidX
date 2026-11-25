package com.nx.android.domain.environment

import com.nx.android.data.Tar
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule of the environment's build: when the app brought another environment, the phone's one is built again.
 */
class EnvPayloadTest {

    @Test
    fun theSameStampIsNoBuild() {
        assertFalse(EnvPayload.stale("abc", "abc", true))
    }

    @Test
    fun anotherStampIsABuild() {
        assertTrue(EnvPayload.stale("def", "abc", true))
    }

    @Test
    fun anAppWithoutAnEnvironmentLeavesTheTreeAlone() {
        assertFalse(EnvPayload.stale("", "abc", true))
        assertFalse(EnvPayload.stale("", "", true))
    }

    @Test
    fun aPhoneWithoutAnEnvironmentIsNotARebuild() {
        // the first install is the guided setup's business, not the app's start
        assertFalse(EnvPayload.stale("def", "", false))
    }

    @Test
    fun aPhoneWithNoStampIsBuiltAgain() {
        // the phone's environment was built by an app that did not write a stamp: it is an old one
        assertTrue(EnvPayload.stale("def", "", true))
    }

    /**
     * A rebuild replaces the environment and the engine that came with it. The player's data is not in that
     * list: the assistant's models and its conversations stay.
     */
    @Test
    fun aRebuildTakesTheEnvironmentAndTheEngine() {
        val dir = Files.createTempDirectory("nx2a-env").toFile()
        val rootfs = File(dir, "rootfs")
        val tool = File(dir, "tool")
        val agent = File(dir, "agent")
        val agentRuntime = File(agent, "runtime")

        val replaced = EnvPayload.replaced(rootfs, tool, agentRuntime)

        assertTrue("the rootfs goes", replaced.contains(rootfs))
        assertTrue("the tool tree goes", replaced.contains(tool))
        assertTrue("the engine goes with the environment", replaced.contains(agentRuntime))
        assertFalse("the assistant's own folder stays", replaced.contains(agent))
    }

    /**
     * The archive's trees land where they belong: the engine in the assistant's folder, the environment in
     * its own.
     */
    @Test
    fun theEngineLandsInTheAssistantsFolder() {
        val dir = Files.createTempDirectory("nx2a-env").toFile()
        val rootfs = File(dir, "rootfs")
        val tool = File(dir, "tool")
        val agent = File(dir, "agent")

        val roots = EnvPayload.roots(rootfs, tool, agent)

        assertEquals(rootfs, roots["rootfs"])
        assertEquals(tool, roots["tool"])
        assertEquals(agent, roots["agent"])
    }

    /**
     * The rebuild as it happens: the engine is the new one, the assistant's own data is still there.
     */
    @Test
    fun aRebuildReplacesTheEngineAndKeepsTheAssistantsData() {
        val dir = Files.createTempDirectory("nx2a-env").toFile()
        val rootfs = File(dir, "rootfs")
        val tool = File(dir, "tool")
        val agent = File(dir, "agent")
        val agentRuntime = File(agent, "runtime")
        // the phone's state: an old engine, and the player's data beside it
        agentRuntime.mkdirs()
        File(agentRuntime, "pi").writeText("the old engine\n")
        File(agent, "models.json").writeText("{}\n")
        File(agent, "workspace/memory.md").let { it.parentFile.mkdirs(); it.writeText("notes\n") }

        // the archive the app brings
        val tar = archive("agent/runtime/pi" to "the new engine\n")
        for (f in EnvPayload.replaced(rootfs, tool, agentRuntime)) f.deleteRecursively()
        Tar.unpack(tar.inputStream(), EnvPayload.roots(rootfs, tool, agent))

        assertEquals("the new engine\n", File(agentRuntime, "pi").readText())
        assertEquals("{}\n", File(agent, "models.json").readText())
        assertEquals("notes\n", File(agent, "workspace/memory.md").readText())
    }

    /** A small ustar archive: the files it holds, in the order they are given */
    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val data = content.toByteArray()
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, 0, 0, minOf(name.length, 100))
            "0000644\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            ("%011o\u0000".format(data.size)).toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            header[156] = '0'.code.toByte()
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0
            for (b in header) sum += b.toInt() and 0xff
            ("%06o\u0000 ".format(sum)).toByteArray().copyInto(header, 148)
            out.write(header)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))   // the end of the archive
        return out.toByteArray()
    }
}
