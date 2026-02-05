package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.service.McpToolProvider
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.factory.AiApiFactory
import io.micrometer.observation.ObservationRegistry
import klog.KLoggers
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.EnhancedAnthropicChatModel
import org.springframework.ai.anthropic.PromptCachingFixedAnthropicChatModel
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
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
import org.springframework.ai.tool.ToolCallback
import org.springframework.context.ApplicationContext
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatModelFactory(
    private val aiApiFactory: AiApiFactory,
    private val toolCallingManager: ToolCallingManager,
    private val settingsProvider: SettingsProvider,
    private val applicationContext: ApplicationContext,
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolProvider: McpToolProvider,
    private val oauthConfigService: com.gromozeka.infrastructure.ai.oauth.OAuthConfigService,
) : ChatModelProvider {
    private val log = KLoggers.logger(this)

    private data class CacheKey(
        val provider: AIProvider,
        val modelName: String,
        val projectPath: String?,
    )

    private val cache = ConcurrentHashMap<CacheKey, ChatModel>()

    /**
     * Retrieves all registered ToolCallback beans from Spring context.
     * Uses the same strategy as SpringContextToolCallbackResolver.
     */
    private fun getAllToolCallbacks(): List<ToolCallback> {
        val toolCallbacks = applicationContext.getBeansOfType(ToolCallback::class.java).values.toList()
        log.info("Retrieved ${toolCallbacks.size} ToolCallback beans from ApplicationContext: ${toolCallbacks.map { it.toolDefinition.name() }}")
        return toolCallbacks
    }

    override fun getChatModel(provider: AIProvider, modelName: String, projectPath: String?): ChatModel {
        val key = CacheKey(provider, modelName, projectPath)
        return cache.getOrPut(key) {
            createChatModel(provider, modelName, projectPath)
        }
    }

    @Deprecated("Use getChatModel instead", ReplaceWith("getChatModel(provider, modelName, projectPath)"))
    fun get(provider: AIProvider, modelName: String, projectPath: String?): ChatModel =
        getChatModel(provider, modelName, projectPath)

    private fun createChatModel(
        provider: AIProvider,
        modelName: String,
        projectPath: String?,
    ): ChatModel {
        val retryTemplate = RetryTemplate.builder().maxAttempts(3).build()
        val observationRegistry = ObservationRegistry.NOOP
        val allToolCallbacks = toolCallbacks + mcpToolProvider.getToolCallbacks()
        val allToolNames = allToolCallbacks.map { it.toolDefinition.name() }.toSet()



        return when (provider) {
            AIProvider.OLLAMA -> {
                val ollamaApi = aiApiFactory.createOllamaApi()

                val options = OllamaChatOptions.builder()
                    .model(modelName)
                    .numCtx(131072)
                    .numPredict(8192)
                    .temperature(0.7)
                    .toolCallbacks(allToolCallbacks)
                    .toolNames(allToolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
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

            AIProvider.GEMINI -> {
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
                    .toolCallbacks(allToolCallbacks)
                    .toolNames(allToolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .build()

                GoogleGenAiGeminiChatModelWithWorkarounds(
                    geminiClient,
                    options,
                    toolCallingManager,
                    retryTemplate,
                    observationRegistry
                )
            }

            AIProvider.OPEN_AI -> {
                val openAiApi = aiApiFactory.createOpenAiApi()

                OpenAiChatModel(
                    openAiApi,
                    OpenAiChatOptions
                        .builder()
                        .model("deepseek-coder-v2-lite-instruct")
                        .temperature(0.7)
                        .maxCompletionTokens(8192)
                        .build(),
                    toolCallingManager,
                    retryTemplate,
                    observationRegistry,

                    )
            }

            AIProvider.ANTHROPIC -> {
                val anthropicApi = aiApiFactory.createAnthropicApi()

                val thinkingBudget = 10000
                val options = AnthropicChatOptions
                    .builder()
                    .model("claude-haiku-4-5-20251001")
                    // NOTE: temperature is NOT compatible with thinking mode
                    // Using top_p instead (allowed range: 0.95-1.0 when thinking enabled)
                    .topP(0.95)
                    .maxTokens(64000)
                    .thinking(
                        org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType.ENABLED,
                        thinkingBudget
                    )
                    .cacheOptions(
                        AnthropicCacheOptions.builder()
                            .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                            .build()
                    )
                    .toolCallbacks(allToolCallbacks)
                    .toolNames(allToolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .build()

                log.info("Using prompt caching fixed Anthropic chat model with improved cache strategy")
                log.info("Extended thinking ENABLED: budget_tokens=$thinkingBudget (min 1024, recommended 10k-32k)")
                log.info("Using top_p=0.95 (temperature NOT compatible with thinking mode)")
                log.info("Thinking will appear as separate content blocks before text responses")
                PromptCachingFixedAnthropicChatModel(
                    anthropicApi,
                    options,
                    toolCallingManager,
                    retryTemplate,
                    observationRegistry
                )
            }

            AIProvider.CLAUDE_CODE -> {
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

                val toolCallbacks = getAllToolCallbacks()
                log.info("Creating ClaudeCodeChatModel with ${toolCallbacks.size} tool callbacks for model $modelName")

                val options = ClaudeCodeChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .maxTokens(8192)
                    .thinkingBudgetTokens(0)
                    .cliPath(claudePath)
                    .workingDirectory(workingDir)
                    .internalToolExecutionEnabled(false)
                    .build()

                options.toolCallbacks = toolCallbacks

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
