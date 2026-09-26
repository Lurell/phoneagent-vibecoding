package io.phoneagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.phoneagent.container.LogLevel

/**
 * 把内联的终端模拟器接到我们自己的会话上。
 *
 * 上游这两个模块**互相之间没有连接**：`TerminalSession` 通过 `TerminalSessionClient`
 * 往外喊「屏幕变了」，而 `TerminalView` 需要有人调它的 `onScreenUpdated()`。
 * 中间这一段必须由应用自己接 —— 就是这个类存在的理由。
 *
 * 同时实现了两个接口：
 *   - `TerminalSessionClient`：会话 → 界面（输出、标题、退出、剪贴板、日志）
 *   - `TerminalViewClient` ：界面 → 会话（按键、长按、缩放、修饰键状态）
 */
class ProotTerminalClient(
    private val context: Context,
    /** 会话结束，参数是退出码。 */
    private val onFinished: (Int) -> Unit,
    /**
     * 内部日志。**必须带上严重程度** —— 界面上按级别决定颜色和是否自动消失，
     * 让调用点各自决定怎么显示的话，最后就会变成「什么都是红的」。
     */
    private val onLog: (LogLevel, String) -> Unit,
    /** 扩展键行上的 Ctrl / Alt 是否处于按下状态。 */
    private val isCtrlDown: () -> Boolean,
    private val isAltDown: () -> Boolean,
) : TerminalSessionClient, TerminalViewClient {

    /** 由界面层在创建 TerminalView 之后注入。 */
    var terminalView: TerminalView? = null

    // ── TerminalSessionClient：会话 → 界面 ─────────────────────────────────

    override fun onTextChanged(changedSession: TerminalSession?) {
        // 上游这两个模块之间没有自动连接，必须由应用手动把
        // 「会话内容变了」转成「视图重绘」。漏掉这一句的表现是：
        // 命令能执行、但屏幕永远空白。
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        onFinished(finishedSession?.exitStatus ?: -1)
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        onLog(LogLevel.INFO, "已复制 ${text.length} 个字符到剪贴板")
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) session?.write(text)
    }

    override fun onBell(session: TerminalSession?) = Unit

    override fun onColorsChanged(session: TerminalSession?) {
        terminalView?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // 上游 javadoc 明确要求这样接：光标可见性变化 → 光标的闪烁开关
        terminalView?.setTerminalCursorBlinkerState(state, true)
    }

    /**
     * 返回 null 表示用默认光标样式。
     *
     * 注意类型要写 `Int?` 而不是 `Integer?`：Java 的 `Integer` 在 Kotlin 里映射为
     * 平台类型 `Int!`，写成 `Integer?`（那是 `java.lang.Integer`）会报
     * RETURN_TYPE_MISMATCH_ON_OVERRIDE。`Int?` 可以表达「返回 null」这个语义。
     */
    override fun getTerminalCursorStyle(): Int? = null

    // ── TerminalViewClient：界面 → 会话 ────────────────────────────────────

    /**
     * 双指缩放。返回 1.0f 表示已自行消费掉缩放比例（不再由视图处理）。
     * 目前不实现字号缩放，等有需要再加。
     */
    override fun onScale(scale: Float): Float = 1.0f

    /**
     * 点一下终端就弹出软键盘。
     *
     * 上游 TerminalView 的手势回调里已经调过 requestFocus()，但 Android **不会**因为
     * 一个视图获得焦点就自动弹出输入法 —— 必须显式调 showSoftInput。
     * 少了这一句，表现就是「点屏幕毫无反应、没法打字」。
     */
    override fun onSingleTapUp(e: MotionEvent?) {
        val view = terminalView ?: return
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 手机上返回键映射成 ESC 更实用（vim、claude 都吃这个）。 */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    // 这三个由界面上的「扩展键行」按钮切换。
    //
    // 关键点：**不需要我们自己去做 Ctrl 的字符变换** —— TerminalView.inputCodePoint()
    // 会读这里的返回值，然后自己把 'c' 变成 0x03、'z' 变成 0x1a 等等。
    // 我们只负责如实报告修饰键状态，转换交给上游做。
    override fun readControlKey(): Boolean = isCtrlDown()

    override fun readAltKey(): Boolean = isAltDown()

    // 没有 Shift / Fn 的 UI，如实返回 false。
    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        // 模拟器就绪后光标才会存在，此时再开闪烁。
        // 不能放在 attachSession 之后立刻做 —— 那时宽高可能还是 0，
        // view 的 updateSize() 会提前 return，mEmulator 仍是 null。
        terminalView?.setTerminalCursorBlinkerState(true, true)

        // ★ 必须把视图背景设成终端的默认背景色，否则屏幕是白底白字、看起来完全空白。
        //
        // 原因在渲染器里：TerminalRenderer.drawTextRun() 有一句
        //     if (backColor != palette[COLOR_INDEX_BACKGROUND]) { 画背景 }
        // 也就是**刻意不画默认背景** —— 这是条优化，前提是画布已经被刷成默认背景色。
        // Termux 的视图在布局 XML 里设了背景，所以成立；我们的 Compose AndroidView
        // 没有背景，画布保持透明，于是露出下层界面的浅色，而文字是默认前景色（白）。
        //
        // 从调色板里取色而不是写死 0xFF000000：这样终端用 OSC 4/11 改配色时也能跟上。
        val emulator = terminalView?.currentSession?.emulator
        val bg = emulator?.mColors?.mCurrentColors?.get(TextStyle.COLOR_INDEX_BACKGROUND)
        terminalView?.setBackgroundColor(bg ?: 0xFF000000.toInt())

        // 补一次重绘。
        //
        // 视图的 updateSize() 是先把 mEmulator 从 null 赋上值、再调 onEmulatorSet() 的；
        // 在此之前若已有输出到达，onScreenUpdated() 会因为 mEmulator 为空而提前 return，
        // 那次重绘机会就永久丢了。而交互式 shell 打完提示符后就等着输入、不再产生输出，
        // 屏幕于是会一直停在空白，直到用户敲下第一个键才恢复。
        terminalView?.onScreenUpdated()
    }

    // ── 日志：全部转发到界面输出区 ─────────────────────────────────────────

    override fun logError(tag: String?, message: String?) = forward("E", tag, message)

    override fun logWarn(tag: String?, message: String?) = forward("W", tag, message)

    override fun logInfo(tag: String?, message: String?) = forward("I", tag, message)

    override fun logDebug(tag: String?, message: String?) = Unit // 太吵，不转发

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        forward("E", tag, "$message: ${e?.javaClass?.simpleName}: ${e?.message}")
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        forward("E", tag, e?.toString())
    }

    private fun forward(level: String, tag: String?, message: String?) {
        val line = "[$level/$tag] $message"
        Log.println(
            when (level) {
                "E" -> Log.ERROR
                "W" -> Log.WARN
                else -> Log.INFO
            },
            tag ?: "phoneagent", message ?: "",
        )

        // 只有 TerminalSession 的日志额外进界面，其余一律只进 logcat。
        if (tag != SESSION_TAG) return

        onLog(
            when (level) {
                "E" -> LogLevel.ERROR
                "W" -> LogLevel.WARN
                else -> LogLevel.INFO
            },
            line,
        )
    }

    private companion object {
        /**
         * 允许进界面的上游 tag。
         *
         * 上游几个模块共用一套 `logError` / `logWarn`，但「错误」的含义完全不同：
         *
         *  - `TerminalSession`  —— pty 建不起来、读写失败。**用户的环境真的坏了**，
         *    而且这正是自检要回答的问题，必须让用户看见。
         *  - `TerminalEmulator` —— 「这个转义序列我不认识」。那是模拟器自身的协议
         *    覆盖度问题：TUI 程序越新撞上的越多（SGR、OSC、设备控制串、
         *    modifyOtherKeys…）。用户既看不懂也改不动，而 Claude Code 一启动就会
         *    撞上一条，看着像故障，其实不是。
         *
         * 所以这里是**白名单**：默认只进 logcat。将来上游再加一个爱抱怨的模块，
         * 也不会自动冒到用户面前 —— 噪音漏出去比诊断少显示一条糟得多。
         *
         * tag 只能硬编码：那两个常量在上游是 private。万一上游改名，后果只是
         * 少在界面上显示几条日志（logcat 里一条不少）。
         */
        const val SESSION_TAG = "TerminalSession"
    }
}
