package io.phoneagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.phoneagent.container.ClaudeProviders
import io.phoneagent.container.ClaudeSettings

/**
 * 引导清单 —— App 设置未完成时直接进这一页。
 *
 * 取代了原来「把四个标签各点一遍、自己拼出顺序」的上手方式。
 */
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel = viewModel(),
    onStart: () -> Unit,
) {
    val state by vm.state.collectAsState()

    // 每次进入这一页都重读一遍状态。
    //
    // ViewModel 是 Activity 作用域的：从「设置 → 回到引导清单」回来时拿到的是
    // **同一个实例**，init 不会再跑，于是这里会一直显示上一次的快照 ——
    // 表现就是「刚撤销了文件通道授权，清单里还写着已完成」。
    LaunchedEffect(Unit) { vm.refresh() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.authorizeBridge(uri) }

    if (state.editingApiKey) {
        EndpointEditor(state, vm)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("准备 phoneagent", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "按顺序做完下面几步，就能在容器里跑 Agent 了。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(20.dp))

        state.steps.forEach { step ->
            StepCard(
                step = step,
                enabled = !state.busy,
                onAction = {
                    when (step.key) {
                        "rootfs" -> vm.installRootfs()
                        "agent" -> vm.installAgent()
                        "apikey" -> vm.openApiKeyEditor()
                        "bridge" -> picker.launch(null)
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
        }

        if (state.busy) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(state.busyLabel, style = MaterialTheme.typography.bodySmall)
            }
        }

        state.message.takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onStart,
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.canStart) "开始使用"
                else "还差 ${state.remainingRequired} 步必需设置"
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "「API Key」和「文件通道」可以先跳过 —— 前者不填 Claude 连不上模型，" +
                "后者不选则 Agent 看不到你手机上的文件。之后都能在设置页补。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

}

/**
 * 模型接入配置。
 *
 * **从对话框改成了整页**：要填的东西已经不是一个「确认框」装得下的 ——
 * 厂商预设、地址、令牌、模型名，每一项都还得配一句解释（这几项写错的表现
 * 全都是「连不上」，用户没法从错误信息反推是哪一项的问题）。
 */
@Composable
private fun EndpointEditor(state: OnboardingUiState, vm: OnboardingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("模型接入", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "写进容器的 ~/.claude/settings.json，只动接入相关的字段，" +
                "不影响你原有的其它配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(20.dp))
        Text("厂商", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClaudeProviders.ALL.forEach { p ->
                FilterChip(
                    selected = state.providerKey == p.key,
                    onClick = { vm.selectProvider(p.key) },
                    label = { Text(p.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "选一个会把下面的地址和模型名一并填好，之后仍可改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        // ── 地址 ───────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.baseUrlDraft,
            onValueChange = vm::onBaseUrlChange,
            label = { Text("ANTHROPIC_BASE_URL") },
            placeholder = { Text("留空 = 用官方端点") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val normalized = ClaudeSettings.normalizeBaseUrl(state.baseUrlDraft)
        if (normalized.isNotEmpty() && normalized != state.baseUrlDraft.trim()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "将规整为：$normalized",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // ── 令牌 ───────────────────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.apiKeyDraft,
            onValueChange = vm::onApiKeyChange,
            label = {
                Text(if (state.authTokenMode) "ANTHROPIC_AUTH_TOKEN" else "ANTHROPIC_API_KEY")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !state.authTokenMode,
                onClick = { vm.onAuthTokenModeChange(false) },
                label = { Text("官方 API") },
            )
            FilterChip(
                selected = state.authTokenMode,
                onClick = { vm.onAuthTokenModeChange(true) },
                label = { Text("中转 / 网关") },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.authTokenMode) "写进 ANTHROPIC_AUTH_TOKEN，发 Authorization: Bearer。"
            else "写进 ANTHROPIC_API_KEY，发 x-api-key。"
                + "选错的表现是「配置都填好了，但 Claude 连不上」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        // ── 模型 ───────────────────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.modelDraft,
            onValueChange = vm::onModelChange,
            label = { Text("模型名") },
            placeholder = { Text("留空 = 不写模型映射") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Claude Code 按 opus / sonnet / haiku 三个档位请求模型，这里填的名称会" +
                "同时写进这三个档位和子 Agent。第三方端点通常只有一个模型名，" +
                "不填就会报「模型不存在」。名称以厂商文档为准，随时可改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        // ── 思考强度 ───────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Text("思考强度", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClaudeSettings.EFFORT_LEVELS.forEach { level ->
                FilterChip(
                    selected = state.effortDraft == level.value,
                    onClick = { vm.onEffortChange(level.value) },
                    label = { Text(level.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "默认「最高」—— 手机上等 Agent 的代价比算力贵。想省 token 就换低一档；" +
                "换「不设置」则完全不动这一项，用模型自己的默认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        // ── 动作 ───────────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = vm::saveApiKey,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("保存")
        }
        TextButton(onClick = vm::closeApiKeyEditor, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepCard(step: SetupStep, enabled: Boolean, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (step.done) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (step.done) "✓" else if (step.required) "●" else "○",
                color = if (step.done) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(22.dp),
            )

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(step.title, fontWeight = FontWeight.Medium)
                    if (!step.required) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "可选",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // 完成后仍然保留入口（前提是这一步有可改的东西）。
            // 用次要样式 —— 它不再是「下一步该做的事」，而是「想改的时候能改」。
            val actionLabel = if (step.done) step.doneActionLabel else step.actionLabel
            if (actionLabel != null) {
                Spacer(Modifier.width(10.dp))
                if (step.done) {
                    TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
                } else {
                    OutlinedButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
                }
            }
        }
    }
}
