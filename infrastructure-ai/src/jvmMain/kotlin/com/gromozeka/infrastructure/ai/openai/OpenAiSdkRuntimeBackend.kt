package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.infrastructure.ai.parsers.AssistantResponseParser
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.completions.CompletionUsage
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

@Service
internal class OpenAiSdkRuntimeBackend(
    private val clientFactory: OpenAiSdkClientFactory,
) : AiRuntimeBackend {
    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.OPENAI_API || connectionKind == AiConnection.Kind.OPENAI_COMPATIBLE

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        projectPath: String?
    ): AiRuntime {
        val connectionKind = connection.kind
        return OpenAiSdkRuntime(
            connectionKind = connectionKind,
            modelName = modelConfiguration.providerModelId,
            client = clientFactory.createClient(connection),
            messageMapper = OpenAiSdkMessageMapper(connectionKind),
        )
    }
}

private class OpenAiSdkRuntime(
    private val connectionKind: AiConnection.Kind,
    private val modelName: String,
    private val client: OpenAIClient,
    private val messageMapper: OpenAiSdkMessageMapper,
) : AiRuntime {
    private val log = KLoggers.logger(this)

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val params = messageMapper.toCreateParams(modelName, request)
        log.info {
            "Calling OpenAI SDK runtime: connectionKind=$connectionKind model=$modelName " +
                "messages=${request.messages.size} tools=${request.tools.size} " +
                "responseFormat=${request.options.responseFormat.logName()}"
        }

        val response = withContext(Dispatchers.IO) {
            client.chat().completions().create(params)
        }

        return messageMapper.toRuntimeResponse(response, request.options.assistantResponseFormat)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }
}

