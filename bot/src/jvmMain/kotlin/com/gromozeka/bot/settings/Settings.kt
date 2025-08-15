package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    // Audio Settings
    val enableTts: Boolean = false,
    val enableStt: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsModel: String = "gpt-4o-mini-tts", // tts-1, tts-1-hd, gpt-4o-mini-tts
    val ttsVoice: String = "alloy", // alloy, echo, fable, onyx, nova, shimmer
    val sttMainLanguage: String = "en",
    val sttModel: String = "whisper-1", // Currently only whisper-1 is supported

    // Input Settings
    val autoSend: Boolean = false,
    val globalPttHotkeyEnabled: Boolean = false,
    val muteSystemAudioDuringPTT: Boolean = false,

    // AI Settings
    val claudeModel: String = "sonnet",
    val includeCurrentTime: Boolean = true,
    val responseFormat: ResponseFormat = ResponseFormat.XML_INLINE,

    // API Keys
    val openAiApiKey: String? = null,

    // UI Settings  
    val showSystemMessages: Boolean = true,
    val alwaysOnTop: Boolean = false,

    // Notification Settings
    val enableErrorSounds: Boolean = false,
    val enableMessageSounds: Boolean = false,
    val enableReadySounds: Boolean = false,
    val soundVolume: Float = 1.0f, // 0.0 to 1.0

    // Developer Settings
    val showOriginalJson: Boolean = false,
)

@Serializable
enum class AppMode {
    DEV,
    PROD
}

@Serializable
enum class ResponseFormat {
    JSON,              // Current JSON format {"fullText": "...", "ttsText": "...", "voiceTone": "..."}
    XML_STRUCTURED,    // <response><visual>...</visual><voice tone="...">...</voice></response>
    XML_INLINE,        // Text with inline <tts tone="...">...</tts> tags
    PLAIN_TEXT         // No structure, for debugging
}