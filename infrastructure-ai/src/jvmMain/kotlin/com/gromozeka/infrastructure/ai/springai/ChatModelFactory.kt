package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.factory.AiApiFactory
import io.micrometer.observation.ObservationRegistry
import klog.KLoggers
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.claudecode.ClaudeCodeChatModel
import org.springframework.ai.claudecode.ClaudeCodeChatOptions
import org.springframework.ai.claudecode.api.ClaudeCodeApi
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.GoogleGenAiGeminiChatModelWithWorkarounds
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.management.ModelManagementOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatModelFactory(
    private val aiApiFactory: AiApiFactory,
    private val toolCallingManager: ToolCallingManager,
    private val settingsProvider: SettingsProvider,
) {
    private val log = KLoggers.logger(this)

    private data class CacheKey(
        val connectionId: AiConnection.Id,
        val modelConfigurationId: AiModelConfiguration.Id,
        val projectPath: String?,
    )

    private val cache = ConcurrentHashMap<CacheKey, ChatModel>()

    fun getChatModel(connection: AiConnection, modelConfiguration: AiModelConfiguration, projectPath: String?): ChatModel {
        val key = CacheKey(connection.id, modelConfiguration.id, projectPath)
        return cache.getOrPut(key) {
            createChatModel(connection, modelConfiguration, projectPath)
        }
    }

    private fun createChatModel(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        projectPath: String?,
    ): ChatModel {
        val retryTemplate = RetryTemplate.builder().maxAttempts(3).build()
        val observationRegistry = ObservationRegistry.NOOP
        val connectionKind = connection.kind
        val modelName = modelConfiguration.providerModelId
        
        return when (connectionKind) {
            AiConnection.Kind.OLLAMA -> {
                val ollamaApi = aiApiFactory.createOllamaApi(connection)

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
                    DefaultToolExecutionEligibilityPredicate(),
                    retryTemplate
                )
            }

            AiConnection.Kind.GEMINI_API -> {
                val geminiClient = aiApiFactory.createGeminiClient()
                requireNotNull(geminiClient) {
                    "Gemini not configured - missing google-credentials.json file"
                }

                val options = GoogleGenAiChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .maxOutputTokens(8192)
                    .thinkingBudget(8192)
                    .includeExtendedUsageMetadata(true)
                    .internalToolExecutionEnabled(false)
                    .build()

                GoogleGenAiGeminiChatModelWithWorkarounds(
                    geminiClient,
                    options,
                    toolCallingManager,
                    retryTemplate,
                    observationRegistry
                )
            }

            AiConnection.Kind.OPENAI_API,
            AiConnection.Kind.OPENAI_COMPATIBLE -> {
                val openAiApi = aiApiFactory.createOpenAiApi(connection)

                OpenAiChatModel(
                    openAiApi,
                    OpenAiChatOptions
                        .builder()
                        .model(modelName)
                        .temperature(0.7)
                        .maxCompletionTokens(8192)
                        .build(),
                    toolCallingManager,
                    retryTemplate,
                    observationRegistry,

                    )
            }

            AiConnection.Kind.OPENAI_SUBSCRIPTION -> {
                error("OPEN_AI_SUBSCRIPTION is handled by a dedicated runtime backend, not Spring AI ChatModelFactory")
            }

            AiConnection.Kind.ANTHROPIC_API -> {
                error("ANTHROPIC is handled by AnthropicSdkRuntimeBackend, not Spring AI ChatModelFactory")
            }

            AiConnection.Kind.ANTHROPIC_BEDROCK -> {
                error("ANTHROPIC_BEDROCK is handled by AnthropicSdkRuntimeBackend, not Spring AI ChatModelFactory")
            }

            AiConnection.Kind.CLAUDE_CODE -> {
                val workingDir = projectPath ?: System.getProperty("user.dir")
                val claudePath = ClaudePathDetector.detectClaudePath()

                val mcpConfigPath = java.io.File(settingsProvider.homeDirectory, "mcp-sse-config.json").absolutePath
                log.info("Using MCP config: $mcpConfigPath")

                val api = ClaudeCodeApi.builder()
                    .cliPath(claudePath)
                    .workingDirectory(workingDir)
                    .devMode(settingsProvider.mode == AppMode.DEV)
                    .mcpConfigPath(mcpConfigPath)
                    .build()

                val options = ClaudeCodeChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .maxTokens(8192)
                    .thinkingBudgetTokens(0)
                    .cliPath(claudePath)
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
