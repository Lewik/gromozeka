package com.gromozeka.domain.service

import com.gromozeka.domain.model.AIProvider
import org.springframework.ai.chat.model.ChatModel

/**
 * Provider for AI chat models.
 *
 * Abstraction over different AI provider implementations (Claude, Gemini, Ollama).
 * Infrastructure layer provides concrete implementations using Spring AI ChatModelFactory.
 *
 * This is JVM-specific domain interface because it uses Spring AI types.
 * Spring AI is treated as framework dependency (similar to Spring Framework itself).
 *
 * @see AIProvider for supported providers
 */
interface ChatModelProvider {
    /**
     * Gets chat model instance for specified provider and model.
     *
     * Returns Spring AI ChatModel configured for the requested provider.
     * Model selection and configuration handled by infrastructure.
     *
     * @param provider AI provider type (CLAUDE_CODE, GEMINI, OLLAMA)
     * @param modelName model identifier (e.g., "claude-3-5-sonnet-20241022")
     * @param projectPath optional project path for context and tool configuration
     * @return configured chat model ready for streaming
     * @throws IllegalArgumentException if provider or model not supported
     */
    fun getChatModel(
        provider: AIProvider,
        modelName: String,
        projectPath: String?
    ): ChatModel
}
