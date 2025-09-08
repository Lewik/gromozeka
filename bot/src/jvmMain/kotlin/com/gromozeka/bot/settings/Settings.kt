package com.gromozeka.bot.settings

import com.gromozeka.bot.services.theming.data.DarkTheme
import com.gromozeka.bot.services.translation.data.EnglishTranslation
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    // Audio Settings
    val enableTts: Boolean = false,
    val enableStt: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsModel: String = "gpt-4o-mini-tts", // tts-1, tts-1-hd, gpt-4o-mini-tts
    val ttsVoice: String = "alloy", // alloy, echo, fable, onyx, nova, shimmer
    val sttMainLanguage: String = EnglishTranslation.LANGUAGE_CODE,
    val sttModel: String = "whisper-1", // Currently only whisper-1 is supported

    // Input Settings
    val autoSend: Boolean = false,
    val globalPttHotkeyEnabled: Boolean = false,
    val muteSystemAudioDuringPTT: Boolean = false,

    // AI Settings
    val claudeModel: String = "sonnet",
    val claudeCliPath: String? = null, // Path to Claude CLI executable (auto-detected if null)
    val includeCurrentTime: Boolean = true,
    val responseFormat: ResponseFormat = ResponseFormat.XML_INLINE,
    val autoApproveAllTools: Boolean = true, // Auto-approve all Claude Code tool requests without showing dialogs (affects new sessions only)

    // API Keys
    val openAiApiKey: String? = null,

    // UI Settings  
    val showSystemMessages: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val showTabsAtBottom: Boolean = false,
    val uiScale: Float = 1.0f, // 0.5-3.0 = UI scale (auto-detected on first launch, then user-controlled)
    val fontScale: Float = 1.0f, // 0.5-2.0 = font scaling multiplier

    // Localization Settings
    val currentLanguageCode: String = EnglishTranslation.LANGUAGE_CODE, // en, ru, he

    // Theming Settings
    val currentThemeId: String = DarkTheme.THEME_ID, // dark, light, gromozeka
    val themeOverrideEnabled: Boolean = false, // Enable theme override from override.json

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