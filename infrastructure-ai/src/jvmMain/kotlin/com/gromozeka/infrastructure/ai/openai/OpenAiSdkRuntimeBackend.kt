package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.infrastructure.ai.runtime.SdkHttpCallExecutor
import com.gromozeka.infrastructure.ai.anthropic.AnthropicThinkingParameters
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiModelBehavior
import com.gromozeka.infrastructure.ai.runtime.ModelGenerationPolicy
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.projectedMessages
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiContextUsage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
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
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import kotlinx.serialization.json.jsonObject
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

@Service
internal class OpenAiSdkRuntimeBackend(
    private val clientFactory: OpenAiSdkClientFactory,
    private val admission: com.gromozeka.domain.service.AiCallAdmission,
) : AiRuntimeBackend {
    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.OPENAI_API || connectionKind == AiConnection.Kind.OPENAI_COMPATIBLE

    override fun capabilities(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
    ) = AiRuntimeCapabilities(
        supportsNativeCompaction = connection.kind == AiConnection.Kind.OPENAI_API,
    )

    override fun createRuntime(
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?
    ): AiRuntime {
        val connection = runtime.connection
        val modelConfiguration = runtime.modelConfiguration
        val executor = SdkHttpCallExecutor(connection, modelConfiguration.defaultParameters.timeoutSeconds, admission = admission, modelConfiguration = modelConfiguration) { end ->
            clientFactory.createInferenceClient(connection, end)
        }
        return when (connection) {
            is AiConnection.OpenAiApi -> OpenAiResponsesSdkRuntime(
                connectionId = connection.id.value,
                modelConfigurationId = modelConfiguration.id.value,
                modelName = modelConfiguration.providerModelId,
                webSearchEnabled = connection.webSearchEnabled,
                executor = executor,
                generation = ModelGenerationPolicy(runtime.modelSpec),
            )

            is AiConnection.OpenAiCompatible -> OpenAiSdkRuntime(
                connectionKind = connection.kind,
                modelName = modelConfiguration.providerModelId,
                executor = executor,
                messageMapper = OpenAiSdkMessageMapper(connection.kind, connection.id.value, modelConfiguration.providerModelId, ModelGenerationPolicy(runtime.modelSpec)),
            )

            else -> error("OpenAI SDK runtime does not support connection ${connection.kind}")
        }
    }
}

private class OpenAiSdkRuntime(
    private val connectionKind: AiConnection.Kind,
    private val modelName: String,
    private val executor: SdkHttpCallExecutor<OpenAIClient>,
    private val messageMapper: OpenAiSdkMessageMapper,
) : AiRuntime {
    private val log = KLoggers.logger(this)

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val options = executor.withDeadline(request.options)
        val params = messageMapper.toCreateParams(modelName, request)
        log.info {
            "Calling OpenAI SDK runtime: connectionKind=$connectionKind model=$modelName " +
                "messages=${request.messages.size} tools=${request.tools.size} " +
                "responseFormat=${request.options.responseFormat.logName()}"
        }

        val response = executor.call(options) { client, remaining ->
            client.async().chat().completions().create(params, com.openai.core.RequestOptions.builder().timeout(remaining).build())
                .thenApply { messageMapper.toRuntimeResponse(it, request.options.assistantResponseFormat) }
        }

        return response
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }
}

