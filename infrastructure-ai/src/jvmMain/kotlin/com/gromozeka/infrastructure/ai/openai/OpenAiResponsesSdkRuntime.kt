package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningDisplay
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
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.Reasoning
import com.openai.models.ResponsesModel
import com.openai.models.ReasoningEffort as OpenAiReasoningEffort
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseFunctionWebSearch
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.ToolChoiceFunction
import com.openai.models.responses.ToolChoiceOptions
import com.openai.models.responses.WebSearchTool
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.jvm.optionals.getOrNull

internal class OpenAiResponsesSdkRuntime(
    private val connectionId: String,
    private val modelConfigurationId: String,
    private val modelName: String,
    private val webSearchEnabled: Boolean,
    private val client: OpenAIClient,
    private val messageMapper: OpenAiResponsesMessageMapper = OpenAiResponsesMessageMapper(
        connectionId = connectionId,
        modelConfigurationId = modelConfigurationId,
        modelName = modelName,
    ),
) : AiRuntime {
    private val log = KLoggers.logger(this)

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val params = messageMapper.toCreateParams(modelName, webSearchEnabled, request)
        log.info {
            "Calling OpenAI Responses API: model=$modelName messages=${request.messages.size} " +
                "functionTools=${request.tools.size} webSearch=$webSearchEnabled " +
                "responseFormat=${request.options.responseFormat.logName()}"
        }
        val response = withContext(Dispatchers.IO) {
            client.responses().create(params)
        }
        return messageMapper.toRuntimeResponse(response, request.options.assistantResponseFormat)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }
}

