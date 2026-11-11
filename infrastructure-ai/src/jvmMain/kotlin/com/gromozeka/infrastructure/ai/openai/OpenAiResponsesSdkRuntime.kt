package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.infrastructure.ai.runtime.SdkHttpCallExecutor
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.infrastructure.ai.runtime.ModelGenerationPolicy
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.ai.projectedMessages
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiContextUsage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.infrastructure.ai.parsers.AssistantResponseParser
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.models.Reasoning
import com.openai.models.ResponsesModel
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCompactParams
import com.openai.models.responses.CompactedResponse
import com.openai.models.responses.inputtokens.InputTokenCountParams
import com.gromozeka.domain.model.ai.AiContextInspection
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseFunctionCallOutputItem
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseFunctionWebSearch
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputFileContent
import com.openai.models.responses.ResponseInputImageContent
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputFile
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseInputTextContent
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.ToolChoiceFunction
import com.openai.models.responses.ToolChoiceOptions
import com.openai.models.responses.WebSearchTool
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
    private val executor: SdkHttpCallExecutor<OpenAIClient>,
    generation: ModelGenerationPolicy = ModelGenerationPolicy(),
    private val messageMapper: OpenAiResponsesMessageMapper = OpenAiResponsesMessageMapper(
        connectionId = connectionId,
        modelConfigurationId = modelConfigurationId,
        modelName = modelName,
        generation = generation,
    ),
) : AiRuntime {
    override val capabilities = com.gromozeka.domain.model.ai.AiRuntimeCapabilities(supportsNativeCompaction = true)
    private val log = KLoggers.logger(this)

    override suspend fun inspectContext(request: AiRuntimeRequest): AiContextInspection {
        val body = messageMapper.toCreateParams(modelName, webSearchEnabled, request)._body()
        val fields = Json.parseToJsonElement(jsonMapper().writeValueAsString(body)).jsonObject
        val countBody = jsonMapper().readValue(JsonObject(fields.filterKeys {
            it in setOf("model", "input", "instructions", "tools", "tool_choice", "reasoning", "text")
        }).toString(), InputTokenCountParams.Body::class.java)
        val options = executor.withDeadline(request.options.copy(usagePurpose = "CONTEXT_COUNT"))
        return executor.call(options) { client, remaining ->
            client.async().responses().inputTokens().count(InputTokenCountParams.builder().body(countBody).build(),
                com.openai.core.RequestOptions.builder().timeout(remaining).build()).thenApply {
                AiContextInspection(Math.toIntExact(it.inputTokens()), AiContextInspection.Precision.PROVIDER_COUNT, true)
            }
        }
    }

    override suspend fun compact(request: AiRuntimeRequest): AiRuntimeResponse {
        val body = messageMapper.toCreateParams(modelName, webSearchEnabled, request)._body()
        val fields = Json.parseToJsonElement(jsonMapper().writeValueAsString(body)).jsonObject
        val compactBody = jsonMapper().readValue(JsonObject(fields.filterKeys {
            it in setOf("model", "input", "instructions")
        }).toString(), ResponseCompactParams.Body::class.java)
        val options = executor.withDeadline(request.options)
        return executor.call(options) { client, remaining ->
            client.async().responses().compact(ResponseCompactParams.builder().body(compactBody).build(),
                com.openai.core.RequestOptions.builder().timeout(remaining).build())
                .thenApply { messageMapper.toCompactionResponse(it, request) }
        }
    }

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val options = executor.withDeadline(request.options)
        val params = messageMapper.toCreateParams(modelName, webSearchEnabled, request)
        log.info {
            "Calling OpenAI Responses API: model=$modelName messages=${request.messages.size} " +
                "functionTools=${request.tools.size} webSearch=$webSearchEnabled " +
                "responseFormat=${request.options.responseFormat.logName()}"
        }
        val response = executor.call(options) { client, remaining ->
            client.async().responses().create(params, com.openai.core.RequestOptions.builder().timeout(remaining).build())
                .thenApply { messageMapper.toRuntimeResponse(it, request.options.assistantResponseFormat) }
        }
        return response
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }
}