internal class OpenAiSdkMessageMapper(
    private val connectionKind: AiConnection.Kind,
    private val connectionId: String? = null,
    private val modelName: String? = null,
    private val generation: ModelGenerationPolicy = ModelGenerationPolicy(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun toCreateParams(modelName: String, request: AiRuntimeRequest): ChatCompletionCreateParams {
        require(this.modelName == null || this.modelName == modelName) {
            "Bound OpenAI message mapper cannot change its target model"
        }
        val reasoning = generation.reasoning(request.options, request.tools.isNotEmpty())
        val builder = ChatCompletionCreateParams.builder().model(modelName)
        val maxOutputTokens = request.options.maxOutputTokens ?: if (
            generation.behavior.chatCompletionsReasoning == AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING &&
            reasoning?.mode == AiReasoningMode.TOKEN_BUDGET
        ) generation.requiredOutputTokens(request) else null
        maxOutputTokens?.let { limit ->
            when (generation.behavior.chatCompletionsTokenLimit) {
                AiModelBehavior.ChatCompletionsTokenLimit.MAX_COMPLETION_TOKENS -> builder.maxCompletionTokens(limit.toLong())
                AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS -> builder.maxTokens(limit.toLong())
                AiModelBehavior.ChatCompletionsTokenLimit.OMIT -> Unit
            }
        }

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

        val messages = request.projectedMessages().flatMap { toMessageParams(it, modelName) }
        require(messages.isNotEmpty()) { "OpenAI SDK request must contain at least one message" }
        messages.forEach(builder::addMessage)

        applyTools(builder, request.tools, request.options.toolChoice)
        applyReasoning(builder, reasoning, maxOutputTokens)
        applyResponseFormat(builder, request.options.responseFormat)

        return builder.build()
    }

    fun toRuntimeResponse(
        completion: ChatCompletion,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val usage = completion.usage().getOrNull()?.toAiUsage()
        val choice = completion.choices().firstOrNull()
            ?: return AiRuntimeResponse(
                messages = emptyList(),
                outcome = AiStepOutcome.FAILED,
                usage = usage,
                contextUsage = usage?.let { AiContextUsage(it.totalInputTokens, it.totalOutputTokens) },
                providerMetadata = mapOf("provider" to connectionKind.name, "model" to completion.model()),
            )
        val message = choice.message()
        val rawMessage = json.parseToJsonElement(com.openai.core.jsonMapper().writeValueAsString(message)).jsonObject
        val outcome = when (choice.finishReason().asString()) {
            "stop" -> when {
                message.refusal().isPresent -> AiStepOutcome.REFUSED
                message.toolCalls().getOrNull().orEmpty().isNotEmpty() -> AiStepOutcome.FAILED
                else -> AiStepOutcome.COMPLETE
            }
            "tool_calls" -> AiStepOutcome.TOOL_CALLS
            "length" -> AiStepOutcome.INCOMPLETE
            "content_filter" -> AiStepOutcome.REFUSED
            else -> AiStepOutcome.FAILED
        }
        val content = buildList {
            addAll(thinkingContent(rawMessage))
            message.content().getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
                add(if (outcome != AiStepOutcome.COMPLETE) Conversation.Message.ContentItem.AssistantMessage(
                    structured = AssistantResponseParser.parseProgress(text, assistantResponseFormat),
                ) else assistantBlock(text, assistantResponseFormat))
            }
            message.refusal().getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
                add(plainAssistantBlock(text))
            }
            if (outcome == AiStepOutcome.TOOL_CALLS) {
                message.toolCalls().getOrNull().orEmpty().mapNotNull(::toToolCall).forEach(::add)
            }
        }

        val assistantMessages = if (content.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AiAssistantMessage(
                    content = content,
                    metadata = mapOf(
                        "provider" to connectionKind.name,
                        "model" to (modelName ?: completion.model()),
                        "reportedModel" to completion.model(),
                        "completionId" to completion.id(),
                        "connectionId" to connectionId,
                        "openaiCompatibleMessage" to rawMessage,
                    ),
                )
            )
        }

        return AiRuntimeResponse(
            messages = assistantMessages,
            usage = usage,
            contextUsage = usage?.let { AiContextUsage(it.totalInputTokens, it.totalOutputTokens) },
            finishReason = choice.finishReason().asString(),
            outcome = outcome,
            providerMetadata = mapOf(
                "provider" to connectionKind.name,
                "model" to (modelName ?: completion.model()),
                "reportedModel" to completion.model(),
                "completionId" to completion.id(),
                "choiceCount" to completion.choices().size,
            ),
        )
    }

    private fun thinkingContent(message: JsonObject): List<Conversation.Message.ContentItem.Thinking> {
        val aggregate = message.thinkingString("reasoning_content")
        val rawBlocks = message["thinking_blocks"]
        if (rawBlocks == null || rawBlocks == JsonNull) {
            return aggregate?.let { listOf(Conversation.Message.ContentItem.Thinking(it)) }.orEmpty()
        }
        require(rawBlocks is JsonArray) { "OpenAI-compatible thinking_blocks must be an array" }
        if (rawBlocks.isEmpty()) {
            return aggregate?.let { listOf(Conversation.Message.ContentItem.Thinking(it)) }.orEmpty()
        }
        val structured = rawBlocks.map { block ->
            require(block is JsonObject) { "OpenAI-compatible thinking block must be an object" }
            when (block.thinkingString("type")) {
                "thinking" -> Conversation.Message.ContentItem.Thinking(
                    thinking = requireNotNull(block.thinkingString("thinking")) { "Thinking block must contain thinking text" },
                    signature = block.thinkingString("signature"),
                )
                "redacted_thinking" -> Conversation.Message.ContentItem.Thinking(
                    thinking = "",
                    signature = requireNotNull(block.thinkingString("data")) { "Redacted thinking block must contain data" },
                    kind = Conversation.Message.ContentItem.Thinking.Kind.REDACTED,
                )
                "reasoning" -> {
                    require(!block.thinkingString("id").isNullOrBlank()) { "OpenAI-compatible reasoning block must contain a nonblank id" }
                    val encrypted = block.thinkingString("encrypted_content")
                    require(!encrypted.isNullOrBlank()) { "OpenAI-compatible reasoning block must contain nonblank encrypted_content" }
                    Conversation.Message.ContentItem.Thinking(thinking = "", signature = encrypted)
                }
                else -> throw IllegalArgumentException("Unsupported OpenAI-compatible thinking block type")
            }
        }
        // Aggregate text can duplicate the blocks, or be the only readable summary of signed-empty blocks.
        // Replay uses the untouched raw message, not this display projection.
        return buildList {
            if (!aggregate.isNullOrBlank() && structured.all { it.thinking.isBlank() }) {
                add(Conversation.Message.ContentItem.Thinking(aggregate))
            }
            addAll(structured)
        }
    }

    private fun JsonObject.thinkingString(name: String): String? {
        val value = this[name] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "OpenAI-compatible reasoning field '$name' must be a string" }
        return value.content
    }

    private fun toMessageParams(message: Conversation.Message, targetModelName: String): List<ChatCompletionMessageParam> =
        buildList {
            when (message.role) {
                Conversation.Message.Role.USER -> userMessageParam(message)?.let {
                    add(ChatCompletionMessageParam.ofUser(it))
                }

                Conversation.Message.Role.ASSISTANT -> toAssistantMessageParam(message, targetModelName)?.let(::add)
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

            toolResultAttachmentMessageParam(message)?.let {
                add(ChatCompletionMessageParam.ofUser(it))
            }
        }

    private fun userMessageParam(message: Conversation.Message): ChatCompletionUserMessageParam? {
        val parts = buildList {
            userText(message).takeIf(String::isNotBlank)?.let { text ->
                add(
                    ChatCompletionContentPart.ofText(
                        ChatCompletionContentPartText.builder().text(text).build()
                    )
                )
            }
            message.content.filterIsInstance<Conversation.Message.ContentItem.ImageItem>()
                .map { it.toOpenAiChatImagePart() }
                .forEach(::add)
            message.content.filterIsInstance<Conversation.Message.ContentItem.DocumentItem>()
                .map { it.toOpenAiChatFilePart() }
                .forEach(::add)
        }
        return parts.takeIf { it.isNotEmpty() }?.let {
            ChatCompletionUserMessageParam.builder()
                .contentOfArrayOfContentParts(parts)
                .build()
        }
    }

    private fun Conversation.Message.ContentItem.ImageItem.toOpenAiChatImagePart(): ChatCompletionContentPart {
        val url = when (val imageSource = source) {
            is Conversation.Message.ImageSource.Base64ImageSource ->
                "data:${imageSource.mediaType};base64,${imageSource.data}"
            is Conversation.Message.ImageSource.UrlImageSource -> imageSource.url
            is Conversation.Message.ImageSource.FileImageSource ->
                error("OpenAI-compatible chat does not accept an image file id")
        }
        val imageUrl = ChatCompletionContentPartImage.ImageUrl.builder()
            .url(url)
            .detail(ChatCompletionContentPartImage.ImageUrl.Detail.AUTO)
            .build()
        return ChatCompletionContentPart.ofImageUrl(
            ChatCompletionContentPartImage.builder().imageUrl(imageUrl).build()
        )
    }

    private fun Conversation.Message.ContentItem.DocumentItem.toOpenAiChatFilePart(): ChatCompletionContentPart =
        when (val documentSource = source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource -> {
                val fileObject = ChatCompletionContentPart.File.FileObject.builder()
                    .filename(documentSource.fileName)
                    .fileData("data:${documentSource.mediaType};base64,${documentSource.data}")
                    .build()
                ChatCompletionContentPart.ofFile(
                    ChatCompletionContentPart.File.builder().file(fileObject).build()
                )
            }
        }

    private fun developerMessageParam(text: String): ChatCompletionDeveloperMessageParam =
        ChatCompletionDeveloperMessageParam.builder()
            .content(text)
            .build()

    private fun systemMessageParam(text: String): ChatCompletionSystemMessageParam =
        ChatCompletionSystemMessageParam.builder()
            .content(text)
            .build()

    private fun toAssistantMessageParam(message: Conversation.Message, targetModelName: String): ChatCompletionMessageParam? {
        val metadata = message.providerMetadata
        val raw = metadata["openaiCompatibleMessage"] as? JsonObject
        if (raw != null && !connectionId.isNullOrBlank() &&
            metadata["provider"]?.jsonPrimitive?.contentOrNull == connectionKind.name &&
            metadata["connectionId"]?.jsonPrimitive?.contentOrNull == connectionId &&
            metadata["model"]?.jsonPrimitive?.contentOrNull == targetModelName
        ) {
            val replay = JsonObject(raw.filterKeys { it in setOf("role", "content", "tool_calls", "reasoning_content", "thinking_blocks", "refusal") })
            return ChatCompletionMessageParam.ofAssistant(com.openai.core.jsonMapper().readValue(
                replay.toString(), com.openai.models.chat.completions.ChatCompletionAssistantMessageParam::class.java,
            ))
        }
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
            .mapNotNull { it.userInputTextOrNull() }
            .joinToString("\n")

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

    private fun toolResultAttachmentMessageParam(
        message: Conversation.Message,
    ): ChatCompletionUserMessageParam? {
        val parts = buildList {
            message.content
                .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                .flatMap { toolResult ->
                    toolResult.result.mapNotNull { data ->
                        when (data) {
                            is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                                if (data.mediaType.value.startsWith("image/")) {
                                    Conversation.Message.ContentItem.ImageItem(
                                        Conversation.Message.ImageSource.Base64ImageSource(
                                            data = data.data,
                                            mediaType = data.mediaType.value,
                                        )
                                    ).toOpenAiChatImagePart()
                                } else {
                                    Conversation.Message.ContentItem.DocumentItem(
                                        Conversation.Message.DocumentSource.Base64DocumentSource(
                                            data = data.data,
                                            mediaType = data.mediaType.value,
                                            fileName = data.fileName ?: "tool-output.bin",
                                        )
                                    ).toOpenAiChatFilePart()
                                }

                            is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
                                data.takeIf { it.mediaType?.value?.startsWith("image/") == true }
                                    ?.let {
                                        Conversation.Message.ContentItem.ImageItem(
                                            Conversation.Message.ImageSource.UrlImageSource(it.url)
                                        ).toOpenAiChatImagePart()
                                    }

                            is Conversation.Message.ContentItem.ToolResult.Data.Text,
                            is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData -> null
                        }
                    }
                }
                .forEach(::add)
        }
        if (parts.isEmpty()) return null

        val description = ChatCompletionContentPart.ofText(
            ChatCompletionContentPartText.builder()
                .text("Binary content returned by the preceding tool calls.")
                .build()
        )
        return ChatCompletionUserMessageParam.builder()
            .contentOfArrayOfContentParts(listOf(description) + parts)
            .build()
    }

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
                is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                    error("OpenAI received an unmaterialized tool artifact: ${data.artifact.id.value}")
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
        reasoning: AiReasoningConfig?,
        maxOutputTokens: Int?,
    ) {
        when (generation.behavior.chatCompletionsReasoning) {
            AiModelBehavior.ChatCompletionsReasoning.REASONING_EFFORT -> {
                require(reasoning?.mode == AiReasoningMode.DISABLED || reasoning?.display == null) {
                    "Chat Completions effort encoding does not support reasoning display control"
                }
                reasoning?.openAiEffort()?.let(builder::reasoningEffort)
            }
            AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING -> {
                require(reasoning?.mode != AiReasoningMode.TOKEN_BUDGET ||
                    generation.behavior.chatCompletionsTokenLimit != AiModelBehavior.ChatCompletionsTokenLimit.OMIT) {
                    "Anthropic thinking token budget requires an output token limit"
                }
                AnthropicThinkingParameters.create(reasoning, generation.behavior, maxOutputTokens)?.let { thinking ->
                    val value = com.openai.core.jsonMapper().readValue(com.anthropic.core.jsonMapper().writeValueAsString(thinking), Any::class.java)
                    builder.putAdditionalBodyProperty("thinking", JsonValue.from(value))
                }
                reasoning?.effort?.let {
                    builder.putAdditionalBodyProperty("output_config", JsonValue.from(mapOf("effort" to it.name.lowercase())))
                }
                if (generation.behavior.anthropicThinkingPrefixDrop) {
                    builder.putAdditionalHeader("anthropic-beta", AnthropicThinkingParameters.PREFIX_DROP_BETA)
                }
            }
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
            val input = json.parseToJsonElement(arguments)
            require(input is JsonObject) { "OpenAI-compatible function arguments must be a JSON object" }

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
    }
}

private fun AiResponseFormat.logName(): String =
    when (this) {
        AiResponseFormat.Text -> "text"
        is AiResponseFormat.JsonSchema -> "json_schema:$name"
    }
