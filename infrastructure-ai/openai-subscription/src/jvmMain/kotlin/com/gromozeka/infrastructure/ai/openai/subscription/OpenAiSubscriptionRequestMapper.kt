package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component

@Component
class OpenAiSubscriptionRequestMapper {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun toRequest(
        request: AiRuntimeRequest,
        modelName: String,
        conversationKey: String,
    ): OpenAiSubscriptionResponsesRequest {
        val replayWindow = request.messages.toReplayWindow()
        val effectiveTools = if (request.options.toolChoice is AiToolChoice.None) {
            emptyList()
        } else {
            request.tools.sortedBy { it.definition.name }
        }
        val inputItems = replayWindow.messages.flatMapIndexed { index, message ->
            toInputItems(
                message = message,
                replayCompactionOnly = replayWindow.compactionAnchorIndex == index,
            )
        }.dropOrphanFunctionCallOutputs(conversationKey)
        val instructions = request.systemPrompts.joinToString("\n\n").trim().ifBlank { null }
        val requestPayload = OpenAiSubscriptionResponsesRequest(
            model = modelName,
            input = inputItems,
            instructions = instructions,
            contextManagement = buildContextManagement(request.options.autoCompactionThresholdTokens),
            tools = effectiveTools.map { tool -> tool.toToolJson() },
            toolChoice = request.options.toolChoice.toToolChoiceJson().takeIf { effectiveTools.isNotEmpty() },
            text = buildTextConfig(request.options.responseFormat),
            reasoning = buildReasoning(request.options.reasoning),
            promptCacheKey = conversationKey,
        )

        logRequestLayout(
            request = request,
            requestPayload = requestPayload,
            replayWindow = replayWindow,
            effectiveTools = effectiveTools,
            conversationKey = conversationKey,
        )

        return requestPayload
    }

    fun buildTransportSignature(request: OpenAiSubscriptionResponsesRequest): String {
        val normalized = request.copy(
            input = emptyList(),
            previousResponseId = null,
        )

        return json.encodeToString(OpenAiSubscriptionResponsesRequest.serializer(), normalized)
    }

    fun toReplayItems(outputItems: List<JsonObject>): List<JsonObject> {
        return outputItems.flatMap(::normalizeReplayItem)
    }

    private fun toInputItems(
        message: Conversation.Message,
        replayCompactionOnly: Boolean = false,
    ): List<JsonObject> {
        return when (message.role) {
            Conversation.Message.Role.USER -> message.toUserInputItems()
            Conversation.Message.Role.ASSISTANT -> message.toAssistantInputItems(replayCompactionOnly)
            Conversation.Message.Role.SYSTEM -> message.toSystemInputItems()
        }
    }

    private fun Conversation.Message.toUserInputItems(): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        buildUserMessageText(this)?.let { userText ->
            items += messageItem(role = "user", content = JsonPrimitive(userText))
        }

        content
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
            .forEach { toolResult ->
                items += functionCallOutputItem(
                    callId = toolResult.toolUseId.value,
                    output = JsonPrimitive(toolResult.asPlainText()),
                )
            }

