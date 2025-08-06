package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val enableTts: Boolean = true,
    val enableStt: Boolean = true,
    val autoSend: Boolean = true,
    val claudeModel: String = "sonnet",
    val globalPttHotkeyEnabled: Boolean = false,
    val showOriginalJson: Boolean = false,
)

@Serializable
enum class AppMode {
    DEV,
    PROD
}