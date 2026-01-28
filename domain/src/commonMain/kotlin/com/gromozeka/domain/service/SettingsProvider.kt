package com.gromozeka.domain.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AppMode

/**
 * Provides access to application configuration settings.
 *
 * Centralized configuration for all Gromozeka modules.
 * Infrastructure layer implements this reading from config files, environment variables, or user preferences.
 *
 * Settings are read-only from domain perspective - modifications happen through UI settings panel.
 */
interface SettingsProvider {
    /**
     * Main language for speech-to-text recognition.
     *
     * ISO 639-1 language code (e.g., "en", "ru", "de").
     * Used by STT service to configure recognition model.
     */
    val sttMainLanguage: String

    /**
     * Text-to-speech model identifier.
     *
     * Provider-specific model name (e.g., "tts-1", "tts-1-hd" for OpenAI).
     * Determines voice quality and synthesis speed.
     */
    val ttsModel: String

    /**
     * Text-to-speech voice identifier.
     *
     * Provider-specific voice name (e.g., "alloy", "echo", "nova" for OpenAI).
     * Determines voice characteristics (gender, accent, tone).
     */
    val ttsVoice: String

    /**
     * Text-to-speech playback speed multiplier.
     *
     * Range: 0.25 to 4.0 (typical: 1.0 = normal speed).
     * Values < 1.0 slow down, > 1.0 speed up.
     */
    val ttsSpeed: Float

    /**
     * AI provider for chat completions.
     *
     * Determines which LLM is used for conversations (Claude, Gemini, OpenAI, etc.).
     * See [AIProvider] for available options.
     */
    val aiProvider: AIProvider

    /**
     * Application operating mode.
     *
     * Determines UI layout and feature availability (CHAT, VOICE, AGENT, etc.).
     * See [AppMode] for available modes.
     */
    val mode: AppMode

    /**
     * Absolute path to Gromozeka home directory.
     *
     * Contains configuration files, logs, temporary files, database.
     * Typically: ~/.gromozeka/ or user-configured location.
     */
    val homeDirectory: String

    /**
     * Enable Brave Search integration.
     *
     * When true, BraveWebSearchTool and BraveLocalSearchTool are available.
     * Requires valid [braveApiKey].
     */
    val enableBraveSearch: Boolean

    /**
     * API key for Brave Search.
     *
     * Required when [enableBraveSearch] is true.
     * Null when Brave Search is disabled or key not configured.
     * Obtained from: https://brave.com/search/api/
     */
    val braveApiKey: String?

    /**
     * Enable Jina Reader integration.
     *
     * When true, JinaReadUrlTool is available for URL-to-Markdown conversion.
     * Requires valid [jinaApiKey].
     */
    val enableJinaReader: Boolean

    /**
     * API key for Jina Reader.
     *
     * Required when [enableJinaReader] is true.
     * Null when Jina Reader is disabled or key not configured.
     * Obtained from: https://jina.ai/reader/
     */
    val jinaApiKey: String?

    val anthropicApiKey: String?
        get() = null

    val openAiApiKey: String?
        get() = null

    val ollamaBaseUrl: String
        get() = "http://localhost:11434"

    /**
     * Enable vector storage for conversation messages.
     *
     * When true, conversation messages are stored in Neo4j vector database for semantic search.
     * Enables conversation search via unified_search tool.
     */
    val vectorStorageEnabled: Boolean
        get() = true

    /**
     * Auto-remember threads to vector memory after each assistant response.
     *
     * When true, automatically calls VectorMemoryService.rememberThread() after final assistant message.
     * Only applies when [vectorStorageEnabled] is true.
     * Enables incremental conversation indexing without manual intervention.
     */
    val autoRememberThreads: Boolean
        get() = true
}