        return items
    }

    private fun Conversation.Message.toAssistantInputItems(
        replayCompactionOnly: Boolean,
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        val textBuffer = mutableListOf<String>()
        val hiddenReplayItems = providerMetadata.toHiddenReplayItems()
        val compactionItems = hiddenReplayItems.filter { it.isCompactionReplayItem() }

        if (replayCompactionOnly && compactionItems.isNotEmpty()) {
            return compactionItems + content.filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                .map { toolCall ->
                    functionCallItem(
                        callId = toolCall.id.value,
                        name = toolCall.call.name,
                        arguments = toolCall.call.input.toString(),
                    )
                }
        }

        items += hiddenReplayItems

        fun flushText() {
            val text = textBuffer.joinToString("\n").trim()
            if (text.isBlank()) return
            items += messageItem(
                role = "assistant",
                content = JsonPrimitive(text),
                phase = providerMetadata["phase"]?.jsonPrimitive?.contentOrNull,
            )
            textBuffer.clear()
        }

        content.forEach { contentItem ->
            when (contentItem) {
                is Conversation.Message.ContentItem.AssistantMessage -> {
                    val text = contentItem.structured.fullText.trim()
                    if (text.isNotBlank()) {
                        textBuffer += text
                    }
                }

                is Conversation.Message.ContentItem.Thinking -> {
                    flushText()
                    items += reasoningItem(
                        encryptedContent = contentItem.signature,
                        thinking = contentItem.thinking,
                    )
                }

                is Conversation.Message.ContentItem.ToolCall -> {
                    flushText()
                    items += functionCallItem(
                        callId = contentItem.id.value,
                        name = contentItem.call.name,
                        arguments = contentItem.call.input.toString(),
                    )
                }

                else -> Unit
            }
        }

        flushText()
        return items
    }

    private fun JsonObject.toHiddenReplayItems(): List<JsonObject> {
        return this[OPENAI_REASONING_ITEMS_METADATA_KEY]
            ?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.normalizeHiddenReasoningItem() }
            ?: emptyList()
    }

    private fun JsonObject.normalizeHiddenReasoningItem(): JsonObject {
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: return this
        val encryptedContent = this["encrypted_content"]?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("type", type)

            when (type) {
                "reasoning" -> {
                    put("summary", this@normalizeHiddenReasoningItem["summary"] ?: JsonArray(emptyList()))
                }

                "compaction", "compaction_summary" -> Unit

                else -> {
                    this@normalizeHiddenReasoningItem["summary"]?.let { put("summary", it) }
                }
            }

            if (!encryptedContent.isNullOrBlank()) {
                put("encrypted_content", encryptedContent)
            }
        }
    }

    private fun Conversation.Message.toSystemInputItems(): List<JsonObject> {
        if (error != null) return emptyList()

        val text = content
            .filterIsInstance<Conversation.Message.ContentItem.System>()
            .joinToString("\n") { it.content }
            .trim()

        if (text.isBlank()) return emptyList()

        val content = buildJsonArray {
            add(inputTextItem(text))
        }

        return listOf(messageItem(role = "developer", content = content))
    }

    private fun buildUserMessageText(message: Conversation.Message): String? {
        val instructionsPrefix = message.instructions.joinToString("\n") { it.toXmlLine() }.trim()
        val text = message.content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }
            .trim()

        return listOf(
            instructionsPrefix.takeIf { it.isNotBlank() },
            text.takeIf { it.isNotBlank() },
        ).filterNotNull()
            .joinToString("\n")
            .trim()
            .ifBlank { null }
    }

    private fun buildTextConfig(responseFormat: AiResponseFormat): JsonObject {
        return buildJsonObject {
            put("verbosity", "medium")
            when (responseFormat) {
                AiResponseFormat.Text -> Unit
                is AiResponseFormat.JsonSchema -> putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", responseFormat.name)
                    responseFormat.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                    put("schema", responseFormat.schema)
                    put("strict", responseFormat.strict)
                }
            }
        }
    }

    private fun buildContextManagement(autoCompactionThresholdTokens: Int?): List<JsonObject>? {
        val threshold = autoCompactionThresholdTokens ?: return null
        return listOf(
            buildJsonObject {
                put("type", "compaction")
                put("compact_threshold", threshold)
            }
        )
    }

    private fun buildReasoning(reasoning: AiReasoningConfig?): JsonObject? {
        val effort = when (reasoning?.effort) {
            AiReasoningEffort.LOW -> "low"
            AiReasoningEffort.MEDIUM -> "medium"
            AiReasoningEffort.HIGH -> "high"
            AiReasoningEffort.MAX -> "xhigh"
            null -> null
        } ?: return null

        return buildJsonObject {
            put("effort", effort)
            put("summary", "detailed")
        }
    }

    private fun AiToolChoice.toToolChoiceJson(): JsonElement? {
        return when (this) {
            AiToolChoice.Auto -> JsonPrimitive("auto")
            AiToolChoice.None -> JsonPrimitive("none")
            AiToolChoice.RequiredAny -> JsonPrimitive("required")
            is AiToolChoice.RequiredTool -> buildJsonObject {
                put("type", "function")
                put("name", name)
            }
        }
    }

    private fun AiToolCallback.toToolJson(): JsonObject {
        return buildJsonObject {
            put("type", "function")
            put("name", definition.name)
            put("description", definition.description)
            put("strict", false)
            put("parameters", definition.inputSchema.toNormalizedToolSchema())
        }
    }

    private fun String.toNormalizedToolSchema(): JsonObject {
        val schema = runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()
            ?.normalizeToolSchema()
            ?: buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
                put("required", JsonArray(emptyList()))
                put("additionalProperties", false)
            }

        return schema
    }

    private fun JsonObject.normalizeToolSchema(): JsonObject {
        val normalized = this.toMutableMap()
        val properties = normalized["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = normalized["required"] as? JsonArray ?: JsonArray(emptyList())

        normalized["type"] = JsonPrimitive("object")
        normalized["properties"] = properties
        normalized["required"] = required
        normalized.putIfAbsent("additionalProperties", JsonPrimitive(false))

        return JsonObject(normalized)
    }

    private fun messageItem(role: String, content: JsonElement, phase: String? = null): JsonObject {
        return buildJsonObject {
            put("type", "message")
            put("role", role)
            put("content", content)
            if (!phase.isNullOrBlank()) {
                put("phase", phase)
            }
        }
    }

    private fun inputTextItem(text: String): JsonObject =
        buildJsonObject {
            put("type", "input_text")
            put("text", text)
        }

    private fun reasoningItem(
        encryptedContent: String?,
        thinking: String,
    ): JsonObject {
        return buildJsonObject {
            put("type", "reasoning")
            putJsonArray("summary") {
                if (thinking.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "summary_text")
                            put("text", thinking)
                        }
                    )
                }
            }
            if (!encryptedContent.isNullOrBlank()) {
                put("encrypted_content", encryptedContent)
            }
        }
    }

    private fun functionCallItem(
        callId: String,
        name: String,
        arguments: String,
    ): JsonObject {
        return buildJsonObject {
            put("type", "function_call")
            put("call_id", callId)
            put("name", name)
            put("arguments", arguments)
        }
    }

    private fun functionCallOutputItem(
        callId: String,
        output: JsonElement,
    ): JsonObject {
        return buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }

    private fun Conversation.Message.ContentItem.ToolResult.asPlainText(): String {
        return result.joinToString("\n") { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                    "[base64:${data.mediaType.value}] ${data.data}"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
                    buildString {
                        append("[url")
                        data.mediaType?.let { append(":${it.value}") }
                        append("] ${data.url}")
                    }
                is Conversation.Message.ContentItem.ToolResult.Data.FileData ->
                    buildString {
                        append("[file")
                        data.mediaType?.let { append(":${it.value}") }
                        append("] ${data.fileId}")
                    }
            }
        }
    }

    private fun normalizeReplayItem(item: JsonObject): List<JsonObject> {
        return when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "message" -> item.toReplayMessageItems()
            "function_call" -> listOfNotNull(item.toReplayFunctionCallItem())
            "reasoning", "compaction", "compaction_summary" -> listOfNotNull(item.toReplayReasoningItem())
            else -> emptyList()
        }
    }

    private fun JsonObject.toReplayMessageItems(): List<JsonObject> {
        if (this["role"]?.jsonPrimitive?.contentOrNull != "assistant") return emptyList()

        val text = buildList {
            when (val content = this@toReplayMessageItems["content"]) {
                is JsonPrimitive -> {
                    content.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }

                is JsonArray -> {
                    content.forEach { part ->
                        val partObject = part as? JsonObject ?: return@forEach
                        when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
                            "output_text", "text" -> {
                                partObject["text"]?.jsonPrimitive?.contentOrNull?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }

                            "refusal" -> {
                                partObject["refusal"]?.jsonPrimitive?.contentOrNull?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }
                    }
                }

                else -> Unit
            }
        }.joinToString("\n").trim()

        if (text.isBlank()) return emptyList()

        return listOf(
            messageItem(
                role = "assistant",
                content = JsonPrimitive(text),
                phase = this["phase"]?.jsonPrimitive?.contentOrNull,
            )
        )
    }

    private fun JsonObject.toReplayFunctionCallItem(): JsonObject? {
        val callId = this["call_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = this["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val arguments = this["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()

        return functionCallItem(
            callId = callId,
            name = name,
            arguments = arguments,
        )
    }

    private fun JsonObject.toReplayReasoningItem(): JsonObject? {
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val encryptedContent = this["encrypted_content"]?.jsonPrimitive?.contentOrNull
        val thinking = extractThinkingText()

        return when {
            thinking.isNotBlank() -> reasoningItem(
                encryptedContent = encryptedContent,
                thinking = thinking,
            )

            encryptedContent.isNullOrBlank() -> null

            else -> buildJsonObject {
                put("type", type)
                if (type == "reasoning") {
                    put("summary", JsonArray(emptyList()))
                }
                put("encrypted_content", encryptedContent)
            }
        }
    }

    private fun JsonObject.extractThinkingText(): String {
        return buildList {
            this@extractThinkingText["summary"]?.jsonArray?.forEach { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }

            this@extractThinkingText["content"]?.jsonArray?.forEach { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }.joinToString("\n").trim()
    }

    private fun List<JsonObject>.dropOrphanFunctionCallOutputs(conversationKey: String): List<JsonObject> {
        val knownCallIds = mutableSetOf<String>()
        val droppedCallIds = mutableListOf<String>()
        val retainedItems = mutableListOf<JsonObject>()

        forEach { item ->
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "function_call" -> {
                    item["call_id"]?.jsonPrimitive?.contentOrNull?.let(knownCallIds::add)
                    retainedItems += item
                }

                "function_call_output" -> {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                    if (callId != null && callId in knownCallIds) {
                        retainedItems += item
                    } else {
                        droppedCallIds += callId ?: "<missing>"
                    }
                }

                else -> retainedItems += item
            }
        }

        if (droppedCallIds.isNotEmpty()) {
            log.warn(
                "OpenAI subscription request dropped orphan function_call_output item(s): " +
                    "conversationKey=$conversationKey count=${droppedCallIds.size} " +
                    "callIds=${droppedCallIds.take(8)}"
            )
        }

        return retainedItems
    }

    private fun List<Conversation.Message>.toReplayWindow(): ReplayWindow {
        val compactionAnchorIndex = indexOfLast { it.providerMetadata.containsCompactionReplayItem() }
        if (compactionAnchorIndex < 0) {
            return ReplayWindow(
                messages = this,
                compactionAnchorIndex = null,
                originalMessageCount = size,
                trimmedMessageCount = 0,
            )
        }

        val trimmedMessageCount = compactionAnchorIndex
        val retainedMessageCount = size - compactionAnchorIndex
        log.info(
            "OpenAI subscription replay window trimmed to latest compaction anchor: " +
                "trimmedMessages=$trimmedMessageCount, retainedMessages=$retainedMessageCount"
        )

        return ReplayWindow(
            messages = drop(compactionAnchorIndex),
            compactionAnchorIndex = 0,
            originalMessageCount = size,
            trimmedMessageCount = trimmedMessageCount,
        )
    }

    private fun JsonObject.containsCompactionReplayItem(): Boolean {
        return toHiddenReplayItems().any { it.isCompactionReplayItem() }
    }

    private fun JsonObject.isCompactionReplayItem(): Boolean {
        return when (this["type"]?.jsonPrimitive?.contentOrNull) {
            "compaction", "compaction_summary" -> true
            else -> false
        }
    }

    private fun logRequestLayout(
        request: AiRuntimeRequest,
        requestPayload: OpenAiSubscriptionResponsesRequest,
        replayWindow: ReplayWindow,
        effectiveTools: List<AiToolCallback>,
        conversationKey: String,
    ) {
        val itemTypeCounts = requestPayload.input
            .groupingBy { it["type"]?.jsonPrimitive?.contentOrNull ?: "unknown" }
            .eachCount()
        val messageRoleCounts = requestPayload.input
            .asSequence()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .groupingBy { it["role"]?.jsonPrimitive?.contentOrNull ?: "unknown" }
            .eachCount()
        val messageContentShapeCounts = requestPayload.input
            .asSequence()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .groupingBy { it.messageContentShapeForLog() }
            .eachCount()
        val payloadChars = runCatching {
            json.encodeToString(OpenAiSubscriptionResponsesRequest.serializer(), requestPayload).length
        }.getOrNull()
        val toolSignature = effectiveTools.joinToString(",") { it.definition.name }.hashCode()

        log.info(
            "OpenAI subscription request layout: " +
                "conversationKey=$conversationKey, " +
                "systemPrompts=${request.systemPrompts.size}, " +
                "instructionsChars=${requestPayload.instructions?.length ?: 0}, " +
                "replayMessages=${replayWindow.messages.size}/${replayWindow.originalMessageCount}, " +
                "trimmedMessages=${replayWindow.trimmedMessageCount}, " +
                "inputItems=${requestPayload.input.size}, " +
                "itemTypes=$itemTypeCounts, " +
                "messageRoles=$messageRoleCounts, " +
                "messageContentShapes=$messageContentShapeCounts, " +
                "tools=${effectiveTools.size}, " +
                "toolChoice=${requestPayload.toolChoice?.toString()?.take(80) ?: "omitted"}, " +
                "toolSignature=${toolSignature.toUInt().toString(16)}, " +
                "autoCompactionThreshold=${request.options.autoCompactionThresholdTokens ?: "omitted"}, " +
                "responseFormat=${request.options.responseFormat.logName()}, " +
                "payloadChars=${payloadChars ?: -1}"
        )
    }

    private fun JsonObject.messageContentShapeForLog(): String {
        val role = this["role"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val content = this["content"]
        val shape = when (content) {
            is JsonArray -> {
                val types = content.mapNotNull { item ->
                    item.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                }.groupingBy { it }.eachCount()

                "array:$types"
            }
            is JsonPrimitive -> "primitive:${if (content.isString) "string" else "non_string"}"
            JsonNull, null -> "null"
            else -> "object"
        }

        return "$role/$shape"
    }

    private fun AiResponseFormat.logName(): String =
        when (this) {
            AiResponseFormat.Text -> "text"
            is AiResponseFormat.JsonSchema -> "json_schema:$name"
        }

    private data class ReplayWindow(
        val messages: List<Conversation.Message>,
        val compactionAnchorIndex: Int?,
        val originalMessageCount: Int,
        val trimmedMessageCount: Int,
    )
}
