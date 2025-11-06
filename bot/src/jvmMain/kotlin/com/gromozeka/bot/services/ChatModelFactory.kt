package com.gromozeka.bot.services

import com.google.cloud.vertexai.VertexAI
import com.gromozeka.bot.settings.AIProvider
import io.micrometer.observation.ObservationRegistry
import klog.KLoggers
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.claudecode.ClaudeCodeChatModel
import org.springframework.ai.claudecode.ClaudeCodeChatOptions
import org.springframework.ai.claudecode.api.ClaudeCodeApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.ai.ollama.management.ModelManagementOptions
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions
import org.springframework.ai.vertexai.gemini.schema.VertexToolCallingManager
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatModelFactory(
    private val claudeCodeApi: ClaudeCodeApi,
    private val ollamaApi: OllamaApi,
    private val vertexAI: VertexAI,
    private val toolCallingManager: ToolCallingManager,
) {
    private val log = KLoggers.logger(this)

    private data class CacheKey(
        val provider: AIProvider,
        val modelName: String,
        val projectPath: String?,
    )

    private val cache = ConcurrentHashMap<CacheKey, ChatModel>()

    fun get(provider: AIProvider, modelName: String, projectPath: String?): ChatModel {
        val key = CacheKey(provider, modelName, projectPath)
        return cache.getOrPut(key) {
            createChatModel(provider, modelName, projectPath)
        }
    }

    private fun createChatModel(
        provider: AIProvider,
        modelName: String,
        projectPath: String?
    ): ChatModel {
        val retryTemplate = RetryTemplate.builder().maxAttempts(3).build()
        val observationRegistry = ObservationRegistry.create()

        return when (provider) {
            AIProvider.CLAUDE_CODE -> {
                val options = ClaudeCodeChatOptions.builder()
                    .model(modelName)
                    .maxTokens(8192)
                    .temperature(0.7)
                    .thinkingBudgetTokens(8000)
                    .useXmlToolFormat(true)
                    .internalToolExecutionEnabled(false)
                    .projectPath(projectPath)
                    .build()

                ClaudeCodeChatModel.builder()
                    .claudeCodeApi(claudeCodeApi)
                    .defaultOptions(options)
                    .toolCallingManager(toolCallingManager)
                    .retryTemplate(retryTemplate)
                    .observationRegistry(observationRegistry)
                    .build()
            }

            AIProvider.OLLAMA -> {
                val options = OllamaOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .internalToolExecutionEnabled(false)
                    .build()

                OllamaChatModel(
                    ollamaApi,
                    options,
                    toolCallingManager,
                    observationRegistry,
                    ModelManagementOptions.defaults(),
                    org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate(),
                    retryTemplate
                )
            }

            AIProvider.GEMINI -> {
                val options = VertexAiGeminiChatOptions
                    .builder()
                    .model(modelName)
                    .temperature(0.7)
                    .internalToolExecutionEnabled(false)
                    .build()

                VertexAiGeminiChatModel(
                    vertexAI,
                    options,
                    VertexToolCallingManager(toolCallingManager),
                    retryTemplate,
                    observationRegistry
                )
            }
        }
    }
}
