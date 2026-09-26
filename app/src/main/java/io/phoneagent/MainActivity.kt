package io.phoneagent

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.phoneagent.ui.BridgeScreen
import io.phoneagent.ui.OnboardingScreen
import io.phoneagent.ui.SettingsScreen
import io.phoneagent.ui.TerminalScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 解压 rootfs、跑 apt、以及在终端里编译东西都可能耗时很久，
        // 期间屏幕熄灭会拖慢甚至中断任务。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

/**
 * 顶层导航。
 *
 * 设置未完成时直接进引导清单 —— 新用户不必再去「把每个标签点一遍、自己拼出顺序」。
 * 「开始使用」之后才进主界面，之后可以从设置页回到清单（重装、排查时用）。
 */
@androidx.compose.runtime.Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("phoneagent_ui", Context.MODE_PRIVATE) }
    var entered by remember { mutableStateOf(prefs.getBoolean(KEY_ENTERED, false)) }

    if (!entered) {
        OnboardingScreen(onStart = {
            prefs.edit().putBoolean(KEY_ENTERED, true).apply()
            entered = true
        })
        return
    }

    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Agent") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("通道") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("设置") })
        }
        when (tab) {
            0 -> TerminalScreen()
            1 -> BridgeScreen()
            else -> SettingsScreen(
                onRestartSetup = {
                    prefs.edit().putBoolean(KEY_ENTERED, false).apply()
                    entered = false
                }
            )
        }
    }
}

private const val KEY_ENTERED = "entered_main_ui"
