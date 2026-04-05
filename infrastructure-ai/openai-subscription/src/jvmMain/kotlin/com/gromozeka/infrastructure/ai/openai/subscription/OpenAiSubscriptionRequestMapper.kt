package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAutoCompaction
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

        return OpenAiSubscriptionResponsesRequest(
            model = modelName,
            input = replayWindow.messages.flatMapIndexed { index, message ->
                toInputItems(
                    message = message,
                    replayCompactionOnly = replayWindow.compactionAnchorIndex == index,
                )
            },
            instructions = request.systemPrompts.joinToString("\n\n").trim().ifBlank { null },
            contextManagement = buildContextManagement(request.options.autoCompaction),
            tools = request.tools.map { tool -> tool.toToolJson() },
            toolChoice = request.options.toolChoice.toToolChoiceJson(),
            text = buildTextConfig(),
            reasoning = buildReasoning(request.options.outputConfig),
            promptCacheKey = conversationKey,
        )
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
            return compactionItems
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
        val normalized = this.toMutableMap()
        if (normalized["type"]?.jsonPrimitive?.contentOrNull == "reasoning" && normalized["summary"] == null) {
            normalized["summary"] = JsonArray(emptyList())
        }
        return JsonObject(normalized)
    }

    private fun Conversation.Message.toSystemInputItems(): List<JsonObject> {
        val text = content
            .filterIsInstance<Conversation.Message.ContentItem.System>()
            .joinToString("\n") { it.content }
            .trim()

        if (text.isBlank()) return emptyList()

        return listOf(messageItem(role = "system", content = JsonPrimitive(text)))
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

    private fun buildTextConfig(): JsonObject {
        return buildJsonObject {
            put("verbosity", "medium")
        }
    }

    private fun buildContextManagement(autoCompaction: AiAutoCompaction?): List<JsonObject>? {
        val threshold = autoCompaction?.threshold ?: return null

        return listOf(
            buildJsonObject {
                put("type", "compaction")
                put("compact_threshold", threshold)
            }
        )
    }

    private fun buildReasoning(outputConfig: AgentDefinition.OutputConfig?): JsonObject? {
        val effort = when (outputConfig?.effort?.lowercase()) {
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            "max", "xhigh" -> "xhigh"
            else -> null
        } ?: return null

        return buildJsonObject {
            put("effort", effort)
            put("summary", "detailed")
        }
    }

    private fun AiToolChoice.toToolChoiceJson(): JsonElement? {
        return when (this) {
            AiToolChoice.Auto -> JsonPrimitive("auto")
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

    private fun List<Conversation.Message>.toReplayWindow(): ReplayWindow {
        val compactionAnchorIndex = indexOfLast { it.providerMetadata.containsCompactionReplayItem() }
        if (compactionAnchorIndex < 0) {
            return ReplayWindow(messages = this, compactionAnchorIndex = null)
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

    private data class ReplayWindow(
        val messages: List<Conversation.Message>,
        val compactionAnchorIndex: Int?,
    )
}
