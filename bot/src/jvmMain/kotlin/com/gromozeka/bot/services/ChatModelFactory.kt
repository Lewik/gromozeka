package com.gromozeka.bot.services

import com.google.genai.Client
import com.gromozeka.bot.settings.AIProvider
import io.micrometer.observation.ObservationRegistry
import klog.KLoggers
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.claudecode.ClaudeCodeChatModel
import org.springframework.ai.claudecode.ClaudeCodeChatOptions
import org.springframework.ai.claudecode.api.ClaudeCodeApi
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.GoogleGenAiGeminiChatModelWithWorkarounds
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.management.ModelManagementOptions
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatModelFactory(
    private val ollamaApi: OllamaApi,
    private val geminiClient: Client,
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
        projectPath: String?,
    ): ChatModel {
        val retryTemplate = RetryTemplate.builder().maxAttempts(3).build()
        val observationRegistry = ObservationRegistry.create()

        return when (provider) {
            AIProvider.OLLAMA -> {
                val options = OllamaChatOptions.builder()
                    .model(modelName)
                    .numCtx(131072)
                    .numPredict(8192)
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
                val options = GoogleGenAiChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .maxOutputTokens(8192)
                    .thinkingBudget(8192)
                    .includeExtendedUsageMetadata(true)
                    .build()

                GoogleGenAiGeminiChatModelWithWorkarounds(
                    geminiClient,
                    options,
                    toolCallingManager,
                    retryTemplate,
                    observationRegistry
                )
            }

            AIProvider.CLAUDE_CODE -> {
                val workingDir = projectPath ?: System.getProperty("user.dir")

                val api = ClaudeCodeApi.builder()
                    .cliPath("claude")
                    .workingDirectory(workingDir)
                    .build()

                val options = ClaudeCodeChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .maxTokens(8192)
                    .thinkingBudgetTokens(8192)
                    .cliPath("claude")
                    .workingDirectory(workingDir)
                    .internalToolExecutionEnabled(false)
                    .build()

                ClaudeCodeChatModel.builder()
                    .claudeCodeApi(api)
                    .defaultOptions(options)
                    .toolCallingManager(toolCallingManager)
                    .retryTemplate(retryTemplate)
                    .observationRegistry(observationRegistry)
                    .build()
            }
        }
    }
}
