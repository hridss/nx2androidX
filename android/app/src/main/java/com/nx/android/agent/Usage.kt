package com.nx.android.agent

import org.json.JSONObject

/**
 * 一份用量：token 的四个分项与花了多少钱。
 *
 * 数字全是引擎写的——它给每一条回答记一份 `usage`，App 只做加法，不自己估。
 */
data class AgentTokenUsage(
    /** 送进去的（没命中缓存的那部分）。 */
    val input: Long = 0L,
    /** 生成出来的。 */
    val output: Long = 0L,
    /** 从缓存里读回来的。 */
    val cacheRead: Long = 0L,
    /** 写进缓存的。 */
    val cacheWrite: Long = 0L,
    /** 花了多少钱（美元）。没有价目的模型那里是 0。 */
    val cost: Double = 0.0,
) {
    /** 四个分项加起来：token 的总数。 */
    val total: Long get() = input + output + cacheRead + cacheWrite

    /** 什么也没花。 */
    val empty: Boolean get() = total == 0L && cost == 0.0

    /** 两份加起来——App 唯一做的算术。 */
    operator fun plus(other: AgentTokenUsage) = AgentTokenUsage(
        input = input + other.input,
        output = output + other.output,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite = cacheWrite + other.cacheWrite,
        cost = cost + other.cost,
    )
}

/**
 * 引擎给一条回答记的用量：四个分项与成本。
 *
 * 成本在 `cost.total` 里（引擎按各家的价目算的）；没有价目的模型那里是 0。
 */
fun tokenUsageOf(usage: JSONObject?): AgentTokenUsage {
    if (usage == null) return AgentTokenUsage()
    return AgentTokenUsage(
        input = usage.optLong("input", 0L),
        output = usage.optLong("output", 0L),
        cacheRead = usage.optLong("cacheRead", 0L),
        cacheWrite = usage.optLong("cacheWrite", 0L),
        cost = usage.optJSONObject("cost")?.optDouble("total", 0.0) ?: 0.0,
    )
}

/**
 * 引擎报的那本账（`get_session_stats` 的 `data`）：全会话聚合的用量与成本。
 *
 * 那是引擎自己记的账，含已被压缩掉的历史——App 重开一次也不会少算。
 */
fun sessionUsageOf(data: JSONObject?): AgentTokenUsage {
    if (data == null) return AgentTokenUsage()
    val tokens = data.optJSONObject("tokens")
    return AgentTokenUsage(
        input = tokens?.optLong("input", 0L) ?: 0L,
        output = tokens?.optLong("output", 0L) ?: 0L,
        cacheRead = tokens?.optLong("cacheRead", 0L) ?: 0L,
        cacheWrite = tokens?.optLong("cacheWrite", 0L) ?: 0L,
        cost = data.optDouble("cost", 0.0),
    )
}

/**
 * 一次运行自己的那些回答的用量：它们各自的 `usage` 加起来。
 *
 * 只有回答带用量——工具结果与用户消息不带。
 */
fun usageOfMessages(messages: List<JSONObject>): AgentTokenUsage =
    messages.fold(AgentTokenUsage()) { acc, message ->
        if (message.optString("role") != "assistant") acc
        else acc + tokenUsageOf(message.optJSONObject("usage"))
    }

/**
 * 正在跑的那一次到目前为止的用量：尾巴里「最后一条 user 消息之后」的那些回答。
 *
 * 尾巴只有最后一段（最后 200 条 / 128 KB），所以这是它已经写下那部分的量——跑完再读一次
 * 就是全的。
 */
fun usageOfRunning(messages: List<JSONObject>): AgentTokenUsage {
    var start = 0
    for (index in messages.indices) {
        if (messages[index].optString("role") == "user") start = index + 1
    }
    return usageOfMessages(messages.drop(start))
}
