package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.stereotype.Service

@Service
class SpringAiRuntimeProvider(
    private val chatModelFactory: ChatModelFactory,
    private val messageMapper: SpringAiMessageMapper,
) : AiRuntimeProvider {

    override fun getRuntime(
        provider: AIProvider,
        modelName: String,
        projectPath: String?
    ): AiRuntime {
        return SpringAiRuntime(
            provider = provider,
            chatModel = chatModelFactory.getChatModel(provider, modelName, projectPath),
            messageMapper = messageMapper
        )
    }
}

private class SpringAiRuntime(
    private val provider: AIProvider,
    private val chatModel: ChatModel,
    private val messageMapper: SpringAiMessageMapper,
) : AiRuntime {
    private val log = KLoggers.logger(this)

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val prompt = Prompt(
            messageMapper.toPromptMessages(request.systemPrompts, request.messages),
            createChatOptions(request.options, request)
        )

        val chatResponse = withContext(Dispatchers.IO) {
            chatModel.call(prompt)
        }

        return messageMapper.toRuntimeResponse(chatResponse)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }

    private fun createChatOptions(
        options: AiRuntimeOptions,
        request: AiRuntimeRequest
    ): ChatOptions {
        val toolCallbacks = request.tools.map(::SpringAiToolCallbackAdapter)
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }.toSet()

        return when (provider) {
            AIProvider.ANTHROPIC -> {
                val builder = AnthropicChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(options.toolContext)
                    .cacheOptions(
                        AnthropicCacheOptions.builder()
                            .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                            .build()
                    )

                options.maxTokens?.let(builder::maxTokens)

                options.thinking?.let { thinking ->
                    when (thinking.type) {
                        "adaptive", "enabled" -> {
                            val budgetTokens = thinking.budgetTokens ?: (options.maxTokens?.let { it / 2 } ?: 16_000)
                            builder.thinking(AnthropicApi.ThinkingType.ENABLED, budgetTokens)
                        }

                        "disabled" -> builder.thinking(AnthropicApi.ThinkingType.DISABLED, null)
                    }
                }

                val httpHeaders = mutableMapOf<String, String>()
                options.thinking?.let { httpHeaders["X-Gromozeka-Thinking-Type"] = it.type }
                options.outputConfig?.let { httpHeaders["X-Gromozeka-Effort"] = it.effort }
                if (httpHeaders.isNotEmpty()) {
                    builder.httpHeaders(httpHeaders)
                }

                when (val toolChoice = options.toolChoice) {
                    AiToolChoice.Auto -> Unit
                    AiToolChoice.RequiredAny -> builder.toolChoice(AnthropicApi.ToolChoiceAny())
                    is AiToolChoice.RequiredTool -> builder.toolChoice(AnthropicApi.ToolChoiceTool(toolChoice.name))
                }

                builder.build()
            }

            AIProvider.OPEN_AI -> {
                val builder = OpenAiChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(options.toolContext)

                options.maxTokens?.let(builder::maxCompletionTokens)

                when (val toolChoice = options.toolChoice) {
                    AiToolChoice.Auto -> builder.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.AUTO)
                    AiToolChoice.RequiredAny -> builder.toolChoice("required")
                    is AiToolChoice.RequiredTool -> {
                        builder.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(toolChoice.name))
                    }
                }

                builder.build()
            }

            else -> {
                if (options.toolChoice !is AiToolChoice.Auto) {
                    log.warn { "Provider $provider does not expose forced tool choice via Spring AI runtime options" }
                }

                ToolCallingChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(options.toolContext)
                    .build()
            }
        }
    }
}
