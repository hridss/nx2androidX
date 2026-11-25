package com.nx.android.agent.env

import android.util.Log
import com.nx.android.agent.runtime.AgentEngineStore
import com.nx.android.agent.runtime.AgentWorkspace
import com.nx.android.domain.environment.GuestProcessLauncher
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 引擎目录里的模型清单：跑一次 `pi --list-models <供应商>`，把表格读回来。
 *
 * 引擎认识哪家，端点与模型清单就是它的事——App 不再自己维护一份。清单是按凭证过滤的：
 * 注入哪家的密钥，就列哪家的模型。
 */
@Singleton
class PiModelList @Inject constructor(
    private val guest: GuestProcessLauncher,
    private val proxy: GuestProxy,
    private val engine: AgentEngineStore,
    private val workspace: AgentWorkspace,
) {

    /** 一次拉到的清单。 */
    data class Listing(
        val models: List<PiModel>,
        val millis: Long,
    )

    /** 清单里的一行：名字，加上引擎顺带报的元数据。 */
    data class PiModel(
        val id: String,
        val contextWindow: Long,
        val reasoning: Boolean,
    )

    /** 问引擎要这家供应商的模型清单。 */
    suspend fun list(providerId: String, apiKey: String): Result<Listing> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val base = guest.guestEnv()
            workspace.ensure()
            val nx2a = File(requireNotNull(base["NX2A_HOME"]) { "NX2A_HOME is not set in the guest env" })
            val binary = engine.binary
            if (!binary.isFile) {
                return@withContext Result.failure(IllegalStateException("pi 没有安装（nx2a/agent/runtime/pi 不存在）"))
            }
            val pattern = PiCatalog.providerKey(providerId)
            val pb = ProcessBuilder(listOf(binary.absolutePath, "--list-models", pattern))
                .directory(nx2a)
            pb.redirectErrorStream(true)
            // stdin 是 /dev/null：不关的话 pi 会等一个永远不会来的 EOF
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            pb.environment().putAll(base)
            pb.environment()["HOME"] = engine.home.absolutePath
            val port = proxy.ensure()
            pb.environment()["http_proxy"] = "http://127.0.0.1:$port"
            pb.environment()["https_proxy"] = pb.environment()["http_proxy"]
            pb.environment()["TERM"] = "dumb"
            val (keyName, keyValue) = PiCatalog.keyEnv(providerId, apiKey)
            pb.environment()[keyName] = keyValue

            val process = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                // 管道被拆的时候（超时结束进程）读会抛 IO 异常：正常收场，不能让它逃出去
                try {
                    process.inputStream.use { stream ->
                        stream.reader(Charsets.UTF_8).use { r ->
                            val buf = CharArray(4_096)
                            while (true) {
                                val n = r.read(buf)
                                if (n < 0) break
                                if (out.length < 200_000) out.appendRange(buf, 0, n)
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "pi stdout closed: ${e.message}")
                }
            }
            reader.start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@withContext Result.failure(IllegalStateException("超时：pi 跑了 $TIMEOUT_SECONDS 秒没有结束"))
            }
            reader.join(2_000)
            val text = out.toString()
            if (process.exitValue() != 0) {
                return@withContext Result.failure(
                    IllegalStateException("退出码 ${process.exitValue()}：${text.takeLast(400)}"),
                )
            }
            val models = parse(text, pattern)
            if (models.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("引擎目录里没有这家供应商的模型：${text.takeLast(200)}"),
                )
            }
            Result.success(Listing(models, System.currentTimeMillis() - started))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {

        private const val TAG = "nx2a-agent-models"
        private const val TIMEOUT_SECONDS = 60L

        /**
         * 表格的读法：列与列之间隔着至少两个空格；第一行是表头；只留这一家的行。
         *
         * 读不出的行跳过——stderr 混进来的告警、目录刷新的提示，都不该让清单作废。
         */
        fun parse(text: String, providerKey: String): List<PiModel> {
            val models = mutableListOf<PiModel>()
            for (line in text.lines()) {
                val cols = line.trim().split(Regex("\\s{2,}"))
                if (cols.size < 3) continue
                if (cols[0] != providerKey) continue
                val id = cols[1].trim()
                if (id.isEmpty() || id == "model") continue
                models += PiModel(
                    id = id,
                    contextWindow = parseTokens(cols[2]),
                    reasoning = cols.getOrElse(4) { "" }.trim() == "yes",
                )
            }
            return models
        }

        /** "200K" / "1.5M" / "131072" -> token 数；读不出算 0。 */
        fun parseTokens(text: String): Long {
            val m = Regex("([0-9.]+)\\s*([KM])?").find(text.trim().uppercase()) ?: return 0
            val n = m.groupValues[1].toDoubleOrNull() ?: return 0
            return when (m.groupValues[2]) {
                "K" -> (n * 1_000).toLong()
                "M" -> (n * 1_000_000).toLong()
                else -> n.toLong()
            }
        }
    }
}