internal class OpenAiResponsesMessageMapper(
    private val connectionId: String,
    private val modelConfigurationId: String,
    private val modelName: String,
    private val generation: ModelGenerationPolicy = ModelGenerationPolicy(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun toCreateParams(
        modelName: String,
        webSearchEnabled: Boolean,
        request: AiRuntimeRequest,
    ): ResponseCreateParams {
        val input = request.projectedMessages("OPENAI_API").flatMap(::toInputItems)
        require(input.isNotEmpty()) { "OpenAI Responses request must contain at least one input item" }

        val builder = ResponseCreateParams.builder()
            .model(modelName)
            .inputOfResponse(input)
            .parallelToolCalls(true)
            .store(false)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)

        request.options.maxOutputTokens?.let { builder.maxOutputTokens(it.toLong()) }

        request.systemPrompts
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .takeIf(String::isNotBlank)
            ?.let(builder::instructions)
        (request.options.toolContext["promptCacheKey"] as? String)
            ?.takeIf(String::isNotBlank)
            ?.let(builder::promptCacheKey)

        val nativeWebSearch = webSearchEnabled &&
            com.gromozeka.domain.tool.ProviderNativeTool.OPENAI_API_WEB_SEARCH.isAllowed(request.options.toolAccess)
        val reasoning = generation.reasoning(request.options, request.tools.isNotEmpty() || nativeWebSearch)
        applyTools(builder, request.tools, request.options.toolChoice, nativeWebSearch)
        applyReasoning(builder, reasoning)
        applyResponseFormat(builder, request.options.responseFormat)
        return builder.build()
    }

    fun toRuntimeResponse(
        response: Response,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val outputItems = response.output()
        val status = response.status().getOrNull()?.asString()
        val outcome = when {
            status == "incomplete" -> AiStepOutcome.INCOMPLETE
            status != "completed" -> AiStepOutcome.FAILED
            outputItems.any { it.isMessage() && it.asMessage().content().any { part -> part.isRefusal() } } -> AiStepOutcome.REFUSED
            outputItems.any { it.isFunctionCall() } -> AiStepOutcome.TOOL_CALLS
            outputItems.lastOrNull { it.isMessage() }?.asMessage()?.phase()?.getOrNull()?.asString() == "commentary" -> AiStepOutcome.CONTINUE
            outputItems.any { it.isCompaction() } && outputItems.none { it.isMessage() } -> AiStepOutcome.CONTINUE
            else -> AiStepOutcome.COMPLETE
        }
        val messages = outputItems.mapNotNull { item ->
            val mapped = when {
                item.isMessage() -> item.asMessage().toAssistantMessage(assistantResponseFormat, outcome != AiStepOutcome.COMPLETE)
                item.isFunctionCall() -> if (outcome == AiStepOutcome.TOOL_CALLS) item.asFunctionCall().toAssistantMessage() else null
                item.isWebSearchCall() -> item.asWebSearchCall().toSearchActivity()
                item.isCompaction() -> item.asCompaction().toCompactionMessage()
                item.isReasoning() -> item.asReasoning().let { reasoning ->
                    val thinking = buildList {
                        reasoning.summary().map { it.text().trim() }.filter(String::isNotBlank).forEach(::add)
                        reasoning.content().getOrNull().orEmpty()
                            .map { it.text().trim() }
                            .filter(String::isNotBlank)
                            .forEach(::add)
                    }.joinToString("\n").trim()
                    val signature = reasoning.encryptedContent().getOrNull()
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.Thinking(
                                thinking = thinking,
                                signature = signature,
                                state = Conversation.Message.BlockState.COMPLETE,
                            )
                        ),
                    )
                }

                else -> AiAssistantMessage(listOf(AssistantResponseParser.providerBlock(
                    json.parseToJsonElement(jsonMapper().writeValueAsString(item)),
                )))
            }
            mapped?.copy(metadata = mapped.metadata + ("openaiOutputItems" to JsonArray(listOf(
                json.parseToJsonElement(jsonMapper().writeValueAsString(item)),
            ))))
        }
        val model = response.model().providerModelId()

        // The answer cites used sources, not every page considered by hosted search.
        // The complete search inventory belongs to the expandable tool activity.
        val sourceUrls = outputItems.filter { it.isMessage() }.flatMap { it.asMessage().content() }
            .filter { it.isOutputText() }.flatMap { it.asOutputText().annotations() }
            .filter { it.isUrlCitation() }.map { it.asUrlCitation().url() }

        val usage = response.usage().getOrNull()?.toAiUsage()
        return AiRuntimeResponse(
            messages = appendWebSources(messages, sourceUrls),
            usage = usage,
            // Billed input tokens describe the pre-compaction request, not the retained window.
            contextUsage = usage?.takeIf { outputItems.none { item -> item.isCompaction() } }
                ?.let { AiContextUsage(it.totalInputTokens, it.totalOutputTokens) },
            finishReason = response.incompleteDetails().getOrNull()?.reason()?.getOrNull()?.asString() ?: status,
            outcome = outcome,
            providerMetadata = mapOf(
                "provider" to "OPENAI_API",
                "model" to modelName,
                "reportedModel" to model,
                "connectionId" to connectionId,
                "modelConfigurationId" to modelConfigurationId,
                "responseId" to response.id(),
            ),
        )
    }

    fun toCompactionResponse(response: CompactedResponse, request: AiRuntimeRequest): AiRuntimeResponse {
        val window = JsonArray(response.output().map { Json.parseToJsonElement(jsonMapper().writeValueAsString(it)) })
        validateCompactionWindow(window)
        return AiRuntimeResponse(
            messages = listOf(AiAssistantMessage(listOf(ContextCompactionResult(
                payload = ContextCompactionResult.Payload.OpaqueProviderState(JsonObject(mapOf("replay_window" to window))),
                origin = ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                strategy = ContextCompactionResult.Strategy.PROVIDER_MANAGED,
                coverage = ContextCompactionResult.Coverage.ALL_PREVIOUS,
                sourceMessageIds = request.messages.map { it.id },
                providerScope = ContextCompactionResult.ProviderScope("OPENAI_API", connectionId, modelConfigurationId, modelName),
            )))),
            usage = response.usage().toAiUsage(), outcome = AiStepOutcome.CONTINUE, finishReason = "compaction",
        )
    }

    private fun com.openai.models.responses.ResponseCompactionItem.toCompactionMessage(): AiAssistantMessage {
        check(id().isNotBlank() && encryptedContent().isNotBlank()) { "OpenAI Responses returned an invalid compaction checkpoint" }
        val replayItem = json.parseToJsonElement(jsonMapper().writeValueAsString(this)).jsonObject
        return AiAssistantMessage(listOf(ContextCompactionResult(
            payload = ContextCompactionResult.Payload.OpaqueProviderState(JsonObject(mapOf("replay_item" to replayItem))),
            origin = ContextCompactionResult.Origin.PROVIDER_AUTO,
            strategy = ContextCompactionResult.Strategy.PROVIDER_MANAGED,
            coverage = ContextCompactionResult.Coverage.ALL_PREVIOUS,
            providerScope = ContextCompactionResult.ProviderScope(
                provider = "OPENAI_API", connectionId = connectionId,
                modelConfigurationId = modelConfigurationId, modelName = modelName,
            ),
        )))
    }

    private fun toInputItems(message: Conversation.Message): List<ResponseInputItem> = buildList {
        val nativeCompactions = message.content.filterIsInstance<ContextCompactionResult>()
            .flatMap { it.nativeInputItems() }
        check(nativeCompactions.isEmpty() || message.role == Conversation.Message.Role.ASSISTANT) {
            "OpenAI Responses compaction checkpoint must be an assistant message"
        }
        val rawItems = message.providerMetadata["openaiOutputItems"] as? JsonArray
        if (nativeCompactions.isEmpty() && message.role == Conversation.Message.Role.ASSISTANT && rawItems != null &&
            message.providerMetadata["provider"]?.jsonPrimitive?.contentOrNull == "OPENAI_API" &&
            message.providerMetadata["connectionId"]?.jsonPrimitive?.contentOrNull == connectionId &&
            message.providerMetadata["model"]?.jsonPrimitive?.contentOrNull == modelName
        ) {
            addAll(rawItems.map { jsonMapper().readValue(it.toString(), ResponseInputItem::class.java) })
            return@buildList
        }
        when (message.role) {
            Conversation.Message.Role.USER -> {
                val content = buildList {
                    userText(message).takeIf(String::isNotBlank)?.let { text ->
                        add(
                            ResponseInputContent.ofInputText(
                                ResponseInputText.builder().text(text).build()
                            )
                        )
                    }
                    message.content.filterIsInstance<Conversation.Message.ContentItem.ImageItem>()
                        .map { it.toOpenAiInputImage() }
                        .map(ResponseInputContent::ofInputImage)
                        .forEach(::add)
                    message.content.filterIsInstance<Conversation.Message.ContentItem.DocumentItem>()
                        .map { it.toOpenAiInputFile() }
                        .map(ResponseInputContent::ofInputFile)
                        .forEach(::add)
                }
                if (content.isNotEmpty()) {
                    add(
                        ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                .contentOfResponseInputMessageContentList(content)
                                .role(EasyInputMessage.Role.USER)
                                .build()
                        )
                    )
                }
            }

            Conversation.Message.Role.ASSISTANT -> {
                addAll(nativeCompactions)
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

    private fun validateCompactionWindow(window: JsonArray) {
        val checkpoint = window.mapNotNull { it as? JsonObject }.singleOrNull { it["type"] == JsonPrimitive("compaction") }
        check(checkpoint != null && !checkpoint["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
            !checkpoint["encrypted_content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "OpenAI compact must return one complete encrypted checkpoint in its replay window"
        }
    }

    private fun ContextCompactionResult.nativeInputItems(): List<ResponseInputItem> {
        val opaque = payload as? ContextCompactionResult.Payload.OpaqueProviderState ?: return emptyList()
        val scope = providerScope
        check(scope?.provider == "OPENAI_API" && scope.connectionId == connectionId && scope.modelName == modelName) {
            "OpenAI Responses compaction checkpoint belongs to another provider, connection or model"
        }
        (opaque.state["replay_window"] as? JsonArray)?.let { window ->
            validateCompactionWindow(window)
            return window.map { jsonMapper().readValue(it.toString(), ResponseInputItem::class.java) }
        }
        val item = opaque.state["replay_item"]?.jsonObject
            ?: error("OpenAI Responses compaction checkpoint has no replay item")
        check(item["type"] == JsonPrimitive("compaction") && !item["encrypted_content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "OpenAI Responses compaction checkpoint has no encrypted state"
        }
        return listOf(jsonMapper().readValue(item.toString(), ResponseInputItem::class.java))
    }

    private fun easyMessage(role: EasyInputMessage.Role, text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .content(text)
                .role(role)
                .build()
        )

    private fun Conversation.Message.ContentItem.ImageItem.toOpenAiInputImage(): ResponseInputImage {
        val builder = ResponseInputImage.builder().detail(ResponseInputImage.Detail.AUTO)
        when (val imageSource = source) {
            is Conversation.Message.ImageSource.Base64ImageSource ->
                builder.imageUrl("data:${imageSource.mediaType};base64,${imageSource.data}")
            is Conversation.Message.ImageSource.UrlImageSource -> builder.imageUrl(imageSource.url)
            is Conversation.Message.ImageSource.FileImageSource -> builder.fileId(imageSource.fileId)
        }
        return builder.build()
    }

    private fun Conversation.Message.ContentItem.DocumentItem.toOpenAiInputFile(): ResponseInputFile =
        when (val documentSource = source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource ->
                ResponseInputFile.builder()
                    .filename(documentSource.fileName)
                    .fileData("data:${documentSource.mediaType};base64,${documentSource.data}")
                    .build()
        }

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
                .outputOfResponseFunctionCallOutputItemList(toolResult.toOpenAiFunctionOutput())
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
        val text = message.content.mapNotNull { it.userInputTextOrNull() }
            .joinToString("\n")
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

    private fun Conversation.Message.ContentItem.ToolResult.toOpenAiFunctionOutput():
        List<ResponseFunctionCallOutputItem> = result.map { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text ->
                    ResponseFunctionCallOutputItem.ofInputText(
                        ResponseInputTextContent.builder().text(data.content).build()
                    )

                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                    if (data.mediaType.value.startsWith("image/")) {
                        ResponseFunctionCallOutputItem.ofInputImage(
                            ResponseInputImageContent.builder()
                                .detail(ResponseInputImageContent.Detail.AUTO)
                                .imageUrl("data:${data.mediaType.value};base64,${data.data}")
                                .build()
                        )
                    } else {
                        ResponseFunctionCallOutputItem.ofInputFile(
                            ResponseInputFileContent.builder()
                                .filename(data.fileName ?: "tool-output.bin")
                                .fileData("data:${data.mediaType.value};base64,${data.data}")
                                .build()
                        )
                    }

                is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
                    if (data.mediaType?.value?.startsWith("image/") == true) {
                        ResponseFunctionCallOutputItem.ofInputImage(
                            ResponseInputImageContent.builder()
                                .detail(ResponseInputImageContent.Detail.AUTO)
                                .imageUrl(data.url)
                                .build()
                        )
                    } else {
                        ResponseFunctionCallOutputItem.ofInputText(
                            ResponseInputTextContent.builder().text("[url ${data.url}]").build()
                        )
                    }

                is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                    error("OpenAI received an unmaterialized tool artifact: ${data.artifact.id.value}")
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

    private fun applyReasoning(builder: ResponseCreateParams.Builder, config: AiReasoningConfig?) {
        if (config == null) return
        val effort = config.openAiEffort()
        val summary = config.mode != AiReasoningMode.DISABLED && config.display != null && config.display != AiReasoningDisplay.OMITTED
        if (effort == null && !summary) return
        val reasoning = Reasoning.builder()
        effort?.let(reasoning::effort)
        if (summary) reasoning.summary(Reasoning.Summary.AUTO)
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
        progress: Boolean,
    ): AiAssistantMessage? {
        val content = content().mapNotNull { item ->
            when {
                item.isOutputText() -> item.asOutputText().toAssistantBlock(
                    assistantResponseFormat, progress || phase().getOrNull()?.asString() == "commentary",
                )
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
        progress: Boolean,
    ): Conversation.Message.ContentItem.AssistantMessage {
        val structured = if (progress) AssistantResponseParser.parseProgress(text(), assistantResponseFormat)
            else AssistantResponseParser.parse(text(), assistantResponseFormat)
        return Conversation.Message.ContentItem.AssistantMessage(
            structured = structured,
            state = Conversation.Message.BlockState.COMPLETE,
        )
    }

    private fun ResponseFunctionToolCall.toAssistantMessage(): AiAssistantMessage {
        val input = json.parseToJsonElement(arguments())
        require(input is JsonObject) { "OpenAI function arguments must be a JSON object" }
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

    private fun ResponseFunctionWebSearch.toSearchActivity(): AiAssistantMessage {
        val callId = Conversation.Message.ContentItem.ToolCall.Id(id())
        val action = json.parseToJsonElement(jsonMapper().writeValueAsString(action())).jsonObject
        val sources = sourceUrls().distinct()
        val complete = status().asString() == "completed"
        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = callId,
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = "web_search",
                        input = JsonObject(action.filterKeys { it != "sources" }),
                    ),
                ),
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = callId,
                    toolName = "web_search",
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(
                        sources.joinToString("\n") { "- <$it>" }.ifBlank { status().asString() },
                    )),
                    isError = !complete,
                ),
            ),
            // Hosted search has already run; this is history/UI, never a client tool dispatch.
            // Its native output item is retained by the common mapping below for exact replay.
            metadata = mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to true),
        )
    }

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
