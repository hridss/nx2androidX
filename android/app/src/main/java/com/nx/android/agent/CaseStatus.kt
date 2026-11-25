package com.nx.android.agent

import org.json.JSONObject

/**
 * 案例状态：pi agent 自己发的那一条。
 *
 * 助手在回答的末尾写一个 nx-case 围栏块：
 *
 * ```nx-case
 * {"status": "fixed", "ids": "0100E8B028B54000", "summary": "空之轨迹 1st 的黑屏修好了"}
 * ```
 *
 * App 认这个格式，就把它渲染成一条提示（「已修好——去跑一下」那种）；助手没写这个块，
 * 就什么都没有——这张卡是助手说的，不是 App 猜的。
 *
 * 约定写在指令里（assets/nx2a/AGENTS.md 与 gamespec.md）：案例状态变了、玩家该看见时，
 * 才带这个块。
 */
data class AgentCaseStatus(
    /** fixed（修好了）/ not-fixed（试过没成）/ open（还开着）；待确认走确认门，不走这里。 */
    val status: String,
    /** 那局的 title ID：「去跑一下」要知道跑哪一局。 */
    val ids: String = "",
    /** 一句话：修好了什么、还差什么（玩家自己的语言）。 */
    val summary: String = "",
)

/** 助手发的那个块：从一段文字里把它取出来，正文留下，状态带走。 */
object CaseStatusFormat {

    /** 围栏块的名字：助手写 ```nx-case */
    const val FENCE = "nx-case"

    /** 助手会说的三种状态。别的（待确认、没写）都不算。 */
    val STATUSES = setOf("fixed", "not-fixed", "open")

    private val BLOCK = Regex("```$FENCE[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /**
     * 从一段文字里取出案例状态。
     *
     * @return 正文（那个块被剥掉了）与状态（没有就是 null）。有多个块时，最后一个算数。
     */
    fun extract(text: String): Pair<String, AgentCaseStatus?> {
        var status: AgentCaseStatus? = null
        val stripped = BLOCK.replace(text) { match ->
            val parsed = parse(match.groupValues[1])
            if (parsed != null) status = parsed
            ""
        }
        return stripped.trimEnd() to status
    }

    /** 块里的那几行：一段 JSON。读不出来就不是状态。 */
    private fun parse(body: String): AgentCaseStatus? {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
        val state = json.optString("status").trim().lowercase()
        if (state !in STATUSES) return null
        return AgentCaseStatus(
            status = state,
            ids = json.optString("ids").trim(),
            summary = json.optString("summary").trim(),
        )
    }
}
