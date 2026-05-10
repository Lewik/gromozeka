package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val enableTts: Boolean = false,
    val enableStt: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsModel: String = "gpt-4o-mini-tts",
    val ttsVoice: String = "alloy",
    val sttMainLanguage: String = "en",
    val sttModel: String = "whisper-1",

    val autoSend: Boolean = false,
    val globalPttHotkeyEnabled: Boolean = false,
    val muteSystemAudioDuringPTT: Boolean = false,

    val defaultAiProvider: AIProvider = AIProvider.CLAUDE_CODE,
    val ollamaModel: String = "llama3.2",
    val ollamaBaseUrl: String = "http://localhost:11434",
    val geminiModel: String = "gemini-2.0-flash-exp",
    val claudeModel: String = "claude-sonnet-4-5",
    val anthropicModel: String = "claude-sonnet-4-6",
    val anthropicBedrockModel: String = "anthropic.claude-sonnet-4-20250514-v1:0",
    val openAiModel: String = "gpt-4o-mini",
    val includeCurrentTime: Boolean = true,
    val responseFormat: ResponseFormat = ResponseFormat.XML_INLINE,
    val autoApproveAllTools: Boolean = true,
    val memoryAutoRemember: Boolean = false,
    val memoryAutoRecall: Boolean = false,

    val openAiApiKey: String? = null,
    val anthropicApiKey: String? = null,
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    val anthropicBedrockRegion: String? = null,
    val anthropicBedrockBaseUrl: String? = null,
    val anthropicBedrockProfile: String? = null,

    val enableBraveSearch: Boolean = false,
    val braveApiKey: String? = null,
    val enableJinaReader: Boolean = false,
    val jinaApiKey: String? = null,

    val showSystemMessages: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val showTabsAtBottom: Boolean = false,
    val uiScale: Float = 1.0f,
    val fontScale: Float = 1.0f,

    val currentLanguageCode: String = "en",

    val currentThemeId: String = "dark",
    val themeOverrideEnabled: Boolean = false,

    val enableErrorSounds: Boolean = false,
    val enableMessageSounds: Boolean = false,
    val enableReadySounds: Boolean = false,
    val soundVolume: Float = 1.0f,

    val showOriginalJson: Boolean = false,
)

@Serializable
enum class ResponseFormat {
    JSON,
    XML_STRUCTURED,
    XML_INLINE,
    PLAIN_TEXT
}
