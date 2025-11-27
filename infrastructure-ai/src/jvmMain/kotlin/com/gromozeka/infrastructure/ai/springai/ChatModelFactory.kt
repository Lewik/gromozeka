package com.gromozeka.infrastructure.ai.springai

import com.google.genai.Client
import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.service.ChatModelProvider
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
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import com.gromozeka.domain.service.SettingsProvider

@Service
class ChatModelFactory(
    @Autowired(required = false) private val ollamaApi: OllamaApi?,
    @Autowired(required = false) private val geminiClient: Client?,
    private val toolCallingManager: ToolCallingManager,
    private val settingsProvider: SettingsProvider,
    private val applicationContext: ApplicationContext,
) : ChatModelProvider {
    private val log = KLoggers.logger(this)

    private data class CacheKey(
        val provider: AIProvider,
        val modelName: String,
        val projectPath: String?,
    )

    private val cache = ConcurrentHashMap<CacheKey, ChatModel>()

    /**
     * Получает все зарегистрированные ToolCallback бины из Spring контекста.
     * Использует ту же стратегию, что и SpringContextToolCallbackResolver.
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
        val observationRegistry = ObservationRegistry.create()

        return when (provider) {
            AIProvider.OLLAMA -> {
                requireNotNull(ollamaApi) {
                    "Ollama not configured - ensure Ollama is installed and running at http://localhost:11434"
                }

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
                requireNotNull(geminiClient) {
                    "Gemini not configured - missing google-credentials.json file"
                }

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
                val claudePath = ClaudePathDetector.detectClaudePath()
                
                // Get MCP config path from settings
                val mcpConfigPath = java.io.File(settingsProvider.homeDirectory, "mcp-sse-config.json").absolutePath
                log.info("Using MCP config: $mcpConfigPath")

                val api = ClaudeCodeApi.builder()
                    .cliPath(claudePath)
                    .workingDirectory(workingDir)
                    .devMode(settingsProvider.mode == AppMode.DEV)
                    .mcpConfigPath(mcpConfigPath)
                    .build()

                // Получаем все зарегистрированные tool callbacks
                val toolCallbacks = getAllToolCallbacks()
                log.info("Creating ClaudeCodeChatModel with ${toolCallbacks.size} tool callbacks for model $modelName")

                val options = ClaudeCodeChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .maxTokens(8192)
                    .thinkingBudgetTokens(8192)
                    .cliPath(claudePath)
                    .workingDirectory(workingDir)
                    .internalToolExecutionEnabled(false)
                    .build()
                
                // Устанавливаем toolCallbacks через setter (Builder не имеет этого метода)
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