private class OpenAiSdkMessageMapper(
    private val connectionKind: AiConnection.Kind,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun toCreateParams(modelName: String, request: AiRuntimeRequest): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(modelName)
            .maxCompletionTokens((request.options.maxOutputTokens ?: DEFAULT_MAX_TOKENS).toLong())

        val systemPrompt = request.systemPrompts
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (systemPrompt.isNotBlank()) {
            if (connectionKind == AiConnection.Kind.OPENAI_API) {
                builder.addDeveloperMessage(systemPrompt)
            } else {
                builder.addSystemMessage(systemPrompt)
            }
        }

        val messages = request.messages.flatMap(::toMessageParams)
        require(messages.isNotEmpty()) { "OpenAI SDK request must contain at least one message" }
        builder.messages(messages)

        applyTools(builder, request.tools, request.options.toolChoice)
        applyReasoning(builder, request.options)
        applyResponseFormat(builder, request.options.responseFormat)

        return builder.build()
    }

    fun toRuntimeResponse(
        completion: ChatCompletion,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val choice = completion.choices().firstOrNull()
            ?: return AiRuntimeResponse(
                messages = emptyList(),
                usage = completion.usage().getOrNull()?.toAiUsage(),
                providerMetadata = mapOf("provider" to connectionKind.name, "model" to completion.model()),
            )
        val message = choice.message()
        val content = buildList {
            message.content().getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
                add(assistantBlock(text, assistantResponseFormat))
            }
            message.refusal().getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
                add(plainAssistantBlock(text))
            }
            message.toolCalls().getOrNull().orEmpty().mapNotNull(::toToolCall).forEach(::add)
        }

        val assistantMessages = if (content.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AiAssistantMessage(
                    content = content,
                    metadata = mapOf(
                        "provider" to connectionKind.name,
                        "model" to completion.model(),
                        "completionId" to completion.id(),
                    ),
                )
            )
        }

        return AiRuntimeResponse(
            messages = assistantMessages,
            usage = completion.usage().getOrNull()?.toAiUsage(),
            finishReason = choice.finishReason().asString(),
            providerMetadata = mapOf(
                "provider" to connectionKind.name,
                "model" to completion.model(),
                "completionId" to completion.id(),
                "choiceCount" to completion.choices().size,
            ),
        )
    }

    private fun toMessageParams(message: Conversation.Message): List<ChatCompletionMessageParam> =
        buildList {
            when (message.role) {
                Conversation.Message.Role.USER -> userText(message).takeIf { it.isNotBlank() }?.let { text ->
                    add(ChatCompletionMessageParam.ofUser(userMessageParam(text)))
                }

                Conversation.Message.Role.ASSISTANT -> toAssistantMessageParam(message)?.let(::add)
                Conversation.Message.Role.SYSTEM -> systemText(message).takeIf { it.isNotBlank() }?.let { text ->
                    if (connectionKind == AiConnection.Kind.OPENAI_API) {
                        add(ChatCompletionMessageParam.ofDeveloper(developerMessageParam(text)))
                    } else {
                        add(ChatCompletionMessageParam.ofSystem(systemMessageParam(text)))
                    }
                }
            }

            message.content
                .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                .map(::toolMessageParam)
                .forEach(::add)
        }

    private fun userMessageParam(text: String): ChatCompletionUserMessageParam =
        ChatCompletionUserMessageParam.builder()
            .content(text)
            .build()

    private fun developerMessageParam(text: String): ChatCompletionDeveloperMessageParam =
        ChatCompletionDeveloperMessageParam.builder()
            .content(text)
            .build()

    private fun systemMessageParam(text: String): ChatCompletionSystemMessageParam =
        ChatCompletionSystemMessageParam.builder()
            .content(text)
            .build()

    private fun toAssistantMessageParam(message: Conversation.Message): ChatCompletionMessageParam? {
        val text = message.content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .map { it.structured.fullText } +
            message.content
                .filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()
                .map { it.toOpenAiSdkText() }
        val textContent = text.joinToString("\n")
            .trim()
        val toolCalls = message.content
            .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
            .map(::assistantToolCall)

        if (textContent.isBlank() && toolCalls.isEmpty()) return null

        val builder = com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.builder()
        if (textContent.isNotBlank()) {
            builder.content(textContent)
        }
        toolCalls.forEach(builder::addToolCall)
        return ChatCompletionMessageParam.ofAssistant(builder.build())
    }

    private fun Conversation.Message.ContentItem.ContextCompactionResult.toOpenAiSdkText(): String =
        when (val payload = payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
                "Earlier conversation compact:\n${payload.text.trim()}"

            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                error("OpenAI SDK runtime cannot replay opaque compaction state for provider=${providerScope?.provider}")
        }

    private fun userText(message: Conversation.Message): String {
        val instructionsPrefix = message.instructions
            .joinToString("\n") { it.toXmlLine() }
        val text = message.content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }

        return listOf(instructionsPrefix, text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun systemText(message: Conversation.Message): String {
        if (message.error != null) return ""

        return message.content
            .filterIsInstance<Conversation.Message.ContentItem.System>()
            .joinToString("\n") { it.content }
            .trim()
    }

    private fun toolMessageParam(toolResult: Conversation.Message.ContentItem.ToolResult): ChatCompletionMessageParam =
        ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .toolCallId(toolResult.toolUseId.value)
                .content(toolResultText(toolResult))
                .build()
        )

    private fun assistantToolCall(toolCall: Conversation.Message.ContentItem.ToolCall): ChatCompletionMessageToolCall =
        ChatCompletionMessageToolCall.ofFunction(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(toolCall.id.value)
                .function(
                    ChatCompletionMessageFunctionToolCall.Function.builder()
                        .name(toolCall.call.name)
                        .arguments(toolCall.call.input.toString())
                        .build()
                )
                .build()
        )

    private fun toolResultText(toolResult: Conversation.Message.ContentItem.ToolResult): String =
        toolResult.result.joinToString("\n") { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> "[base64 ${data.mediaType.value}, ${data.data.length} chars]"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
                is Conversation.Message.ContentItem.ToolResult.Data.FileData -> "[file ${data.fileId}]"
            }
        }

    private fun applyTools(
        builder: ChatCompletionCreateParams.Builder,
        tools: List<AiToolCallback>,
        toolChoice: AiToolChoice,
    ) {
        if (tools.isNotEmpty() && toolChoice !is AiToolChoice.None) {
            tools.sortedBy { it.definition.name }
                .map(::toOpenAiTool)
                .forEach(builder::addTool)
        }

        when (toolChoice) {
            AiToolChoice.Auto -> Unit
            AiToolChoice.None -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE)
            AiToolChoice.RequiredAny -> if (tools.isNotEmpty()) {
                builder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED)
            }

            is AiToolChoice.RequiredTool -> builder.toolChoice(
                ChatCompletionNamedToolChoice.builder()
                    .function(
                        ChatCompletionNamedToolChoice.Function.builder()
                            .name(toolChoice.name)
                            .build()
                    )
                    .build()
            )
        }
    }

    private fun toOpenAiTool(callback: AiToolCallback): ChatCompletionFunctionTool {
        val schema = json.parseToJsonElement(callback.definition.inputSchema)
        require(schema is JsonObject) {
            "Tool ${callback.definition.name} input schema must be a JSON object"
        }

        return ChatCompletionFunctionTool.builder()
            .function(
                FunctionDefinition.builder()
                    .name(callback.definition.name)
                    .description(callback.definition.description)
                    .parameters(
                        FunctionParameters.builder()
                            .putAllAdditionalProperties(schema.toOpenAiProperties())
                            .build()
                    )
                    .strict(false)
                    .build()
            )
            .build()
    }

    private fun applyReasoning(
        builder: ChatCompletionCreateParams.Builder,
        options: AiRuntimeOptions,
    ) {
        options.reasoning?.effort?.let { effort ->
            builder.reasoningEffort(effort.toOpenAiReasoningEffort())
        }
    }

    private fun applyResponseFormat(
        builder: ChatCompletionCreateParams.Builder,
        responseFormat: AiResponseFormat,
    ) {
        if (responseFormat !is AiResponseFormat.JsonSchema) return

        val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
            .putAllAdditionalProperties(responseFormat.schema.toOpenAiProperties())
            .build()
        val jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
            .name(responseFormat.name)
            .schema(schema)
            .strict(responseFormat.strict)
            .apply {
                responseFormat.description?.takeIf { it.isNotBlank() }?.let(::description)
            }
            .build()

        builder.responseFormat(
            ResponseFormatJsonSchema.builder()
                .jsonSchema(jsonSchema)
                .build()
        )
    }

    private fun toToolCall(toolCall: ChatCompletionMessageToolCall): Conversation.Message.ContentItem.ToolCall? =
        toolCall.function().getOrNull()?.let { functionCall ->
            val function = functionCall.function()
            val arguments = function.arguments()
            val input = runCatching { json.parseToJsonElement(arguments) }
                .getOrElse { JsonObject(mapOf("raw" to JsonPrimitive(arguments))) }

            Conversation.Message.ContentItem.ToolCall(
                id = Conversation.Message.ContentItem.ToolCall.Id(functionCall.id()),
                call = Conversation.Message.ContentItem.ToolCall.Data(
                    name = function.name(),
                    input = input,
                ),
                state = Conversation.Message.BlockState.COMPLETE,
            )
        }

    private fun assistantBlock(
        text: String,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): Conversation.Message.ContentItem.AssistantMessage =
        Conversation.Message.ContentItem.AssistantMessage(
            structured = AssistantResponseParser.parse(text, assistantResponseFormat),
            state = Conversation.Message.BlockState.COMPLETE,
        )

    private fun plainAssistantBlock(text: String): Conversation.Message.ContentItem.AssistantMessage =
        Conversation.Message.ContentItem.AssistantMessage(
            structured = Conversation.Message.StructuredText(fullText = text),
            state = Conversation.Message.BlockState.COMPLETE,
        )

    private fun AiReasoningEffort.toOpenAiReasoningEffort(): ReasoningEffort =
        when (this) {
            AiReasoningEffort.LOW -> ReasoningEffort.LOW
            AiReasoningEffort.MEDIUM -> ReasoningEffort.MEDIUM
            AiReasoningEffort.HIGH -> ReasoningEffort.HIGH
            AiReasoningEffort.MAX -> ReasoningEffort.XHIGH
        }

    private fun CompletionUsage.toAiUsage(): AiUsage {
        val cachedTokens = promptTokensDetails().getOrNull()?.cachedTokens()?.getOrNull() ?: 0L
        val reasoningTokens = completionTokensDetails().getOrNull()?.reasoningTokens()?.getOrNull() ?: 0L
        val visibleCompletionTokens = (completionTokens() - reasoningTokens).coerceAtLeast(0L)

        return AiUsage(
            promptTokens = (promptTokens() - cachedTokens).coerceAtLeast(0L).toIntClamped(),
            completionTokens = visibleCompletionTokens.toIntClamped(),
            thinkingTokens = reasoningTokens.toIntClamped(),
            cacheReadTokens = cachedTokens.toIntClamped(),
        )
    }

    private fun JsonObject.toOpenAiProperties(): Map<String, JsonValue> =
        mapValues { (_, value) -> value.toOpenAiJsonValue() }

    private fun JsonElement.toOpenAiJsonValue(): JsonValue =
        JsonValue.from(toJsonCompatibleValue())

    private fun JsonElement.toJsonCompatibleValue(): Any? =
        when (this) {
            JsonNull -> null
            is JsonObject -> mapValues { (_, value) -> value.toJsonCompatibleValue() }
            is JsonArray -> map { it.toJsonCompatibleValue() }
            is JsonPrimitive -> when {
                isString -> contentOrNull
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> contentOrNull
            }
        }

    private fun Long.toIntClamped(): Int =
        coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    companion object {
        private const val DEFAULT_MAX_TOKENS = 8192
    }
}

private fun AiResponseFormat.logName(): String =
    when (this) {
        AiResponseFormat.Text -> "text"
        is AiResponseFormat.JsonSchema -> "json_schema:$name"
    }
