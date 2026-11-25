package com.nx.android.agent

import com.nx.android.agent.env.PiCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

enum class AgentRole { USER, ASSISTANT }

data class AgentToolCard(
    val id: String,
    val name: String,
    val argsSummary: String,
    val state: AgentToolState,
    val resultSummary: String? = null,
    /** The sub-agent this call spawned (null for an ordinary tool). */
    val subagent: AgentSubagentCall? = null,
)

enum class AgentToolState { RUNNING, DONE, FAILED }

data class AgentMessage(
    val id: Long,
    val role: AgentRole,
    val text: String,
    val tools: List<AgentToolCard> = emptyList(),
    /**
     * 这一轮的思考过程（引擎的 thinking）。
     *
     * 它不是回答，是模型自己想的过程：默认折起来，点开才看。
     */
    val thinking: String = "",
    /** 这一条回答自己生成的 token 数（0 = 引擎没报） */
    val tokens: Long = 0L,
    /**
     * 这一轮还没收尾：App 在这一轮中途没了，标记留在会话文件里。
     * 重新打开时它显示「已中断」——玩家看得见这一轮断在哪。
     */
    val pending: Boolean = false,
    /** 这一轮里，子 Agent 完成时回来的通知（一张卡片一张通知）。 */
    val notifications: List<AgentNotification> = emptyList(),
    /**
     * 助手自己发的案例状态（回答里的 nx-case 块）：没有就没有。
     *
     * 那是助手说的话，不是 App 猜的——App 只把它渲染出来。
     */
    val caseStatus: AgentCaseStatus? = null,
)

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    /** 引擎的思考过程：模型自己想的那一段（不是回答） */
    data class ThinkingDelta(val text: String) : AgentEvent
    data class ToolStarted(val card: AgentToolCard) : AgentEvent
    data class ToolFinished(val id: String, val state: AgentToolState, val resultSummary: String) : AgentEvent
    data class TurnDone(val assistantText: String, val tokens: Long = 0L) : AgentEvent
    data class Failed(val reason: AgentFailure) : AgentEvent

    /** The running context occupancy (tokens in the model's window) and the window itself. */
    data class UsageUpdated(val contextTokens: Long, val contextWindow: Long) : AgentEvent

    /** A sub-agent finished: the notice that says so. */
    data class Notification(val notification: AgentNotification) : AgentEvent

    /**
     * 轮外的一轮写完了：后台子 Agent 跑完之后，主 Agent 自己写下的那段下文。
     *
     * 它到的时候没有玩家在问——引擎把子 Agent 的通知注入会话，主 Agent 随即接一段话。
     * 这段话是给玩家看的，所以它自己是一条消息。
     */
    data class FollowUp(val text: String, val tokens: Long = 0L) : AgentEvent
}

enum class AgentFailure { NO_KEY, BAD_KEY, NETWORK, MODEL_NO_TOOLS, LOOP_ERROR }

data class AgentModelConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    /**
     * Whether this model is ready to run.
     *
     * The providers the engine knows by itself carry their own endpoint, so there is no address to
     * keep for them — a missing one does not make the model unready.
     */
    val configured: Boolean
        get() = apiKey.isNotBlank() && modelName.isNotBlank() &&
            (baseUrl.isNotBlank() || PiCatalog.engineManaged(providerId))
}

interface AgentChat {
    val messages: List<AgentMessage>
    suspend fun send(userText: String): Flow<AgentEvent>
    fun reset()

    /**
     * 轮外的事件：子 Agent 跑完的通知、主 Agent 在轮外写下的下文、上下文占用。
     *
     * 它们是引擎在两轮之间送来的（后台子 Agent 完成后，引擎把通知注入会话，主 Agent
     * 随即写一段总结）——这些事件到的时候没有轮在跑，App 照样得看得见。
     */
    val backgroundEvents: Flow<AgentEvent> get() = emptyFlow()

    /** 中止当前这一轮：引擎停下手里的活，进程留着给下一轮。 */
    fun abort()

    /**
     * 读回一段会话的历史：prefer 为空就是指针上的（或最新的一段），指定就是切到那一段再读。
     * 会话的真相在引擎那里——这是抽屉渲染历史的唯一来源。
     */
    suspend fun history(prefer: String? = null): Result<AgentHistory>

    /**
     * 这一局到现在花了多少：主会话的累计用量与成本。
     *
     * 引擎那本账上的数（含已被压缩掉的历史）——App 不自己累加，重开一次也不会少算。
     */
    suspend fun usage(): AgentTokenUsage = AgentTokenUsage()
}

/** 一段会话的历史（从引擎读回的形状）：消息、会话文件的路径、标题、上下文占用、用量。 */
data class AgentHistory(
    val messages: List<AgentMessage>,
    val sessionFile: String,
    val title: String,
    val contextTokens: Long,
    val contextWindow: Long,
    /** 这一局到读历史时为止的累计用量（引擎的账本）。 */
    val usage: AgentTokenUsage = AgentTokenUsage(),
)

/** 引擎还没装：历史读不了，面板要引导安装，而不是显示一段空历史。 */
class AgentEngineMissingException : IllegalStateException("引擎未安装")

interface AgentUiActions {
    fun navigate(page: String, game: String? = null): String
}
