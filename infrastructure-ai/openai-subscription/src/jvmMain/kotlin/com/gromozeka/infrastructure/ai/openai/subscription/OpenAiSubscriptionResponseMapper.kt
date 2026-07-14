package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.infrastructure.ai.parsers.AssistantResponseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component

@Component
class OpenAiSubscriptionResponseMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun toRuntimeResponse(
        outputItems: List<JsonObject>,
        completed: OpenAiSubscriptionCompletedResponse?,
        conversationKey: String,
        connectionId: String,
        modelConfigurationId: String,
        modelName: String,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val messages = outputItems.mapNotNull {
            toAssistantMessage(
                item = it,
                assistantResponseFormat = assistantResponseFormat,
                connectionId = connectionId,
                modelConfigurationId = modelConfigurationId,
                modelName = modelName,
            )
        }

        return AiRuntimeResponse(
            messages = messages,
            usage = completed?.usage?.toAiUsage(),
            finishReason = completed?.status,
            providerMetadata = buildMap {
                put("conversationKey", conversationKey)
                completed?.id?.let { put("responseId", it) }
            },
        )
    }

    fun parseCompletedResponse(jsonObject: JsonObject): OpenAiSubscriptionCompletedResponse {
        return json.decodeFromJsonElement(OpenAiSubscriptionCompletedResponse.serializer(), jsonObject)
    }

    fun extractErrorMessage(body: String): String {
        val trimmed = body.trim()
        val payload = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return trimmed
        val errorObject = payload["error"] as? JsonObject
        val code = errorObject?.get("code")?.jsonPrimitive?.contentOrNull
        val message = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
        val detail = payload["detail"]?.textOrNull()
        val topLevelMessage = payload["message"]?.textOrNull()
        val errorText = payload["error"]?.textOrNull()
        return listOfNotNull(code, message, detail, topLevelMessage, errorText)
            .joinToString(": ")
            .ifBlank { trimmed }
    }

    private fun toAssistantMessage(
        item: JsonObject,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
        connectionId: String,
        modelConfigurationId: String,
        modelName: String,
    ): AiAssistantMessage? {
        return when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "message" -> item.toOutputMessage(assistantResponseFormat)
            "function_call" -> item.toToolCall()
            "reasoning" -> item.toReasoningMessage()
            "compaction_summary", "compaction" -> item.toCompactionResultMessage(
                connectionId = connectionId,
                modelConfigurationId = modelConfigurationId,
                modelName = modelName,
            )
            else -> null
        }
    }

    private fun JsonObject.toOutputMessage(
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiAssistantMessage? {
        if (this["role"]?.jsonPrimitive?.contentOrNull != "assistant") return null

        val blocks = buildList {
            when (val content = this@toOutputMessage["content"]) {
                is JsonPrimitive -> {
                    val text = content.contentOrNull?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        add(assistantBlock(text, assistantResponseFormat))
                    }
                }

                is JsonArray -> {
                    content.forEach { part ->
                        val partObject = part as? JsonObject ?: return@forEach
                        when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
                            "output_text", "text" -> {
                                val text = partObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                if (text.isNotBlank()) {
                                    add(assistantBlock(text, assistantResponseFormat))
                                }
                            }

                            "refusal" -> {
                                val text = partObject["refusal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                if (text.isNotBlank()) {
                                    add(plainAssistantBlock(text))
                                }
                            }
                        }
                    }
                }

                else -> Unit
            }
        }

        if (blocks.isEmpty()) return null

        return AiAssistantMessage(
            content = blocks,
            metadata = buildMap {
                this@toOutputMessage["id"]?.jsonPrimitive?.contentOrNull?.let { put("messageId", it) }
                this@toOutputMessage["phase"]?.jsonPrimitive?.contentOrNull?.let { put("phase", it) }
            },
        )
    }

    private fun JsonObject.toToolCall(): AiAssistantMessage {
        val callId = this["call_id"]?.jsonPrimitive?.contentOrNull
            ?: error("Function call is missing call_id")
        val name = this["name"]?.jsonPrimitive?.contentOrNull
            ?: error("Function call is missing name")
        val arguments = this["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val input = json.parseOpenAiSubscriptionToolArguments(arguments)

        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id(callId),
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = name,
                        input = input,
                    ),
                    state = Conversation.Message.BlockState.COMPLETE,
                )
            ),
        )
    }

    private fun JsonObject.toReasoningMessage(): AiAssistantMessage? {
        val encryptedContent = this["encrypted_content"]?.jsonPrimitive?.contentOrNull
        val thinkingText = buildList {
            this@toReasoningMessage["summary"]?.jsonArray?.forEach { part ->
                val text = part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isNotBlank()) add(text)
            }
            this@toReasoningMessage["content"]?.jsonArray?.forEach { part ->
                val text = part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isNotBlank()) add(text)
            }
        }.joinToString("\n").trim()

        if (thinkingText.isBlank() && encryptedContent.isNullOrBlank()) return null

        if (thinkingText.isBlank()) {
            return AiAssistantMessage(
                content = emptyList(),
                metadata = mapOf(
                    OPENAI_REASONING_ITEMS_METADATA_KEY to buildJsonArray {
                        add(toHiddenReasoningItem())
                    }
                ),
            )
        }

        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.Thinking(
                    thinking = thinkingText,
                    signature = encryptedContent,
                    state = Conversation.Message.BlockState.COMPLETE,
                )
            ),
        )
    }

    private fun JsonObject.toHiddenReasoningItem(): JsonObject {
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: "reasoning"
        val encryptedContent = this["encrypted_content"]?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("type", type)
            if (type == "reasoning") {
                put("summary", JsonArray(emptyList()))
            }
            if (!encryptedContent.isNullOrBlank()) {
                put("encrypted_content", encryptedContent)
            }
        }
    }

    private fun JsonObject.toCompactionResultMessage(
        connectionId: String,
        modelConfigurationId: String,
        modelName: String,
    ): AiAssistantMessage? {
        val replayItem = toHiddenReasoningItem()
        if (replayItem["encrypted_content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return null

        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.ContextCompactionResult(
                    payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(
                        state = buildJsonObject {
                            put("replay_item", replayItem)
                        }
                    ),
                    origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                    providerScope = Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(
                        provider = AiConnection.Kind.OPENAI_SUBSCRIPTION.name,
                        connectionId = connectionId,
                        modelConfigurationId = modelConfigurationId,
                        modelName = modelName,
                    ),
                )
            ),
        )
    }

    private fun assistantBlock(
        text: String,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): Conversation.Message.ContentItem.AssistantMessage {
        return Conversation.Message.ContentItem.AssistantMessage(
            structured = AssistantResponseParser.parse(text, assistantResponseFormat),
            state = Conversation.Message.BlockState.COMPLETE,
        )
    }

    private fun plainAssistantBlock(text: String): Conversation.Message.ContentItem.AssistantMessage {
        return Conversation.Message.ContentItem.AssistantMessage(
            structured = Conversation.Message.StructuredText(fullText = text),
            state = Conversation.Message.BlockState.COMPLETE,
        )
    }

    private fun OpenAiSubscriptionUsage.toAiUsage(): AiUsage {
        val cachedTokens = inputTokensDetails?.cachedTokens?.toInt() ?: 0
        val cacheWriteTokens = inputTokensDetails?.cacheWriteTokens?.toInt() ?: 0
        val reasoningTokens = outputTokensDetails?.reasoningTokens?.toInt() ?: 0

        return AiUsage(
            promptTokens = (inputTokens.toInt() - cachedTokens - cacheWriteTokens).coerceAtLeast(0),
            completionTokens = (outputTokens.toInt() - reasoningTokens).coerceAtLeast(0),
            thinkingTokens = reasoningTokens,
            cacheCreationTokens = cacheWriteTokens,
            cacheReadTokens = cachedTokens,
        )
    }

    private fun JsonElement.textOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

internal fun Json.parseOpenAiSubscriptionToolArguments(arguments: String): JsonElement =
    runCatching { parseToJsonElement(arguments) }
        .getOrElse { JsonObject(mapOf("raw" to JsonPrimitive(arguments))) }
