package io.phoneagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.phoneagent.container.BridgeStore

/**
 * 文件通道页。见 [BridgeViewModel] 的类注释说明为什么这一页是必需的。
 */
@Composable
fun BridgeScreen(vm: BridgeViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    // 撤销是不可逆的操作（要重新走一遍系统选择器才能恢复），所以加一道确认。
    var confirmRevoke by remember { mutableStateOf(false) }

    // 系统目录选择器。SAF 的操作只能由 App 发起，所以必须有这个按钮。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.authorize(uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("交互目录", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.authorized) state.treeUri else "尚未选择目录",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(null) },
                        enabled = !state.busy,
                    ) { Text(if (state.authorized) "更换目录" else "选择目录") }

                    if (state.authorized) {
                        OutlinedButton(onClick = vm::sync, enabled = !state.busy) { Text("立即同步") }
                        TextButton(onClick = { confirmRevoke = true }) { Text("撤销") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status.contains("失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (confirmRevoke) {
            AlertDialog(
                onDismissRequest = { confirmRevoke = false },
                title = { Text("撤销目录授权？") },
                text = {
                    Text(
                        "撤销后 Agent 就看不到你的文件了。你手机上的文件夹和里面的文件不会被删除，" +
                            "只是这个应用不再能访问它。之后可以重新选择同一个目录。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.revoke()
                        confirmRevoke = false
                    }) { Text("撤销") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRevoke = false }) { Text("取消") }
                },
            )
        }

        if (!state.authorized) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "选一个目录作为容器与你之间的通道。App 会在其中自动建两个子目录：\n\n" +
                    "  in/  —— 你放文件进去，Agent 只能读\n" +
                    "  out/ —— Agent 的产出会出现在这里，你直接点开看\n\n" +
                    "选定之后，你平时用系统文件管理器往 in/ 里丢文件就行。",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        FileSection(
            title = "送到容器  in/",
            hint = "Agent 只能读，改不了你放进去的原始文件",
            files = state.inFiles,
            emptyHint = "还没有文件。用文件管理器把文件放进该目录的 in/ 里，再点「立即同步」。",
            action = null,
        )

        Spacer(Modifier.height(12.dp))
        FileSection(
            title = "从容器取出  out/",
            hint = "Agent 写在这里的东西会自动同步回你的目录",
            files = state.outFiles,
            emptyHint = "还没有产出。让 Agent 把结果写到 /bridge/out/ 就会出现在这里。",
            action = if (state.outFiles.isEmpty()) null else ("清空" to vm::clearOut),
        )
    }
}

@Composable
private fun FileSection(
    title: String,
    hint: String,
    files: List<BridgeStore.Entry>,
    emptyHint: String,
    action: Pair<String, () -> Unit>?,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontWeight = FontWeight.Medium)
                action?.let { (label, onClick) ->
                    TextButton(onClick = onClick) { Text(label) }
                }
            }
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            if (files.isEmpty()) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                files.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = humanSize(entry.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
