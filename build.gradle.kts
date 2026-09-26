plugins {
    // AGP 9 起内置了 Kotlin 支持，不再需要（也不能）单独应用
    // org.jetbrains.kotlin.android —— 同时应用会直接报错。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
