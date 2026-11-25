package com.nx.android.data

import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.domain.environment.GuestProcessLauncher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导入档案包：跑完了只说一次，带上它自己的退出码。
 *
 * 这个回调以前会被叫两次（一次带真正的退出码，一次带 0）：玩家的屏幕上弹两次提示，失败的导入还会被说成成功。
 */
class PortRunnerImportTest {

    /** 导入一次，回收它说过的每一句（退出码） */
    private fun import(code: Int, lines: String = ""): List<Int> {
        val launcher = object : GuestProcessLauncher {
            override fun guestEnv() = mutableMapOf<String, String>()
            override fun guest(args: List<String>, extra: Map<String, String>): Process =
                object : Process() {
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = ByteArrayInputStream(lines.toByteArray())
                    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                    override fun waitFor() = code
                    override fun exitValue() = code
                    override fun destroy() = Unit
                }
        }
        val told = mutableListOf<Int>()
        val runner = PortRunner(NullEnvironment(), launcher, { _, _ -> "line" }) { }
        runner.importProfiles("/files/profiles.zip") { told.add(it) }.join()
        return told
    }

    @Test
    fun `the import says once how it went`() {
        assertEquals(listOf(0), import(0))
    }

    @Test
    fun `a failed import says so, once`() {
        assertEquals(listOf(3), import(3))
    }

    @Test
    fun `the porter's own lines reach the caller`() {
        val said = mutableListOf<String>()
        val launcher = object : GuestProcessLauncher {
            override fun guestEnv() = mutableMapOf<String, String>()
            override fun guest(args: List<String>, extra: Map<String, String>): Process =
                object : Process() {
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = ByteArrayInputStream("imported 183 profile(s)\n".toByteArray())
                    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                    override fun waitFor() = 0
                    override fun exitValue() = 0
                    override fun destroy() = Unit
                }
        }
        val runner = PortRunner(NullEnvironment(), launcher, { _, _ -> "line" }) { said.add(it) }
        runner.importProfiles("/files/profiles.zip") { }.join()
        assertEquals(listOf("imported 183 profile(s)"), said)
    }

    /** 环境这一层与导入无关：这里只跑客机程序 */
    private class NullEnvironment : EnvironmentSetup {
        override val ready = true
        override val lastCheck = ""
        override fun needsRebuild() = false
        override fun rebuild(onProgress: (Int) -> Unit) {}
        override fun patchInterpreters() {}
    }
}
