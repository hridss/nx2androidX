package com.nx.android.agent.env

import android.content.Context
import android.util.Log
import com.nx.android.agent.runtime.AgentEngineStore
import com.nx.android.agent.runtime.AgentSettingsStore
import com.nx.android.agent.runtime.AgentWorkspace
import com.nx.android.domain.environment.GuestProcessLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The env agent: pi — a full coding agent (read/write/edit/bash, ADR-009) living in the glibc
 * environment the porter runs in. This is the strong tier; the in-app ReAct runtime stays as the
 * light tier. One headless turn per call (`pi -p`): the panel's streaming RPC client is the next
 * step, this is the wiring proof.
 *
 * The work directory is the app's data root (NX2A_HOME): the AGENTS.md seeded there carries the
 * domain knowledge — where the packs live, which log carries a crash's backtrace, the read-only
 * discipline.
 */
@Singleton
class PiRunner @Inject constructor(
    private val guest: GuestProcessLauncher,
    private val proxy: GuestProxy,
    private val settings: AgentSettingsStore,
    private val engine: AgentEngineStore,
    private val workspace: AgentWorkspace,
) {

    private companion object {
        const val TAG = "nx2a-agent-pi"
    }

    /** One headless pi turn; the answer's text, or a failure the caller shows verbatim. */
    suspend fun run(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val base = guest.guestEnv()
        workspace.ensure()
        val nx2a = File(requireNotNull(base["NX2A_HOME"]) { "NX2A_HOME is not set in the guest env" })
        val binary = engine.binary
        if (!binary.isFile) {
            return@withContext Result.failure(IllegalStateException("pi 没有安装（nx2a/agent/runtime/pi 不存在）"))
        }
        val config = settings.config
        if (!config.configured) {
            return@withContext Result.failure(IllegalStateException("模型还没有配置"))
        }

        val proxyPort = proxy.ensure()
        // the providers pi does not know by itself (glm through bigmodel.cn, doubao, an endpoint
        // of the player's own) are declared in the engine's models.json — the same file the chat
        // writes, so the two entry points cannot drift apart
        PiCatalog.modelsJson(config)?.let { json ->
            val piHome = File(engine.home, ".pi/agent")
            piHome.mkdirs()
            File(piHome, "models.json").writeText(json)
        }
        val pb = ProcessBuilder(listOf(binary.absolutePath, "-p", "--model", PiCatalog.fullModelId(config), prompt))
            .directory(nx2a)
        pb.redirectErrorStream(true)
        // stdin is /dev/null: an open pipe makes pi wait for an EOF that never comes
        // (0 cpu, no sockets, no session — verified once on device the hard way)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.environment().putAll(base)
        // the engine's home is the assistant's own folder: its models.json and its sessions stay
        // there, and the environment's reset does not touch them
        pb.environment()["HOME"] = engine.home.absolutePath
        pb.environment()["http_proxy"] = "http://127.0.0.1:$proxyPort"
        pb.environment()["https_proxy"] = pb.environment()["http_proxy"]
        pb.environment()["TERM"] = "dumb"
        val (keyName, keyValue) = PiCatalog.keyEnv(config.providerId, config.apiKey)
        pb.environment()[keyName] = keyValue

        try {
            val process = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                // 管道被拆的时候（超时结束进程）读会抛 IO 异常：那是正常收场，不是错误。
                // 让它逃出去就是 App 进程自杀。
                try {
                    process.inputStream.use { stream ->
                        val buf = CharArray(4_096)
                        val r = stream.reader(Charsets.UTF_8)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (out.length < 100_000) out.appendRange(buf, 0, n)
                        }
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "pi stdout closed: ${e.message}")
                }
            }
            reader.start()
            val finished = process.waitFor(300, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@withContext Result.failure(IllegalStateException("超时：pi 跑了 5 分钟没有结束"))
            }
            reader.join(2_000)
            val text = out.toString().trim()
            if (process.exitValue() != 0) {
                return@withContext Result.failure(IllegalStateException("退出码 ${process.exitValue()}：${text.takeLast(400)}"))
            }
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
