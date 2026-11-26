package com.nx.android.game

import android.annotation.SuppressLint
import android.app.Activity
import android.app.GameManager
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nx.android.NxApp
import com.nx.android.R
import com.nx.android.appstate.PageFacts
import com.nx.android.appstate.PageState
import com.nx.android.appstate.PageStateStore
import com.nx.android.agent.runtime.GamespecExporter
import com.nx.android.agent.runtime.openGamespecIssue
import com.nx.android.agent.ui.AgentConversationState
import com.nx.android.agent.ui.AgentDrawerHost
import com.nx.android.agent.ui.AgentEdgeBadge
import com.nx.android.core.storage.AppPaths
import com.nx.android.core.ui.ProvideAppLocale
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.core.ui.theme.NxTheme
import com.nx.android.data.AppLocale
import com.nx.android.data.AppLog
import com.nx.android.data.GameLog
import com.nx.android.data.Gamespec
import com.nx.android.data.MotionSettings
import com.nx.android.data.PrefsStore
import com.nx.android.data.RateSettings
import com.nx.android.data.RuntimeCompiler
import com.nx.android.data.RuntimeStorage
import com.nx.android.data.RuntimeLibraries
import com.nx.android.data.drivers.Drivers
import com.nx.android.data.hasGyroscope
import com.nx.android.data.library.LibraryFileScanner
import com.nx.android.data.playback.PackEnvStore
import com.nx.android.domain.RuntimeBuildMarker
import com.nx.android.game.GameActivity.Companion.prepareScope
import com.nx.android.game.overlay.audioOutputDevices
import com.nx.android.agent.ui.AgentDrawerHost as AgentDrawerHostAlias
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 音量键归 Android：游戏的音频走媒体音量组（AAUDIO_USAGE_GAME），系统拿不到这些键就永远调不了音量
 * （音量条不出现，游戏的声音也一点不变）。其余按键照旧交给游戏。
 */
internal fun forwardsKeyToGame(keyCode: Int): Boolean =
    keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
        keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
        keyCode != KeyEvent.KEYCODE_VOLUME_MUTE

/** 游戏页的准备阶段（Loading 页的状态）：进程级的，页面重建时新实例读到的是同一份 */
internal enum class PreparePhase { IDLE, RUNNING, READY, FAILED }

/**
 * One game in this process (":play"): the launch lands here first as a Loading page, and a prepare of
 * the process's own ([prepareScope], outliving any activity instance) brings the host in — the fingerprint
 * is checked against the pack, a stale runtime is rebuilt by a porter subprocess right here, and the host
 * is copied into the app's private folder. Only when that is done does the page load the host
 * ([System.load]), put the SurfaceView in (its surfaceCreated must not fire before the host is in) and
 * start it ([Native.start]).
 *
 * 游戏内的一切界面都归宿主核心画（虚拟手柄、四页签菜单、计数器、布局编辑器、存档选择器——上游的那套）；
 * App 在游戏画面上只画助手（右缘的徽标与抽屉，游戏照常跑）。宿主菜单的「声音输出」回调游戏页的
 * [openAudioMenu]，弹 App 自己的选设备对话框。宿主结束进程就是游戏结束（一个进程一个进程一把钥匙——
 * 一个进程一个游戏），[endProcess] 是主动版。起来了的宿主不能在同进程再起一次，重建出来的活动只把
 * 新窗口交给跑着的游戏（[hostRunning]）。
 */
