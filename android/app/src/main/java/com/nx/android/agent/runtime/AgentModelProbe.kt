package com.nx.android.agent.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 端点探针：问端点两件事——它有哪些模型、这把密钥通不通。
 *
 * 探针在 App 侧发请求：App 进程有网络，而引擎在 guest 里（那边没有 DNS，得靠 App 的代理）。
 * 地址、解析与归类都是纯 JVM 逻辑，不依赖 Android，单元测试直接驱动。
 */
object AgentModelProbe {

    /** 探的结果：通了（带耗时），还是不通（带原因）。 */
    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T, val millis: Long) : Outcome<T>
        data class Failed(val why: Why, val detail: String = "") : Outcome<Nothing>
    }

    /** 不通的原因。 */
    enum class Why {
        /** 密钥无效或没权限。 */
        BadKey,

        /** 端点上没有这个接口。 */
        NoSuchApi,

        /** 端点不认这个请求。 */
        NotAccepted,

        /** 这个模型在端点上不可用。 */
        ModelUnavailable,

        /** 端点自己出错了。 */
        ServerSide,

        /** 网络不可达。 */
        Network,

        /** 明文的 http 被系统拦下了：这个端点得用 https。 */
        CleartextBlocked,

        /** 答了，但读不出模型清单。 */
        Unreadable,

        /** 答了个看不懂的东西。 */
        Unexpected,
    }

    /** 拉到的清单。 */
    data class Catalog(val ids: List<String>)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MODEL_LIMIT = 200

    /** 清单地址：接口地址去掉尾斜杠，再拼 /models。 */
    fun modelsUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/models"

    /** 对话地址：接口地址去掉尾斜杠，再拼 /chat/completions。 */
    fun chatUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /**
     * 清单里的模型名：先找 data 数组，再找 models 数组；元素取 id，没有 id 就取 name。
     *
     * null = 这不是一份清单（读不出）；空清单 = 端点说自己没有模型。
     */
    fun parseModels(text: String, limit: Int = MODEL_LIMIT): List<String>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return null
        val ids = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val id = when (val item = array.opt(i)) {
                is JSONObject -> item.optString("id").trim().ifEmpty { item.optString("name").trim() }
                is String -> item.trim()
                else -> ""
            }
            if (id.isNotEmpty()) ids.add(id)
            if (ids.size >= limit) break
        }
        return ids.toList()
    }

    /** 状态码归类。 */
    fun whyFor(code: Int): Why = when (code) {
        401, 403 -> Why.BadKey
        404 -> Why.NoSuchApi
        400 -> Why.NotAccepted
        in 500..599 -> Why.ServerSide
        else -> Why.Unexpected
    }

    /** 拉清单：通了给模型名，不通给原因。 */
    fun models(baseUrl: String, apiKey: String): Outcome<Catalog> {
        val answer = call(modelsUrl(baseUrl), apiKey, "GET", null)
        return when (answer) {
            is Outcome.Failed -> answer
            is Outcome.Ok -> {
                val ids = parseModels(answer.value.body)
                if (ids == null) Outcome.Failed(Why.Unreadable) else Outcome.Ok(Catalog(ids), answer.millis)
            }
        }
    }

    /** 深测：对端点发一条最小的对话，答了就算通。 */
    fun test(baseUrl: String, apiKey: String, model: String): Outcome<Unit> {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .put("max_tokens", 1)
            .put("stream", false)
            .toString()
        val answer = call(chatUrl(baseUrl), apiKey, "POST", body)
        return when (answer) {
            is Outcome.Ok -> Outcome.Ok(Unit, answer.millis)
            is Outcome.Failed ->
                // 端点认了请求、只是不认这个模型：说的是另一回事
                if (answer.why == Why.NotAccepted && answer.detail.contains("model", ignoreCase = true)) {
                    Outcome.Failed(Why.ModelUnavailable, answer.detail)
                } else {
                    answer
                }
        }
    }

    /** 一次请求的答复。 */
    private data class Answer(val code: Int, val body: String)

    /** 发一次请求。 */
    private fun call(url: String, apiKey: String, method: String, body: String?): Outcome<Answer> {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "nx2android")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            val millis = System.currentTimeMillis() - started
            if (code in 200..299) {
                Outcome.Ok(Answer(code, text), millis)
            } else {
                Outcome.Failed(whyFor(code), text.take(300))
            }
        } catch (e: SocketTimeoutException) {
            Outcome.Failed(Why.Network, e.message.orEmpty())
        } catch (e: UnknownHostException) {
            Outcome.Failed(Why.Network, e.message.orEmpty())
        } catch (e: IOException) {
            // 明文的 http 被系统拦下来：这不是网络不通，是端点得用 https
            val cleartext = e.message?.contains("Cleartext", ignoreCase = true) == true
            Outcome.Failed(if (cleartext) Why.CleartextBlocked else Why.Network, e.message.orEmpty())
        } finally {
            conn?.disconnect()
        }
    }
}
