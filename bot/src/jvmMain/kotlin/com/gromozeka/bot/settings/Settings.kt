package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    // Audio Settings
    val enableTts: Boolean = true,
    val enableStt: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val sttMainLanguage: String = "en",
    
    // Input Settings
    val autoSend: Boolean = true,
    val globalPttHotkeyEnabled: Boolean = false,
    val muteSystemAudioDuringPTT: Boolean = true,
    
    // AI Settings
    val claudeModel: String = "sonnet",
    val includeCurrentTime: Boolean = true,
    val responseFormat: ResponseFormat = ResponseFormat.JSON,
    
    // UI Settings  
    val showSystemMessages: Boolean = true,
    
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