@AndroidEntryPoint
class GameActivity : ComponentActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_PACK = "pack"          // the data pack folder (the port's output, shared storage)
        const val EXTRA_TITLE_ID = "title_id"  // keeps the saves, the temp and the log apart per game
        const val EXTRA_NAME = "name"
        const val EXTRA_REBUILD = "rebuild"    // the runtime is rebuilt first, whatever it looks like now

        /** a start already happened in this process: a recreated activity takes the running game's window over */
        @Volatile var hostRunning = false

        /** the prepare runs on the process's own scope: an activity recreation does not kill it, and does not re-run it */
        private val prepareScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var prepareJob: Job? = null

        // ---- the prepare's states, process-level: the Loading page of whichever instance is current reads these ----
        internal var preparePhase by mutableStateOf(PreparePhase.IDLE); private set
        internal var prepareStep by mutableStateOf("")       // what the prepare is doing right now (the porter's line)
        internal var prepareReason by mutableStateOf("")     // the failure's own reason (why the rebuild stopped)
        @Volatile private var preparedHostPath = ""          // where the prepared host landed (READY)
        @Volatile private var startClaimed = false           // exactly one instance turns a READY prepare into a game
        private var currentInstance = WeakReference<GameActivity?>(null)

        /** the game again (back from the app): the running page comes to the front, no new one is made */
        fun resume(activity: Activity) {
            activity.startActivity(
                Intent(activity, GameActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    @Inject lateinit var compiler: RuntimeCompiler
    @Inject lateinit var runtimeStorage: RuntimeStorage
    /** 攻关档案的导出：打包器在环境里跑 */
    @Inject lateinit var exporter: GamespecExporter
    /** 玩家的 nx2android 文件夹：攻关档案（gamespec/）落在里面 */
    @Inject lateinit var paths: AppPaths
    /** 助手的会话：整个 App 一份——首页的抽屉与这里的抽屉看的都是它 */
    @Inject lateinit var agentConversation: AgentConversationState
    /** 页面状态快照：助手想知道玩家此刻在哪一局，就读它 */
    @Inject lateinit var pageState: PageStateStore

    /** 界面语言：toast 与助手的词从这里取 */
    private val locale: AppLocale get() = (application as NxApp).locale

    private var packPath: String? = null
    private var packTitleId: String? = null
    private var packName: String = ""
    /** 这次进来是要重建运行库的（游戏闪退后玩家点的「重新构建」）：目录里那份对不上也不将就，重编一次 */
    private var forceRebuild = false

    /** 玩家在处理哪一局：助手据此知道这局是哪一局、运行参数是什么 */
    private fun gamePageState(): PageState {
        val facts = mutableMapOf<String, String>()
        // 运行参数：包里的 env.txt 写着它们（语言与 Vulkan 驱动）
        packPath?.let { pack ->
            val (lang, driver) = PackEnvStore.current(File(pack))
            if (!lang.isNullOrBlank()) facts[PageFacts.LANGUAGE] = lang
            if (!driver.isNullOrBlank()) facts[PageFacts.DRIVER] = driver
        }
        return PageState(
            page = "game",
            titleId = packTitleId.orEmpty(),
            name = packName,
            facts = facts,
        )
    }

    /** the game UI (surface, assistant) is in the view tree: the native calls and the input paths are live */
    private var gameUiReady = false

    // 助手在游戏画面上的入口（右缘的那条把手）：玩家可以在设置里把它收走
    private var agentBadge by mutableStateOf(true)
    /** 助手的侧抽屉开着吗（游戏里从右边缘那枚徽标进来） */
    private var agentDrawerOpen by mutableStateOf(false)
    /** 抽屉第一次打开之后就一直留在组合里；之前不组合它（游戏启动时不必付这份开销） */
    private var agentEverOpened by mutableStateOf(false)
    /** 宿主菜单的「声音输出」弹出的 App 对话框（列系统给的设备，选了经 JNI 送回宿主） */
    private var audioMenuOpen by mutableStateOf(false)

    private val inputManager: InputManager get() = getSystemService(InputManager::class.java)

    private fun isGamepad(deviceId: Int): Boolean {
        val d = inputManager.getInputDevice(deviceId) ?: return false
        if (d.isVirtual) return false   // 虚拟设备（小米常驻的 uinput-xiaomi 之类）：它说是手柄，却从不发输入
        val s = d.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun immersive() {
        window.decorView   // the decor must exist: the insets controller hangs off it (a plain Activity has none until it is asked for)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else @Suppress("DEPRECATION") window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /**
     * 横屏时窗口铺满整块屏幕：默认的挖孔策略下，系统把整个窗口避开刘海/挖孔，窗口窄一条。
     * 游戏画面是在这个窗口里居中的，于是整幅画面被推到右边，左边多出一条黑带（挖孔正好在那条带上）。
     * 允许画进挖孔区之后，窗口就是整块屏幕，画面居中到整块屏上，左右黑边等宽，挖孔落在黑边里。
     */
    private fun drawOverCutout() {
        if (Build.VERSION.SDK_INT < 28) return   // 挖孔从 Android 9 起才有；更早的设备本来就不避让
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        WindowCompat.setDecorFitsSystemWindows(window, false)   // 内容铺满整块屏幕，inset 自己处理
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 游戏进程自己的日志（App 的日志只在主进程里开过，这里也开一份：游戏出问题时看得到）
        AppLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentInstance = WeakReference(this)
        packPath = intent.getStringExtra(EXTRA_PACK)
        packTitleId = intent.getStringExtra(EXTRA_TITLE_ID)?.ifEmpty { null }
        packName = intent.getStringExtra(EXTRA_NAME) ?: ""
        forceRebuild = intent.getBooleanExtra(EXTRA_REBUILD, false)
        immersive()
        drawOverCutout()
        if (hostRunning) { installGameUi(); return }   // a recreation (the system rebuilt the activity): the running game takes the new window in surfaceCreated
        // 没带包的一条（resume 的自愈）：这个进程里没有正在跑的游戏，也没有准备在进行——
        // 它是「从不杀」模型下的清道夫，把幽灵进程收掉。
        if (packPath.isNullOrEmpty()) {
            toast(locale.str(R.string.game_no_game_to_run)); endProcess(); return
        }
        // 上一份准备已经就绪（页面在准备期被重建、又或上一个实例没了）：这份实例直接接手开跑
        if (preparePhase == PreparePhase.READY && !startClaimed && preparedHostPath.isNotEmpty()) {
            startClaimed = true
            startGame(preparedHostPath)
            return
        }
        // 出生即 Loading：Back 被接管，SurfaceView 一概不进视图树——
        // surfaceCreated 若早于 System.load 会抛 UnsatisfiedLinkError。准备在进程级的协程里做，
        // 页面重建时不重跑（新实例只接管 Loading 界面）。
        showLoading()
        startPrepare()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- 出生即 Loading：准备期的唯一画面（进行中 / 失败，成功后整个被游戏 UI 换掉）----

    private fun showLoading() {
        gameUiReady = false
        val loading = ComposeView(this)
        loading.setViewTreeLifecycleOwner(this)
        loading.setViewTreeViewModelStoreOwner(this)
        loading.setViewTreeSavedStateRegistryOwner(this)
        loading.setContent { NxTheme { ProvideAppLocale(locale) { LoadingPage() } } }
        setContentView(loading)
    }

    @Composable
    private fun LoadingPage() {
        val name = packName
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (name.isNotBlank()) {
                    Text(name, color = Color.White,
                         style = MaterialTheme.typography.titleLarge,
                         maxLines = 1)
                    Spacer(Modifier.height(18.dp))
                }
                when (preparePhase) {
                    PreparePhase.RUNNING -> {
                        CircularProgressIndicator(color = NxPalette.Cream)
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.runtime_rebuilding), color = Color.White.copy(alpha = 0.75f),
                             style = MaterialTheme.typography.bodyMedium)
                        // 进度来自同进程读 porter 子进程的 stdout：它说到哪儿了，这里就写到哪儿
                        if (prepareStep.isNotBlank()) Text(prepareStep, color = Color.White.copy(alpha = 0.5f),
                             style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.runtime_rebuild_hint), color = Color.White.copy(alpha = 0.5f),
                             style = MaterialTheme.typography.bodySmall,
                             textAlign = TextAlign.Center)
                    }
                    PreparePhase.FAILED -> {
                        Text(prepareReason.ifBlank { stringResource(R.string.runtime_update_failed) },
                             color = Color.White, style = MaterialTheme.typography.bodyMedium,
                             textAlign = TextAlign.Center)
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { startPrepare() }) {   // 重试：重新判断一次并再准备
                                Text(stringResource(R.string.runtime_retry))
                            }
                            Button(onClick = { endProcess() }) {
                                Text(stringResource(R.string.drawer_exit))
                            }
                        }
                    }
                    PreparePhase.READY, PreparePhase.IDLE -> {}
                }
            }
        }
    }

    // ---- 准备：指纹检查 →（对不上就地重建）→ 拷进私有目录；全程在进程级协程里 ----

    private fun startPrepare() {
        if (prepareJob?.isActive == true) return   // 页面重建：协程还在，新实例只接管 Loading 界面
        val pack = packPath ?: return
        startClaimed = false
        preparePhase = PreparePhase.RUNNING
        prepareStep = ""
        prepareReason = ""
        prepareJob = prepareScope.launch {
            val outcome = prepareRuntime(File(pack))
            withContext(Dispatchers.Main) {
                if (outcome.ok) {
                    // 成功：Loading 收起。由「当下活着的那份实例」开跑（重建期间的新实例接手）
                    preparedHostPath = outcome.hostPath
                    val driver = currentInstance.get()
                    if (driver != null && !startClaimed) {
                        startClaimed = true
                        driver.startGame(outcome.hostPath)
                    } else preparePhase = PreparePhase.READY   // 没有活着的实例：下一次 onCreate 接手
                } else {
                    preparePhase = PreparePhase.FAILED
                    prepareReason = outcome.reason
                    // 起不来也是这局的事：记进那局的档案
                    Gamespec.appendEvent(
                        paths.nxHome,
                        packTitleId.orEmpty(),
                        Gamespec.LAUNCH_FAILED,
                        outcome.reason,
                    )
                }
            }
        }
    }

    private data class PrepareOutcome(val ok: Boolean = false, val hostPath: String = "", val reason: String = "")

    private suspend fun prepareRuntime(pack: File): PrepareOutcome {
        val ported = LibraryFileScanner.portFromPack(pack)
            ?: return PrepareOutcome(reason = locale.str(R.string.main_cannot_run_not_a_pack, pack.name))
        val dir = runtimeStorage.dir(ported.titleId)
        runtimeStorage.pruneLegacyHost(dir)   // 改名前的旧前端库（libdmplay.so）：见到就删，它不可能再被装载
        // forceRebuild：玩家点名要重建（闪退后的「重新构建」）——目录里那份对不上也不将就，一律重编。
        // 旧格式的包（清单带 host，运行库在包里）：包里那份若还是旧前端的 libdmplay.so，不晋升——
        // 就地重建（重建写的是新名字 libdmhost.so 与新标记，旧文件随之作废）。
        if (ported.hostDeclared) {
            if (ported.runnable && !forceRebuild && ported.host.name == "libdmhost.so") {
                if (runtimeStorage.complete(dir) && runtimeStorage.matches(ported, runtimeStorage.readMarker(dir))) {
                    return PrepareOutcome(ok = true, hostPath = runtimeStorage.hostOf(dir).absolutePath)
                }
                try {
                    prepareStep = locale.str(R.string.runtime_promoting)
                    withContext(Dispatchers.IO) {
                        RuntimeLibraries.copyForLaunch(ported, dir)
                    }
                    // 拷完由 App 写入构建标记（否则下次启动无从判版本）。旧包的运行库是旧前端的，
                    // 晋升拷贝只是过渡：标记里的接口版本对不上，下一次启动就地重建。
                    runtimeStorage.writeMarker(dir, RuntimeBuildMarker(
                        playerApiRevision = ported.playerApiRevision, profileH = ported.profileH))
                } catch (e: Exception) {
                    return PrepareOutcome(reason = e.message.orEmpty().ifBlank { e.javaClass.simpleName })
                }
                if (!runtimeStorage.complete(dir)) {
                    return PrepareOutcome(reason = locale.str(R.string.main_cannot_run_no_host, pack.name))
                }
                return PrepareOutcome(ok = true, hostPath = runtimeStorage.hostOf(dir).absolutePath)
            }
            rebuild(pack)?.let { return it }
        } else {
            // 新格式：运行库是统一目录里的正本，标记在、指纹对得上就零拷贝加载；对不上（app 升级让
            // 接口版本变了、标记没了、档案换了）就地重建——重建把运行库写回目录，启动判断只读目录侧。
            if (!forceRebuild && runtimeStorage.complete(dir) && runtimeStorage.matches(ported, runtimeStorage.readMarker(dir))) {
                return PrepareOutcome(ok = true, hostPath = runtimeStorage.hostOf(dir).absolutePath)
            }
            rebuild(pack)?.let { return it }
        }
        // 重建之后（或目录里已就位的那份）：清单与标记都核对过了，这里只认目录
        val fresh = LibraryFileScanner.portFromPack(pack) ?: ported
        val freshMarker = runtimeStorage.readMarker(ported.titleId)
        if (!runtimeStorage.complete(dir) || !runtimeStorage.matches(fresh, freshMarker)) {
            return PrepareOutcome(reason = locale.str(R.string.game_needs_report))
        }
        return PrepareOutcome(ok = true, hostPath = runtimeStorage.hostOf(dir).absolutePath)
    }

    /**
     * 就地重建（porter 子进程，stdout 读进来就是 Loading 的进度）；成功返回 null，失败返回原因。
     *
     * 这一步的输出写进游戏自己的文件夹（rebuild.log，每次重建覆盖）：重建只发生在启动这一刻，
     * 它属于这个游戏，游戏详情页的日志包会把它一起带走。
     */
    private suspend fun rebuild(pack: File): PrepareOutcome? {
        val writer = try {
            val log = File(pack, GameLog.REBUILD_FILE)
            log.writeText("")
            log.writer()
        } catch (e: Exception) {
            null
        }
        val result = compiler.rebuild(pack.absolutePath) { line ->
            prepareStep = line
            try {
                writer?.write(line + "\n")
                writer?.flush()
            } catch (e: Exception) {
            }
        }
        try { writer?.close() } catch (e: Exception) {}
        // 重建是这局的事：记进那局的档案（玩家问「刚才为什么重编了一遍」时查得到）
        Gamespec.appendEvent(
            paths.nxHome,
            packTitleId.orEmpty(),
            Gamespec.REBUILD,
            if (result.success) "runtime rebuild done" else "runtime rebuild failed: ${result.reason}",
        )
        if (!result.success) return PrepareOutcome(reason = result.reason)
        return null
    }

    // ---- 开跑：加载宿主 → 游戏进视图树 → Native.start（全部在主线程上，SurfaceView 最后进）----

    private fun startGame(host: String) {
        preparePhase = PreparePhase.IDLE
        try {
            System.load(host)
        } catch (t: Throwable) {
            preparePhase = PreparePhase.FAILED
            prepareReason = locale.str(R.string.game_cannot_load_host, t.message)
            showLoading()
            Gamespec.appendEvent(
                paths.nxHome,
                packTitleId.orEmpty(),
                Gamespec.LAUNCH_FAILED,
                prepareReason,
            )
            return
        }
        installGameUi()
        val pack = packPath ?: return endProcess()
        val titleId = packTitleId
        // 玩家在详情页预设的运行参数，经环境行进宿主：帧率档位（DM_FPS_PIN，0/未设 = 跟随游戏）、
        // 体感的两行（开不开、保留甩动方向）。这些是启动时读的默认值——游戏内不再改它们。
        val rate = RateSettings(PrefsStore(this), titleId.orEmpty()).load()
        val motion = MotionSettings(PrefsStore(this), titleId.orEmpty()).load(hasGyroscope(this))
        val env = (rate?.takeIf { it.fps != 0 }?.let { arrayOf("DM_FPS_PIN=${it.fps}") } ?: emptyArray()) +
            motion.envLines()
        if (!Native.start(pack, filesDir.path, titleId, resources.displayMetrics.densityDpi, env, this)) {
            endProcess()   // the save import asked to end: it happens at the next start
            return
        }
        hostRunning = true
        // the default driver: a game whose driver was never picked runs on the app's built-in one (the row goes
        // into the pack's env.txt, which the host reads). A driver the player picked is left alone.
        applyDefaultDriver(File(pack))
    }

    /** 游戏的视图树：SurfaceView + 助手层（准备完成才建——见类注释） */
    private fun installGameUi() {
        val frame = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        frame.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // the assistant's layer: a ComposeView over the game's surface (the badge and the drawer; the game
        // keeps running under it — the assistant watches a running game, so nothing here holds the clock)
        val compose = ComposeView(this)
        compose.setViewTreeLifecycleOwner(this)
        compose.setViewTreeViewModelStoreOwner(this)
        compose.setViewTreeSavedStateRegistryOwner(this)
        compose.setContent { NxTheme { ProvideAppLocale(locale) { GameOverlayRoot() } } }
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        immersive()
        gameUiReady = true
    }

    /** 助手的层：右缘的徽标、滑出的抽屉，与宿主菜单叫出来的声音输出对话框 */
    @Composable
    private fun GameOverlayRoot() {
        Box(Modifier.fillMaxSize()) {
            // 助手的徽标：右边缘垂直居中的一条小把手，点它就滑出助手抽屉。
            // 玩家可以在设置里收走它（那条把手是画面上的东西，游戏画面该是游戏的）。
            if (agentBadge && !agentDrawerOpen) {
                AgentEdgeBadge(
                    onClick = { openAgentDrawer() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            // 助手的侧抽屉：从右边滑出来的那张（与首页同一套），开着的时候游戏照常跑着
            // 第一次打开之前不组合它：徽标点开之后它才进组合，然后就一直留着
            LaunchedEffect(agentDrawerOpen) { if (agentDrawerOpen) agentEverOpened = true }
            if (agentEverOpened) {
                AgentDrawerHost(
                    open = agentDrawerOpen,
                    conversation = agentConversation,
                    chips = listOf(
                        R.string.agent_chip_game_crash,
                        R.string.agent_chip_game_fps,
                        R.string.agent_chip_game_build,
                    ),
                    haze = null,   // 游戏画面是 SurfaceView，磨砂糊不到它：走玻璃的兜底底色
                    onCollapse = { closeAgentDrawer() },
                    onExpand = {},
                    onOpenSettings = {},
                    onExportGamespec = { packTitleId?.let { exportGamespec(it) } },
                )
            }
            // 宿主菜单的「声音输出」叫出来的对话框：列系统给的设备，选了经 JNI 送回宿主（立刻切换）
            if (audioMenuOpen) {
                val devices = remember { audioOutputDevices(this@GameActivity) }
                val current = remember { runCatching { Native.audioGet() }.getOrDefault(0) }
                AlertDialog(
                    onDismissRequest = { audioMenuOpen = false },
                    confirmButton = {},
                    dismissButton = {},
                    title = { Text(stringResource(R.string.drawer_audio_output), style = MaterialTheme.typography.titleMedium) },
                    text = {
                        Column {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.drawer_audio_follow)) },
                                modifier = Modifier.fillMaxWidth(),
                                trailingContent = { if (current == 0) Text("✓", color = NxPalette.Cream) },
                            )
                            devices.forEach { d ->
                                ListItem(
                                    headlineContent = { Text(d.productName ?: locale.str(audioKindName(d))) },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingContent = { if (current == d.id) Text("✓", color = NxPalette.Cream) },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    /** 设备种类的词（上游 AudioChoices 的分法）：扬声器 / 有线 / 蓝牙；外接的给系统给的名字 */
    private fun audioKindName(d: com.nx.android.game.overlay.AudioOutput): Int = when (d.kind) {
        com.nx.android.game.overlay.AudioOutputKind.SPEAKER -> R.string.drawer_audio_speaker
        com.nx.android.game.overlay.AudioOutputKind.WIRED -> R.string.drawer_audio_wired
        com.nx.android.game.overlay.AudioOutputKind.BLUETOOTH -> R.string.drawer_audio_bluetooth
        com.nx.android.game.overlay.AudioOutputKind.EXTERNAL -> R.string.drawer_audio_output
    }

    /** 宿主菜单的「声音输出」：宿主经 JNI 找到游戏页的这个方法（上游前端的约定），App 弹自己的选设备对话框 */
    fun openAudioMenu() {
        runOnUiThread { audioMenuOpen = true }
    }

    /** The default driver: the app's built-in one (the driver it ships), written into the pack when the player
     * never picked a driver for this game. The row is written once — a driver the player picked (the device's own
     * one included) stays what it is. */
    private fun applyDefaultDriver(pack: File) {
        val builtIn = Drivers.list(this).firstOrNull { it.shipped } ?: return
        PackEnvStore.ensureDriverDefault(pack, builtIn.library.absolutePath)
    }

    /**
     * 导出这局的攻关档案：在环境里跑打包器，把 zip 放进下载目录，再把玩家送到提 issue 的页面。
     *
     * 玩家拿去提 issue 用的是这个包（表单里自己附上）。档案本身不动——导出是快照，导出完了还能继续往里记。
     */
    private fun exportGamespec(titleId: String) {
        lifecycleScope.launch {
            val result = exporter.export(this@GameActivity, titleId)
            result.onSuccess { target ->
                toast(locale.str(R.string.agent_gamespec_exported, target.name))
                openGamespecIssue(this@GameActivity)
            }.onFailure {
                toast(locale.str(R.string.agent_gamespec_export_failed, it.message.orEmpty()))
            }
        }
    }

    /** The phone's language changed (configChanges holds it): the app's words are read again */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        locale.configurationChanged()
    }

    override fun onPause() { super.onPause(); if (gameUiReady) Native.lifecycle(0) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
        if (gameUiReady) Native.lifecycle(if (hasFocus) 2 else 3)
    }

    override fun onDestroy() {
        // 这一局没了：助手看到的页面状态回到主界面那一页
        pageState.setGame(null)
        super.onDestroy()
    }

    // ---- the surface: the window passes over owned, and dropping it destroys the swapchain before it returns ----
    override fun surfaceCreated(h: SurfaceHolder) { Native.setSurface(h.surface) }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { Native.setSurface(h.surface) }
    override fun surfaceDestroyed(h: SurfaceHolder) = Native.setSurface(null)

    // ---- input: touches and keys go to the core as raw data (the core does its own hit-testing:
    //      the gamepad's controls, the host menu, and the game's touchscreen are all its to sort) ----
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!gameUiReady) return true   // Loading 期：还没有宿主，触摸就地吃掉
        val ids = mutableListOf<Int>(); val xs = mutableListOf<Float>(); val ys = mutableListOf<Float>()
        for (i in 0 until e.pointerCount) { ids += e.getPointerId(i); xs += e.getX(i); ys += e.getY(i) }
        Native.motion(e.source, e.deviceId, e.action, ids.toIntArray(), xs.toFloatArray(), ys.toFloatArray(), FloatArray(0))
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!gameUiReady) return super.onGenericMotionEvent(e)   // Loading 期：宿主还没载入，别碰它
        if (!isGamepad(e.deviceId)) return super.onGenericMotionEvent(e)
        Native.motion(e.source, e.deviceId, e.action, IntArray(0), FloatArray(0), FloatArray(0), com.nx.android.input.PadAxes.read(e))
        return true
    }

    /**
     * everything is forwarded to the core as raw data (it sorts the gamepad, its menu and the game's touch)
     * — except the volume keys, which belong to Android. Back is the host menu's key (upstream: Back opens
     * and closes the menu); the assistant's drawer takes Back and the typing keys while it is open.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!gameUiReady) {   // Loading 期：Back 接管（不可返回），其余按键也没有去处（宿主还没载入）
            return forwardsKeyToGame(e.keyCode)
        }
        if (!forwardsKeyToGame(e.keyCode)) return false   // 音量键：系统的事，游戏与宿主都不沾
        // 助手抽屉开着的时候，键是输入框的事：中文输入法的删除键走的是按键事件这条路，
        // 被这里转走就删不掉了（英文输入法走 deleteSurroundingText，所以它没事）。
        // 键也不进游戏——玩家在打字，不是在玩。
        if (agentDrawerOpen) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_DOWN) {
                closeAgentDrawer()
            }
            return super.dispatchKeyEvent(e)   // 交给焦点所在的输入框
        }
        if (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) {
            Native.key(e.deviceId, e.source, e.keyCode, e.action, e.metaState, e.repeatCount)
        }
        return true
    }

    // ---- lifecycle: the same dance as the NativeActivity's callbacks ----
    override fun onResume() {
        super.onResume()
        // 助手看得见的页面状态：游戏页在前台时它说了算（这一局是哪一局、跑的是什么参数）
        pageState.setGame(gamePageState())
        if (!gameUiReady) return   // Loading 期：宿主还没就位
        Native.lifecycle(1)
        pushGameMode()
    }

    /**
     * Android 的游戏模式：系统给的档位（性能 / 标准）推给宿主，宿主据此换节奏策略
     * （性能档里游戏自己的周期说话）。每次 resume 都问一遍 —— 玩家在游戏页时可以在系统里改模式
     * （上游的 #294：游戏在后台时模式可能变）。
     */
    private fun pushGameMode() {
        val mode = GameMode.forward(systemGameMode(), packGraphics())
        runCatching { Native.perfGameMode(mode) }
    }

    /** 系统现在给的档位（2 = 性能，1 = 标准，-1 = 不支持；没有那个服务就是 0） */
    private fun systemGameMode(): Int {
        if (Build.VERSION.SDK_INT < 31) return 0   // GameManager is Android 12 and later
        val gm = getSystemService(GameManager::class.java) ?: return 0
        return runCatching { gm.gameMode }.getOrDefault(0)
    }

    /** 这个游戏的图形接口（包的清单里的 graphics）：宿主自己画的那些才归宿主的节奏策略管 */
    private fun packGraphics(): String {
        val pack = packPath ?: return ""
        return runCatching {
            LibraryFileScanner.portFromPack(File(pack))?.graphics ?: ""
        }.getOrDefault("")
    }

    /** 助手的抽屉打开：游戏照常跑着——助手要看着它抓帧、翻日志，停了就什么都看不到 */
    private fun openAgentDrawer() {
        agentDrawerOpen = true
    }

    /** 助手抽屉收起：游戏本来就在跑，这里只是把抽屉收走 */
    private fun closeAgentDrawer() {
        agentDrawerOpen = false
    }

    /**
     * 这一页没了，进程也走：宿主在一个进程里只能跑一次（见类注释）。
     * 游戏没起来也一样要把进程带走——只结束页面不结束进程，会留下一个「有进程没游戏」的幽灵，
     * 库页会以为游戏还在跑（点「运行」就会去回到一个已经不在的页面）。
     */
    private fun endProcess() {
        finish()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }
}
