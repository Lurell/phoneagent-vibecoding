package io.phoneagent.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.view.TerminalView
import io.phoneagent.container.LogLevel
import kotlinx.coroutines.delay

/**
 * 主界面 —— Agent 优先。
 *
 * 这一页的重点是**一个大按钮**：容器没起来时是「启动容器」，起来之后是
 * 「启动 Claude Code」。用户是来找 Agent 的，不该每次手敲一遍 `claude`。
 */
@Composable
fun TerminalScreen(vm: TerminalViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val modifiers by vm.modifiers.collectAsState()
    val density = LocalDensity.current.density
    val session = vm.currentSession()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 键盘弹出时把整个布局顶上去，终端随之下移并变矮。
            //
            // 这不只是「看得见输入框」：终端变矮会走
            // TerminalView.updateSize() → Pty.setWindowSize → 内核发 SIGWINCH，
            // 容器里的全屏程序（vim、claude）会重新排版而不是被压在键盘下面。
            // 这正是当初做 pty 时处理过的那条链路。
            .imePadding(),
    ) {
        // ── 状态 + 主按钮 ──────────────────────────────────────────────────
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(9.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (state.started) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                ) {}
                Spacer(Modifier.size(8.dp))
                Text(
                    text = when {
                        state.finished -> "容器已退出（${state.exitCode}）"
                        state.started -> "容器运行中"
                        else -> "容器未启动"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                if (state.started) {
                    TextButton(onClick = vm::restart) { Text("重启", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = vm::stop) { Text("结束", style = MaterialTheme.typography.bodySmall) }
                }
            }

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = {
                    when {
                        // 容器已经退出，主按钮得能直接重开。少了这一支，它会继续显示
                        // 「启动 Claude Code」并往一个死掉的会话里写 —— 点了毫无反应。
                        state.finished -> vm.restart()
                        state.started -> vm.launchClaude()
                        else -> vm.start()
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    when {
                        state.busy -> state.busyLabel.ifEmpty { "准备中…" }
                        state.finished -> "重新启动容器"
                        !state.started -> "启动容器"
                        state.claudeLaunched -> "再次启动 Claude Code"
                        else -> "启动 Claude Code"
                    }
                )
            }
        }

        StatusBar(
            last = state.log.lastOrNull(),
            seq = state.logSeq,
            onDismiss = vm::dismissLog,
        )

        Box(Modifier.weight(1f)) {
            if (state.started && session != null) {
                key(session) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                // 顺序不能改。setTextSize 是唯一创建 renderer 的地方，
                                // 而 attachSession 会立刻调 updateSize()，那里会解引用 renderer。
                                setTextSize((16 * density).toInt())
                                setTerminalViewClient(vm.client)
                                vm.client.terminalView = this
                                isFocusable = true
                                isFocusableInTouchMode = true
                                requestFocus()
                                attachSession(session)
                            }
                        },
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "点上面的按钮启动容器",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        if (state.started) {
            ExtraKeysRow(vm, modifiers, enabled = !state.finished)
        }
    }
}

/**
 * 终端上方的状态条。只显示最后一条。
 *
 * 颜色按级别来 —— 这里以前是一律用 error 色，于是
 * 「已从交互目录取回 3 个文件」和「pty 建不起来」长得一模一样，
 * 用户没法从颜色上分辨哪条需要管。
 *
 * INFO 会自己消失：它是进度，不是问题，看过了就该把屏幕还给终端。
 * WARN / ERROR 留着，要用户自己点掉 —— 会消失的告警等于没有告警。
 */
