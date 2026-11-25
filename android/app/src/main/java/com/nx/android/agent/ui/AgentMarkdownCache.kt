package com.nx.android.agent.ui

import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import com.nx.android.agent.AgentMessage
import com.nx.android.agent.AgentRole
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Markdown 的预解析缓存：解析在后台先做完，条目出现时高度一次就对。
 *
 * 渲染库自己是异步解析的——条目滚进视野才开始解析，解析完高度再变一次。长回答滚起来
 * 就是这样"一突一突、一下滚很远"：估的高度是猜的，真高度在解析落地后才出现。这里提前
 * 把 AST 备好，滚动时不再等它，高度也不用再改一次。
 *
 * 缓存有界（留最近的一批）：内存有界；往回翻很旧的消息时它可能已经被挤掉，那就退回
 * 库自己的异步解析（那条消息的高度还是会跳一次）。
 *
 * 换一段对话要清空：条目的钥匙是消息的 id，而新会话的消息 id 从头数（1、2、3……）——
 * 新对话的第 2 条会撞上旧对话第 2 条的钥匙，于是旧答案的 AST 顶了上来，屏幕上是上一段
 * 对话的回答。清空 + 代际计数（清空前起跑的那次解析，不让它回来）才是干净的。
 *
 * @param parse 解析一段文字（测试塞一个可控的进去）
 */
class AgentMarkdownCache(
    private val scope: CoroutineScope,
    private val parse: suspend (String) -> MarkdownState? = { text ->
        (parseMarkdown(text) as? State.Success)?.let { PreparsedMarkdownState(it) }
    },
) {

    private companion object {
        /** 留多少条：够玩家翻一段，又不至于把内存吃穿 */
        const val KEEP = 50
    }

    private val lock = Any()
    private val parsed = LinkedHashMap<Long, MarkdownState>(0, 0.75f, true)
    /** 每条备好的 AST 是从哪段文字来的：文字变了就得重备 */
    private val parsedText = LinkedHashMap<Long, String>(0, 0.75f, true)
    private val inFlight = mutableSetOf<Long>()
    /** 解析不了的那几条（id → 那段文字）：同样的文字再试也是同样的结果，别一次次重试 */
    private val failed = LinkedHashMap<Long, String>()
    /** 清空了几次：清空前起跑的那次解析，落地时不认 */
    private var generation = 0L
    private val _ready = MutableStateFlow<Map<Long, MarkdownState>>(emptyMap())

    /** 解析好的那些：条目按 id 取。 */
    val ready: StateFlow<Map<Long, MarkdownState>> = _ready.asStateFlow()

    /**
     * 这些消息的 AST 备好：助手的消息备上，正在生成的那条等它写完再说。
     *
     * 这一步放在状态层（不是面板里）——面板冷启动时根本不组合，预热放在那儿就等于没有：
     * 首开那刻才开始解析，落底会停在半路。这里从启动就开工，首开时高度已经定了。
     */
    fun ensureAll(messages: List<AgentMessage>, streamingId: Long?) {
        for (m in messages) {
            if (m.role != AgentRole.ASSISTANT) continue
            if (m.id == streamingId) continue   // 正在生成的那条：这一轮结束后再排版
            ensure(m.id, m.text)
        }
    }

    /** 这条消息的 AST 备好了吗？没备好就在后台备一份。 */
    fun ensure(id: Long, text: String) {
        if (text.isBlank()) return
        val gen = synchronized(lock) {
            // 备过的：那段文字还是这一段，才算数。文字变了（助手发的案例状态块被剥掉）
            // 就重备一份——否则屏幕上留着一个旧的 AST。
            if (parsed.containsKey(id) && parsedText[id] == text) return
            if (failed[id] == text) return   // 这段文字解析不了：换个文字再来
            if (!inFlight.add(id)) return
            generation
        }
        scope.launch(Dispatchers.Default) {
            val ready = try {
                parse(text)
            } catch (t: Throwable) {
                null
            }
            synchronized(lock) {
                inFlight.remove(id)
                // 清空过：这次解析属于旧的那一段对话，不回来
                if (gen != generation) return@synchronized
                if (ready != null) {
                    parsed[id] = ready
                    parsedText[id] = text
                    while (parsed.size > KEEP) {
                        val oldest = parsed.keys.firstOrNull() ?: break
                        parsed.remove(oldest)
                        parsedText.remove(oldest)
                    }
                } else {
                    // 解析不了：记下这段文字，别在每次刷新时都重试一遍
                    failed[id] = text
                    while (failed.size > KEEP) {
                        val oldest = failed.keys.firstOrNull() ?: break
                        failed.remove(oldest)
                    }
                }
            }
            if (ready != null) _ready.value = synchronized(lock) { parsed.toMap() }
        }
    }

    /**
     * 换了一段对话：旧的 AST 一条都不留。
     *
     * 消息的 id 是每一段对话自己从头数的——新对话的第 2 条消息会撞上旧对话第 2 条的
     * 钥匙，屏幕就会把旧答案当成新答案渲染。清空之后缓存是空的，渲染库自己解析当前
     * 那段文字（清空前起跑的那次解析也不让它回来）。
     */
    fun clear() {
        synchronized(lock) {
            generation++
            parsed.clear()
            parsedText.clear()
            failed.clear()
            inFlight.clear()
        }
        _ready.value = emptyMap()
    }
}

/** 已经解析好的 markdown：直接交给渲染器，不用再等它解析一次。 */
class PreparsedMarkdownState(private val parsed: State.Success) : MarkdownState {
    private val flow = MutableStateFlow<State>(parsed)
    override val state: StateFlow<State> = flow.asStateFlow()
    override val links: StateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())
    override suspend fun parse(): State = parsed
}
