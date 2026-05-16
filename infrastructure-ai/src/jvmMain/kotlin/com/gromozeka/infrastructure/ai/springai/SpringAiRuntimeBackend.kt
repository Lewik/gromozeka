package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.stereotype.Service

@Service
internal class SpringAiRuntimeBackend(
    private val chatModelFactory: ChatModelFactory,
    private val messageMapper: SpringAiMessageMapper,
) : AiRuntimeBackend {

    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind != AiConnection.Kind.OPENAI_SUBSCRIPTION &&
            connectionKind != AiConnection.Kind.ANTHROPIC_API &&
            connectionKind != AiConnection.Kind.ANTHROPIC_BEDROCK

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        projectPath: String?
    ): AiRuntime {
        return SpringAiRuntime(
            connectionKind = connection.kind,
            chatModel = chatModelFactory.getChatModel(connection, modelConfiguration, projectPath),
            messageMapper = messageMapper
        )
    }
}

private class SpringAiRuntime(
    private val connectionKind: AiConnection.Kind,
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
        if (options.responseFormat !is AiResponseFormat.Text) {
            log.warn { "Connection kind $connectionKind does not expose structured response format via Spring AI runtime options yet" }
        }

        return when (connectionKind) {
            AiConnection.Kind.OPENAI_API,
            AiConnection.Kind.OPENAI_COMPATIBLE -> {
                val builder = OpenAiChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(options.toolContext)

                options.maxOutputTokens?.let(builder::maxCompletionTokens)

                when (val toolChoice = options.toolChoice) {
                    AiToolChoice.Auto -> builder.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.AUTO)
                    AiToolChoice.None -> builder.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.NONE)
                    AiToolChoice.RequiredAny -> builder.toolChoice("required")
                    is AiToolChoice.RequiredTool -> {
                        builder.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(toolChoice.name))
                    }
                }

                builder.build()
            }

            else -> {
                if (options.toolChoice !is AiToolChoice.Auto && options.toolChoice !is AiToolChoice.None) {
                    log.warn { "Connection kind $connectionKind does not expose forced tool choice via Spring AI runtime options" }
                }

                if (options.toolChoice is AiToolChoice.None && toolCallbacks.isNotEmpty()) {
                    log.warn { "Connection kind $connectionKind does not expose tool_choice=none via Spring AI runtime options; dropping tools for this call" }
                }

                val builder = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false)
                    .toolContext(options.toolContext)

                if (options.toolChoice !is AiToolChoice.None) {
                    builder
                        .toolCallbacks(toolCallbacks)
                        .toolNames(toolNames)
                }

                builder.build()
            }
        }
    }
}
