package io.phoneagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 设置页。
 *
 * 核心是**指令白名单** —— 容器里的 Agent 能对手机做哪些事，由这里决定。
 * 这一页不做的话，白名单就只是代码里的默认值：用户既看不见也改不了，
 * 那叫「作者认为安全」，不叫「用户能控制的安全」。
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    /** 回到引导清单（重装、排查时用）。 */
    onRestartSetup: () -> Unit = {},
) {
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("指令白名单", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "容器里的 Agent 能对手机做这些事。关掉之后它会收到明确的拒绝理由，" +
                        "并被告诉还有哪些可用 —— 所以它不会卡死，只会换方案。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        state.commands.forEach { cmd ->
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cmd.title, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = cmd.allowed,
                        onCheckedChange = { vm.setAllowed(cmd.name, it) },
                        enabled = cmd.toggleable,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("容器", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))

                InfoRow("状态", if (state.rootfsInstalled) "已安装" else "未安装")
                if (state.rootfsVersion.isNotEmpty()) InfoRow("系统", state.rootfsVersion)
                InfoRow(
                    "占用",
                    if (state.computingSize) "计算中…" else state.rootfsSize,
                )

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = vm::resetEnvDoc) {
                    Text("重置 Agent 运行环境说明")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "如果你自己改过容器里的 ~/.claude/CLAUDE.md，之后又想恢复成应用自带的版本，" +
                        "用这个按钮。下次启动容器时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Android 构建工具（可选）────────────────────────────────────────
        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Android 构建工具", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "让 Agent 能在手机上自己编出 APK。约 500MB（JDK 占大头），" +
                        "日常用 Agent 不需要它 —— 只有你要让它做 Android 开发时才装。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))

                when {
                    state.toolchainBusy -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.toolchainPhase, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.toolchainPercent in 0..100) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { state.toolchainPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    state.toolchainInstalled -> {
                        Text("已安装，Agent 可以用 build-apk.sh 编 APK", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = vm::uninstallToolchain) { Text("卸载并回收约 500MB") }
                    }

                    else -> {
                        Button(
                            onClick = vm::installToolchain,
                            enabled = state.rootfsInstalled,
                        ) { Text("安装（约 500MB）") }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 环境自检（从删掉的命令页搬过来）────────────────────────────────
        Card {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("环境自检", fontWeight = FontWeight.Medium)
                    TextButton(onClick = vm::runProbe, enabled = !state.busy) { Text("运行") }
                }
                Text(
                    "容器起不来的时候先看这里 —— 它能区分是系统拦了（SELinux）还是依赖没配好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (state.probeVisible && state.probe.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    state.probe.forEach { item ->
                        Text(
                            text = (if (item.ok) "✓ " else "✗ ") + item.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (item.ok) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                    TextButton(onClick = vm::toggleProbe) { Text("收起") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("重新配置", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "回到最初的引导清单。容器和设置都不会被删掉，只是重新走一遍流程 —— " +
                        "换 rootfs、补装组件、或者只是想看看还差什么的时候用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRestartSetup) { Text("回到引导清单") }
            }
        }

        state.message.takeIf { it.isNotEmpty() }?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
