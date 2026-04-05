package com.gromozeka.domain.model

/**
 * AI model context window size registry.
 *
 * Maintains a lookup table of maximum context window sizes (in tokens)
 * for various AI models. Context window determines how much conversation
 * history and context can be included in a single request.
 *
 * Supports both full model names and common short aliases for convenience.
 *
 * Usage:
 * ```kotlin
 * val windowSize = ModelContextWindows.getContextWindow("claude-sonnet-4-5")
 * // Returns 200_000 tokens
 * ```
 */
object ModelContextWindows {
    private val windows = mapOf(
        // Claude 4.6 - latest with 1M context
        "claude-opus-4-6" to 1_000_000,
        "claude-sonnet-4-6" to 1_000_000,
        
        // Claude 4.5 - full names
        "claude-sonnet-4-5-20250929" to 200_000,
        "claude-haiku-4-5-20251001" to 200_000,
        
        // Claude 3.x - full names
        "claude-3-5-sonnet-20241022" to 200_000,
        "claude-3-5-haiku-20241022" to 200_000,
        "claude-3-opus-20240229" to 200_000,

        // Claude - short names (4.6 models)
        "claude-sonnet-4-5" to 200_000,
        "sonnet" to 1_000_000,
        "opus" to 1_000_000,
        "haiku" to 200_000,

        // Gemini
        "gemini-2.0-flash-exp" to 1_000_000,
        "gemini-1.5-pro" to 2_000_000,
        "gemini-1.5-flash" to 1_000_000,

        // OpenAI
        "gpt-4-turbo" to 128_000,
        "gpt-4o" to 128_000,
        "gpt-5.3-codex" to 400_000,
        "gpt-5.4" to 1_050_000,
    )

    /**
     * Returns context window size for given model.
     *
     * @param modelId model identifier (full name or short alias)
     * @return context window size in tokens, or null if model not found
     */
    fun getContextWindow(modelId: String): Int? = windows[modelId]
}