internal class OpenAiResponsesMessageMapper(
    private val connectionId: String,
    private val modelConfigurationId: String,
    private val modelName: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun toCreateParams(
        modelName: String,
        webSearchEnabled: Boolean,
        request: AiRuntimeRequest,
    ): ResponseCreateParams {
        val input = request.messages.flatMap(::toInputItems)
        require(input.isNotEmpty()) { "OpenAI Responses request must contain at least one input item" }

        val builder = ResponseCreateParams.builder()
            .model(modelName)
            .inputOfResponse(input)
            .maxOutputTokens((request.options.maxOutputTokens ?: DEFAULT_MAX_TOKENS).toLong())
            .parallelToolCalls(true)
            .store(false)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)

        request.systemPrompts
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .takeIf(String::isNotBlank)
            ?.let(builder::instructions)
        (request.options.toolContext["promptCacheKey"] as? String)
            ?.takeIf(String::isNotBlank)
            ?.let(builder::promptCacheKey)

        applyTools(builder, request.tools, request.options.toolChoice, webSearchEnabled)
        applyReasoning(builder, request.options)
        applyResponseFormat(builder, request.options.responseFormat)
        return builder.build()
    }

    fun toRuntimeResponse(
        response: Response,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val outputItems = response.output()
        val messages = outputItems.mapNotNull { item ->
            when {
                item.isMessage() -> item.asMessage().toAssistantMessage(assistantResponseFormat)
                item.isFunctionCall() -> item.asFunctionCall().toAssistantMessage()
                item.isReasoning() -> item.asReasoning().let { reasoning ->
                    val thinking = buildList {
                        reasoning.summary().map { it.text().trim() }.filter(String::isNotBlank).forEach(::add)
                        reasoning.content().getOrNull().orEmpty()
                            .map { it.text().trim() }
                            .filter(String::isNotBlank)
                            .forEach(::add)
                    }.joinToString("\n").trim()
                    val signature = reasoning.encryptedContent().getOrNull()
                    if (thinking.isBlank() && signature.isNullOrBlank()) {
                        null
                    } else {
                        AiAssistantMessage(
                            content = if (thinking.isBlank()) {
                                emptyList()
                            } else {
                                listOf(
                                    Conversation.Message.ContentItem.Thinking(
                                        thinking = thinking,
                                        signature = signature,
                                        state = Conversation.Message.BlockState.COMPLETE,
                                    )
                                )
                            },
                            metadata = signature?.let {
                                mapOf(
                                    OPENAI_API_REASONING_ITEMS_METADATA_KEY to JsonArray(
                                        listOf(reasoning.toReplayJson())
                                    )
                                )
                            }.orEmpty(),
                        )
                    }
                }

                else -> null
            }
        }
        val model = response.model().providerModelId()

        val sourceUrls = outputItems
            .filter { it.isWebSearchCall() }
            .flatMap { it.asWebSearchCall().sourceUrls() }

        return AiRuntimeResponse(
            messages = appendWebSources(messages, sourceUrls),
            usage = response.usage().getOrNull()?.toAiUsage(),
            finishReason = response.status().getOrNull()?.asString(),
            providerMetadata = mapOf(
                "provider" to "OPENAI_API",
                "model" to model,
                "connectionId" to connectionId,
                "modelConfigurationId" to modelConfigurationId,
                "responseId" to response.id(),
            ),
        )
    }

    private fun toInputItems(message: Conversation.Message): List<ResponseInputItem> = buildList {
        when (message.role) {
            Conversation.Message.Role.USER -> userText(message).takeIf(String::isNotBlank)?.let { text ->
                add(easyMessage(EasyInputMessage.Role.USER, text))
            }

            Conversation.Message.Role.ASSISTANT -> {
                addAll(message.toReasoningInputItems())
                val text = message.content.mapNotNull { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                        is Conversation.Message.ContentItem.ContextCompactionResult -> item.readableText()
                        else -> null
                    }
                }.filter(String::isNotBlank).joinToString("\n").trim()
                if (text.isNotBlank()) {
                    val builder = EasyInputMessage.builder()
                        .content(text)
                        .role(EasyInputMessage.Role.ASSISTANT)
                    message.providerMetadata["phase"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let { builder.phase(EasyInputMessage.Phase.of(it)) }
                    add(ResponseInputItem.ofEasyInputMessage(builder.build()))
                }
                message.content.filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                    .map(::functionCallItem)
                    .forEach(::add)
            }

            Conversation.Message.Role.SYSTEM -> systemText(message).takeIf(String::isNotBlank)?.let { text ->
                add(easyMessage(EasyInputMessage.Role.DEVELOPER, text))
            }
        }

        message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
            .map(::functionCallOutputItem)
            .forEach(::add)
    }

    private fun easyMessage(role: EasyInputMessage.Role, text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .content(text)
                .role(role)
                .build()
        )

    private fun functionCallItem(toolCall: Conversation.Message.ContentItem.ToolCall): ResponseInputItem =
        ResponseInputItem.ofFunctionCall(
            ResponseFunctionToolCall.builder()
                .callId(toolCall.id.value)
                .name(toolCall.call.name)
                .arguments(toolCall.call.input.toString())
                .build()
        )

    private fun functionCallOutputItem(toolResult: Conversation.Message.ContentItem.ToolResult): ResponseInputItem =
        ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(toolResult.toolUseId.value)
                .output(toolResultText(toolResult))
                .build()
        )

    private fun Conversation.Message.toReasoningInputItems(): List<ResponseInputItem> {
        if (providerMetadata["provider"]?.jsonPrimitive?.contentOrNull != "OPENAI_API") return emptyList()
        if (providerMetadata["connectionId"]?.jsonPrimitive?.contentOrNull != connectionId) return emptyList()
        if (providerMetadata["model"]?.jsonPrimitive?.contentOrNull != modelName) return emptyList()

        val items = providerMetadata[OPENAI_API_REASONING_ITEMS_METADATA_KEY] as? JsonArray
            ?: return emptyList()
        return items.map { element ->
            val item = element.jsonObject
            val id = item["id"]?.jsonPrimitive?.contentOrNull
                ?: error("OpenAI API reasoning replay item is missing id")
            val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull
                ?: error("OpenAI API reasoning replay item is missing encrypted_content")
            val summaries = (item["summary"] as? JsonArray).orEmpty().map { summaryElement ->
                val text = summaryElement.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("OpenAI API reasoning summary is missing text")
                ResponseReasoningItem.Summary.builder().text(text).build()
            }
            ResponseInputItem.ofReasoning(
                ResponseReasoningItem.builder()
                    .id(id)
                    .summary(summaries)
                    .encryptedContent(encryptedContent)
                    .build()
            )
        }
    }

    private fun userText(message: Conversation.Message): String {
        val instructions = message.instructions.joinToString("\n") { it.toXmlLine() }
        val text = message.content.filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }
        return listOf(instructions, text).filter(String::isNotBlank).joinToString("\n").trim()
    }

    private fun systemText(message: Conversation.Message): String {
        if (message.error != null) return ""
        return message.content.filterIsInstance<Conversation.Message.ContentItem.System>()
            .joinToString("\n") { it.content }
            .trim()
    }

    private fun Conversation.Message.ContentItem.ContextCompactionResult.readableText(): String? =
        when (val value = payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
                "Earlier conversation compact:\n${value.text.trim()}"

            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState -> null
        }

    private fun toolResultText(toolResult: Conversation.Message.ContentItem.ToolResult): String =
        toolResult.result.joinToString("\n") { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                    "[base64 ${data.mediaType.value}, ${data.data.length} chars]"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
                is Conversation.Message.ContentItem.ToolResult.Data.FileData -> "[file ${data.fileId}]"
            }
        }

    private fun applyTools(
        builder: ResponseCreateParams.Builder,
        tools: List<AiToolCallback>,
        toolChoice: AiToolChoice,
        webSearchEnabled: Boolean,
    ) {
        val toolsAllowed = toolChoice !is AiToolChoice.None
        if (toolsAllowed) {
            tools.sortedBy { it.definition.name }.map(::functionTool).forEach(builder::addTool)
            if (webSearchEnabled) {
                builder.addTool(WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build())
                builder.addInclude(ResponseIncludable.WEB_SEARCH_CALL_ACTION_SOURCES)
            }
        }

        when (toolChoice) {
            AiToolChoice.Auto -> Unit
            AiToolChoice.None -> builder.toolChoice(ToolChoiceOptions.NONE)
            AiToolChoice.RequiredAny -> if (tools.isNotEmpty() || webSearchEnabled) {
                builder.toolChoice(ToolChoiceOptions.REQUIRED)
            }

            is AiToolChoice.RequiredTool -> builder.toolChoice(
                ToolChoiceFunction.builder().name(toolChoice.name).build()
            )
        }
    }

    private fun functionTool(callback: AiToolCallback): FunctionTool {
        val schema = json.parseToJsonElement(callback.definition.inputSchema)
        require(schema is JsonObject) { "Tool ${callback.definition.name} input schema must be a JSON object" }
        return FunctionTool.builder()
            .name(callback.definition.name)
            .description(callback.definition.description)
            .parameters(
                FunctionTool.Parameters.builder()
                    .putAllAdditionalProperties(schema.toOpenAiProperties())
                    .build()
            )
            .strict(false)
            .build()
    }

    private fun applyReasoning(builder: ResponseCreateParams.Builder, options: AiRuntimeOptions) {
        val config = options.reasoning ?: return
        val reasoning = Reasoning.builder()
        config.effort?.let { reasoning.effort(it.toOpenAiReasoningEffort()) }
        if (config.display != null && config.display != AiReasoningDisplay.OMITTED) {
            reasoning.summary(Reasoning.Summary.AUTO)
        }
        builder.reasoning(reasoning.build())
    }

    private fun applyResponseFormat(builder: ResponseCreateParams.Builder, responseFormat: AiResponseFormat) {
        if (responseFormat !is AiResponseFormat.JsonSchema) return
        val schema = ResponseFormatTextJsonSchemaConfig.Schema.builder()
            .putAllAdditionalProperties(responseFormat.schema.toOpenAiProperties())
            .build()
        val format = ResponseFormatTextJsonSchemaConfig.builder()
            .name(responseFormat.name)
            .schema(schema)
            .strict(responseFormat.strict)
            .apply {
                responseFormat.description?.takeIf(String::isNotBlank)?.let(::description)
            }
            .build()
        builder.text(ResponseTextConfig.builder().format(format).build())
    }

    private fun ResponseOutputMessage.toAssistantMessage(
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiAssistantMessage? {
        val content = content().mapNotNull { item ->
            when {
                item.isOutputText() -> item.asOutputText().toAssistantBlock(assistantResponseFormat)
                item.isRefusal() -> item.asRefusal().refusal().trim().takeIf(String::isNotBlank)?.let(::plainAssistantBlock)
                else -> null
            }
        }
        if (content.isEmpty()) return null
        return AiAssistantMessage(
            content = content,
            metadata = buildMap {
                put("messageId", id())
                phase().getOrNull()?.asString()?.let { put("phase", it) }
            },
        )
    }

    private fun ResponseOutputText.toAssistantBlock(
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): Conversation.Message.ContentItem.AssistantMessage {
        val structured = AssistantResponseParser.parse(text().trim(), assistantResponseFormat)
        val citations = annotations().filter { it.isUrlCitation() }
            .map { it.asUrlCitation() }
            .distinctBy { it.url() }
        val fullText = if (citations.isEmpty()) {
            structured.fullText
        } else {
            val sources = citations.joinToString("\n") { citation ->
                "- [${citation.title().escapeMarkdownLinkText()}](${citation.url()})"
            }
            "${structured.fullText}\n\nSources:\n$sources"
        }
        return Conversation.Message.ContentItem.AssistantMessage(
            structured = structured.copy(fullText = fullText),
            state = Conversation.Message.BlockState.COMPLETE,
        )
    }

    private fun ResponseReasoningItem.toReplayJson(): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(id()))
            put(
                "summary",
                JsonArray(
                    summary().map { summary ->
                        JsonObject(mapOf("text" to JsonPrimitive(summary.text())))
                    }
                )
            )
            encryptedContent().getOrNull()?.let { put("encrypted_content", JsonPrimitive(it)) }
        }
    )

    private fun ResponseFunctionToolCall.toAssistantMessage(): AiAssistantMessage {
        val input = runCatching { json.parseToJsonElement(arguments()) }
            .getOrElse { JsonObject(mapOf("raw" to JsonPrimitive(arguments()))) }
        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id(callId()),
                    call = Conversation.Message.ContentItem.ToolCall.Data(name = name(), input = input),
                    state = Conversation.Message.BlockState.COMPLETE,
                )
            )
        )
    }

    private fun plainAssistantBlock(text: String): Conversation.Message.ContentItem.AssistantMessage =
        Conversation.Message.ContentItem.AssistantMessage(
            structured = Conversation.Message.StructuredText(fullText = text),
            state = Conversation.Message.BlockState.COMPLETE,
        )

    private fun ResponseUsage.toAiUsage(): AiUsage {
        val cachedTokens = inputTokensDetails().cachedTokens()
        val reasoningTokens = outputTokensDetails().reasoningTokens()
        return AiUsage(
            promptTokens = (inputTokens() - cachedTokens).coerceAtLeast(0).toIntClamped(),
            completionTokens = (outputTokens() - reasoningTokens).coerceAtLeast(0).toIntClamped(),
            thinkingTokens = reasoningTokens.toIntClamped(),
            cacheReadTokens = cachedTokens.toIntClamped(),
        )
    }

    private fun AiReasoningEffort.toOpenAiReasoningEffort(): OpenAiReasoningEffort = when (this) {
        AiReasoningEffort.LOW -> OpenAiReasoningEffort.LOW
        AiReasoningEffort.MEDIUM -> OpenAiReasoningEffort.MEDIUM
        AiReasoningEffort.HIGH -> OpenAiReasoningEffort.HIGH
        AiReasoningEffort.XHIGH,
        AiReasoningEffort.MAX -> OpenAiReasoningEffort.XHIGH
    }

    private fun JsonObject.toOpenAiProperties(): Map<String, JsonValue> =
        mapValues { (_, value) -> JsonValue.from(value.toJsonCompatibleValue()) }

    private fun JsonElement.toJsonCompatibleValue(): Any? = when (this) {
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

    private fun String.escapeMarkdownLinkText(): String = replace("[", "\\[").replace("]", "\\]")

    private fun ResponseFunctionWebSearch.sourceUrls(): List<String> {
        val action = action()
        return when {
            action.isSearch() -> action.asSearch().sources().getOrNull().orEmpty().map { it.url() }
            action.isOpenPage() -> action.asOpenPage().url().getOrNull()?.let(::listOf).orEmpty()
            action.isFind() -> listOf(action.asFind().url())
            else -> emptyList()
        }
    }

    private fun Long.toIntClamped(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val DEFAULT_MAX_TOKENS = 8192
    }
}

