package io.phoneagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import io.phoneagent.container.AgentEnvDoc
import io.phoneagent.container.BridgeStore
import io.phoneagent.container.ContainerGroups
import io.phoneagent.container.LogLevel
import io.phoneagent.container.Paths
import io.phoneagent.container.PhonePolicy
import io.phoneagent.container.PhoneServer
import io.phoneagent.container.ProotCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 扩展键行的修饰键状态。 */
data class Modifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
)

/** 状态条上的一行。带上级别，界面才知道该用什么颜色、要不要自动消失。 */
data class LogLine(val level: LogLevel, val text: String)

data class TerminalUiState(
    val started: Boolean = false,
    val finished: Boolean = false,
    val exitCode: Int = -1,
    val busy: Boolean = false,
    /** 忙碌时的提示语（比如「同步交互目录」「启动容器」）。 */
    val busyLabel: String = "",
    /** 已经点过「启动 Claude Code」，用来把主按钮变成次要状态。 */
    val claudeLaunched: Boolean = false,
    /** 终端模拟器内部的告警等内容，展示在终端上方的状态条里。最多留 50 条。 */
    val log: List<LogLine> = emptyList(),
    /**
     * 每追加一条日志就 +1。
     *
     * 界面靠它区分「同一句话又出现了一次」和「没有新消息」—— 日志被截断到 50 条之后
     * 长度就不再变化了，拿 size 当键会失效。
     */
    val logSeq: Long = 0,
)