@Composable
private fun StatusBar(last: LogLine?, seq: Long, onDismiss: () -> Unit) {
    var expiredSeq by remember { mutableStateOf(-1L) }

    LaunchedEffect(seq, last) {
        if (last?.level == LogLevel.INFO) {
            delay(4000)
            expiredSeq = seq
        }
    }

    // 用 logSeq 而不是文本判断「是不是新消息」：同一句话再来一次也该重新计时。
    val expired = last != null && last.level == LogLevel.INFO && seq <= expiredSeq
    if (last == null || expired) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = last.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (last.level) {
                LogLevel.INFO -> MaterialTheme.colorScheme.outline
                LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 2.dp),
        )
        if (last.level != LogLevel.INFO) {
            TextButton(onClick = onDismiss) {
                Text("×", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 扩展键行。
 *
 * 所有键在**同一条可横向滑动的行**里。前六个（/ ← ↑ ↓ → ENTER）的宽度被撑到
 * 正好占满一屏，于是危险键天然落在屏幕外、**必须滑动才能碰到**。
 *
 * 那六个都是**可逆**操作：`/` 只是打出一个字符（Claude Code 的斜杠命令全靠它），
 * 方向键只动光标，回车只是确认 —— 误触最多重按一次。
 *
 * 屏幕外那几个会打断正在跑的东西：
 *
 *   ESC   打断 Claude Code 正在生成的回复
 *   ^C    同上，更硬
 *   ^D    结束输入，等同关掉当前程序
 *   ^Z    把前台进程挂起 —— 最阴的一个：终端看起来"卡住了"，
 *         而原因是一个看不见的暂停作业
 *
 * 为什么是「一行撑满」而不是「固定区 + 可滑区」分两块：分成两块时滑到右边、
 * 方向键就看不见了，而方向键恰恰是滑过去之后最可能还要用的。同一条行里
 * 滑回来即可，手势也只有一个。
 *
 * 宽度**按屏幕算**而不是写死 dp：写死的话换个屏幕就可能把危险键漏出来。
 */
@Composable
private fun ExtraKeysRow(vm: TerminalViewModel, modifiers: Modifiers, enabled: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val edge = 8.dp
        // 6 个键 + 5 个间隔 + 两侧留白 = 整屏宽。第 7 个键的起点就落在屏幕外。
        val fixedWidth = (maxWidth - edge * 2 - gap * 5) / 6

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edge, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            // ── 一屏之内：可逆、最常用 ─────────────────────────────────────
            KeyChip("/", enabled, width = fixedWidth) { vm.sendText("/") }
            KeyChip("←", enabled, width = fixedWidth) { vm.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            KeyChip("↑", enabled, width = fixedWidth) { vm.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            KeyChip("↓", enabled, width = fixedWidth) { vm.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            KeyChip("→", enabled, width = fixedWidth) { vm.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            KeyChip("ENTER", enabled, width = fixedWidth) { vm.sendKey(KeyEvent.KEYCODE_ENTER) }

            // ── 屏幕外：会打断东西的、以及不常用的 ─────────────────────────
            KeyChip("ESC", enabled) { vm.sendKey(KeyEvent.KEYCODE_ESCAPE) }
            KeyChip("TAB", enabled) { vm.sendKey(KeyEvent.KEYCODE_TAB) }

            KeyChip("^C", enabled) { vm.sendText("\u0003") }
            KeyChip("^D", enabled) { vm.sendText("\u0004") }
            KeyChip("^Z", enabled) { vm.sendText("\u001a") }

            // CTRL / ALT 是粘性开关：按一下打开、再按一下关闭，
            // 之后敲的字符会被 TerminalView 按修饰键变换（'c' -> 0x03 等）。
            // 高亮表示还开着 —— 忘了关会让人以为键盘坏了。
            KeyChip("CTRL", enabled, selected = modifiers.ctrl) { vm.toggleCtrl() }
            KeyChip("ALT", enabled, selected = modifiers.alt) { vm.toggleAlt() }

            // 软键盘上要翻页才找得到的两个符号。
            KeyChip("-", enabled) { vm.sendText("-") }
            KeyChip("|", enabled) { vm.sendText("|") }
        }
    }
}

/**
 * 键帽。
 *
 * 没有用 Material 的 AssistChip：它的内边距是按「带图标」设计的，宽度不受控
 * （一排撑不出「正好一屏」），高度也偏大 —— 键盘弹起时终端本来就矮。
 *
 * [width] 只有撑满一屏的那几个键会传，其余按内容自适应。**定宽的键不加内边距** ——
 * 宽度是算出来的定值，六等分之后每个约 58dp，再加 10dp 内边距会把「ENTER」
 * 挤到换行。文字居中就够了，反正它比键帽窄。
 */
@Composable
private fun KeyChip(
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    width: Dp? = null,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (width != null) Modifier else Modifier.padding(horizontal = 10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1,
        )
    }
}
