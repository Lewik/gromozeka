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
    // STT language code - supports ISO 639-1 (e.g., "en", "ru") and 639-3 codes for GPT-4o models
    val sttMainLanguage: String = "en",
)

@Serializable
enum class AppMode {
    DEV,
    PROD
}