/**
 * 主界面：交互式终端 + 一键进入 Agent。
 *
 * 把 proot 直接挂在真正的 pty 上，所以交互式程序（vim、top、claude 的 TUI）都能工作。
 * （v1 里还有过一个「管道 + 哨兵行」的一次性命令模式，已随命令页一并删除 ——
 * 它和终端功能重叠，而终端做得更好。将来做对话界面时会需要一个管道式会话，
 * 那时按真实需求重写。）
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val paths = Paths(app)
    private val command = ProotCommand(paths)

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /** 扩展键行上的修饰键状态。粘性开关：按一下打开，再按一下关闭。 */
    private val _modifiers = MutableStateFlow(Modifiers())
    val modifiers: StateFlow<Modifiers> = _modifiers.asStateFlow()

    val client = ProotTerminalClient(
        context = app,
        onFinished = { code ->
            _state.update { it.copy(finished = true, exitCode = code) }
        },
        onLog = { level, line -> log(level, line) },
        isCtrlDown = { _modifiers.value.ctrl },
        isAltDown = { _modifiers.value.alt },
    )

    private var session: TerminalSession? = null

    /** 容器与手机之间的指令通道。随会话启停 —— 只在 Agent 活着的时候开放。 */
    private val phoneServer = PhoneServer(
        context = app,
        paths = paths,
        policy = PhonePolicy(app),
        onLog = { level, line ->
            // 同时写 logcat。只进状态条的话，排查时看不到全部信息
            // （状态条只显示最后一条），这一条已经让我多绕了一步。
            android.util.Log.i("phoneagent", line)
            log(level, line)
        },
    )

    fun currentSession(): TerminalSession? = session

    /**
     * 创建会话。**必须在主线程调用** —— 上游 TerminalSession 内部的 Handler 绑定的是
     * 构造它的那个线程的 Looper，在后台线程构造会让终端解析跑到非主线程，
     * 随后 invalidate() 抛 CalledFromWrongThreadException。
     *
     * 注意这里**不会**立刻 fork：pty 的建立发生在视图第一次拿到尺寸、
     * 调用 session.updateSize(cols, rows) 的时候。
     */
    fun start() {
        if (session != null || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "同步交互目录…") }

            // 先同步文件通道：否则 Agent 看到的是上一次同步时的 in/，用户刚放进去的文件不在。
            // 同步要读写 SAF 目录，可能搬几十 MB，必须在 IO 线程上做。
            val synced = withContext(Dispatchers.IO) {
                BridgeStore(getApplication(), paths).refresh()
            }
            val syncNote = when (synced) {
                is BridgeStore.SyncResult.Ok ->
                    if (synced.pulled == 0) null
                    else LogLine(LogLevel.INFO, "已从交互目录取回 ${synced.pulled} 个文件")
                BridgeStore.SyncResult.NotAuthorized -> null
                is BridgeStore.SyncResult.Failed ->
                    LogLine(LogLevel.WARN, "交互目录同步失败：${synced.message}")
            }

            paths.ensureDirs()

            // 补齐容器 /etc/group 里缺失的名字（详见 ContainerGroups）。
            // 必须在会话建立**之前** —— 交互式 bash 一起来就读 /etc/bash.bashrc，
            // 那里会调 `groups`，晚了就还是那一屏 "cannot find name for group ID"。
            withContext(Dispatchers.IO) { ContainerGroups(paths).ensure() }

            // 部署「运行环境说明」。Agent 不知道产出该放哪就会写进 /root/，用户永远看不到。
            val docNote = if (AgentEnvDoc(getApplication(), paths).ensureUpToDate()) {
                LogLine(LogLevel.INFO, "已更新 Agent 运行环境说明")
            } else null

            // 指令通道要在会话起来之前就绪 —— 端口和令牌每次都会变，
            // Agent 一起来就要能读到最新的 endpoint 文件。
            withContext(Dispatchers.IO) { phoneServer.start() }

            // 会话必须在主线程构造：上游 TerminalSession 的 Handler 绑定构造线程的 Looper。
            val argv = command.interactiveArgv()
            session = TerminalSession(
                argv[0],
                paths.files.absolutePath, // 宿主上的合法路径；容器内的 cwd 由 proot 的 -w 决定
                argv.drop(1).toTypedArray(),
                command.environmentArray(),
                2000, // 回滚缓冲行数
                client,
            )

            _state.update { s ->
                val notes = listOfNotNull(syncNote, docNote)
                s.copy(
                    started = true,
                    finished = false,
                    exitCode = -1,
                    busy = false,
                    log = (s.log + notes).takeLast(50),
                    logSeq = s.logSeq + notes.size,
                )
            }
        }
    }

    /** 追加一条状态条消息。所有写入都得走这里，否则 logSeq 会与 log 脱节。 */
    private fun log(level: LogLevel, text: String) = _state.update { s ->
        s.copy(log = (s.log + LogLine(level, text)).takeLast(50), logSeq = s.logSeq + 1)
    }

    /** 用户主动关掉状态条上的告警。INFO 那类界面会自己让它消失，不需要这个。 */
    fun dismissLog() = _state.update { it.copy(log = emptyList(), logSeq = it.logSeq + 1) }

    fun restart() {
        if (_state.value.busy) return
        viewModelScope.launch {
            gracefulStop()
            session = null
            _state.update { TerminalUiState() }
            start()
        }
    }

    fun stop() {
        if (_state.value.busy) return
        viewModelScope.launch { gracefulStop() }
    }

    /**
     * 关掉会话。**先礼后兵。**
     *
     * 原来是一上来就 SIGKILL，问题是：容器里的 Agent 是被枪毙的、不是自己退的 ——
     * 它没机会收尾，用户还永远看到一个 `137`（= 128+9）的退出码，像是出了错。
     *
     * 现在按顺序来：
     *
     *  1. `Ctrl+C` 打断正在生成的东西，再 `Ctrl+D`。**不需要知道里面跑的是哪个
     *     Agent** —— 「退出」在 TUI 和 bash 里是同一个手势，Claude Code 和 shell
     *     都认 Ctrl+D。走成了的话退出码是 0，而不是 137。
     *  2. 清屏。**必须在进程真正退出之后**：TerminalSession 会在退出时往屏幕上补
     *     一行 `[Process completed (code N) - press Enter]`，清早了会被它盖回来。
     *  3. 还没走就强杀兜底。
     *
     * 每一步之间是轮询等待而不是睡固定时长 —— Agent 退得快就立刻往下走，
     * 不让用户白等。
     */
    private suspend fun gracefulStop() {
        val s = session
        if (s != null && _state.value.started && !_state.value.finished) {
            _state.update { it.copy(busy = true, busyLabel = "正在结束…") }

            s.write("\u0003") // Ctrl+C：打断正在生成的回复 / 取消当前输入
            if (!awaitExit(s, 700)) {
                s.write("\u0004") // Ctrl+D：退出 Agent
                if (!awaitExit(s, 700)) {
                    s.write("\u0004") // 再按一次：退出 Agent 之后的那个 shell
                    awaitExit(s, 700)
                }
            }

            s.finishIfRunning() // 还不走就强杀
            awaitExit(s, 500)
            clearScreen(s)

            _state.update { it.copy(busy = false, busyLabel = "") }
        }

        // 会话结束就关掉指令通道：Agent 都不在了，没理由继续开着端口
        phoneServer.stop()
    }

    /** 等会话退出，最多等 [timeoutMs]。 */
    private suspend fun awaitExit(s: TerminalSession, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!s.isRunning) return true
            delay(60)
        }
        return !s.isRunning
    }

    /**
     * 清空终端画面。
     *
     * 往**模拟器**里直接喂 ANSI 序列，而不是 `session.write()` —— 走到这里时
     * pty 另一头已经没有进程在读，写进去只会石沉大海。
     *
     * 必须在主线程调用：`append` 会改模拟器的屏幕缓冲，而渲染也读它。
     * viewModelScope 默认就在主线程。
     */
    private fun clearScreen(s: TerminalSession) {
        val emulator = s.emulator ?: return
        // 2J 清屏、3J 一并清回滚缓冲（否则往回滚还能看到刚才的 TUI 残影）、H 归位
        val bytes = "\u001b[2J\u001b[3J\u001b[H".toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        client.terminalView?.onScreenUpdated()
    }

    /**
     * 立即强杀。
     *
     * 只给「应用自己都要走了」的场景用（见 onCleared）—— 那时已经没有时间
     * 走 [gracefulStop] 那套等待。
     */
    private fun hardStop() {
        session?.finishIfRunning()
        phoneServer.stop()
    }

    /**
     * 往会话里发一行 `claude` —— 一键进 Agent。
     *
     * 用户是来找 Agent 的，不是来找 shell 的。让他每次手敲一遍 `claude`
     * 是把容器当成产品、把 Agent 当成附赠品，而顺序应该反过来。
     */
    fun launchClaude() {
        val s = session ?: return
        if (!_state.value.started) return
        viewModelScope.launch {
            // Agent 起来**之前**重写环境说明 —— 这是唯一真正有意义的时机。
            //
            // Claude Code 在**启动时**把 ~/.claude/CLAUDE.md 读进上下文，之后改文件
            // 不会影响已经在跑的会话。所以「哪个设置变了就顺手更新一次文件」这套，
            // 更新得再勤也帮不到正在说话的那个 Agent。
            //
            // 卡在这一刻还顺带省掉了逐一追踪状态变化：构建工具装没装、文件通道开没开、
            // 哪几条指令被允许 —— 全部在渲染时现读现算，不依赖别处「记得来调一次」。
            withContext(Dispatchers.IO) {
                AgentEnvDoc(getApplication(), paths).ensureUpToDate()
            }
            s.write("claude\n")
            _state.update { it.copy(claudeLaunched = true) }
        }
    }

    // ── 扩展键行 ───────────────────────────────────────────────────────────
    //
    // 手机上没法按 Ctrl+C 退出 top、也没法给 vim 发 ESC，所以必须有一排按钮。

    fun toggleCtrl() = _modifiers.update { it.copy(ctrl = !it.ctrl) }

    fun toggleAlt() = _modifiers.update { it.copy(alt = !it.alt) }

    /**
     * 发送一个「按键」。
     *
     * 交给上游的 KeyHandler 去编码，而不是自己硬写转义序列 —— 因为方向键有两套
     * 编码：普通模式是 `ESC [ A`，**应用模式（DECCKM）是 `ESC O A`**。
     * 全屏程序（vim、less、top）会切换到应用模式，写死会出现方向键失灵。
     * KeyHandler.getCode() 会读模拟器的当前模式来选对的那一套。
     */
    fun sendKey(keyCode: Int) {
        val s = session ?: return
        val emulator = s.emulator ?: return
        val code = KeyHandler.getCode(
            keyCode,
            0,
            emulator.isCursorKeysApplicationMode,
            emulator.isKeypadApplicationMode,
        )
        if (code != null) s.write(code)
    }

    /** 直接写入一串字符（控制字符也走这里，比如 Ctrl+C = "\u0003"）。 */
    fun sendText(text: String) {
        session?.write(text)
    }

    override fun onCleared() {
        // 不能走 gracefulStop()：onCleared 时 viewModelScope 已经被取消，
        // 往里面 launch 的协程根本不会执行。应用都要走了，直接强杀。
        hardStop()
        session = null
        super.onCleared()
    }
}
