package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val enableTts: Boolean = true,
    val enableStt: Boolean = true,
    val autoSend: Boolean = true,
    val claudeModel: String = "sonnet",
    val globalPttHotkeyEnabled: Boolean = false,
    val muteSystemAudioDuringPTT: Boolean = true,
    val showOriginalJson: Boolean = false,
    // STT language code - supports ISO 639-1 (e.g., "en", "ru") and 639-3 codes for GPT-4o models
    val sttMainLanguage: String = "en",
    val includeCurrentTime: Boolean = true,
    // TTS speech rate: 0.25 (slowest) to 4.0 (fastest), 1.0 = normal speed
    val ttsSpeed: Float = 1.0f,
)

@Serializable
enum class AppMode {
    DEV,
    PROD
}