private const val OPENAI_API_REASONING_ITEMS_METADATA_KEY = "openAiApiReasoningItems"

internal fun ResponsesModel.providerModelId(): String = when {
    isString() -> asString()
    isChat() -> asChat().asString()
    isOnly() -> asOnly().asString()
    else -> error("OpenAI Responses API returned an unsupported model value: ${_json().getOrNull()}")
}

internal fun appendWebSources(
    messages: List<AiAssistantMessage>,
    sourceUrls: List<String>,
): List<AiAssistantMessage> {
    val existingText = messages
        .flatMap { it.content }
        .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
        .joinToString("\n") { it.structured.fullText }
    val missingSources = sourceUrls
        .filter(String::isNotBlank)
        .distinct()
        .filterNot(existingText::contains)
    if (missingSources.isEmpty()) return messages

    val messageIndex = messages.indexOfLast { message ->
        message.content.any { it is Conversation.Message.ContentItem.AssistantMessage }
    }
    if (messageIndex < 0) return messages
    val message = messages[messageIndex]
    val contentIndex = message.content.indexOfLast { it is Conversation.Message.ContentItem.AssistantMessage }
    val content = message.content[contentIndex] as Conversation.Message.ContentItem.AssistantMessage
    val sources = missingSources.joinToString("\n") { "- <$it>" }
    val updatedContent = content.copy(
        structured = content.structured.copy(
            fullText = "${content.structured.fullText}\n\nSources:\n$sources"
        )
    )

    return messages.toMutableList().apply {
        this[messageIndex] = message.copy(
            content = message.content.toMutableList().apply { this[contentIndex] = updatedContent }
        )
    }
}

private fun AiResponseFormat.logName(): String = when (this) {
    AiResponseFormat.Text -> "text"
    is AiResponseFormat.JsonSchema -> "json_schema:$name"
}
