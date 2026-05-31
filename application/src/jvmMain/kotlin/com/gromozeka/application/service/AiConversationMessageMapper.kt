package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AiConversationMessageMapper {

    fun toConversationMessages(
        conversationId: Conversation.Id,
        response: AiRuntimeResponse
    ): List<Conversation.Message> {
        val responseMetadata = toJsonObject(response.providerMetadata)
        val messages = mutableListOf<Conversation.Message>()
        var pendingMessageMetadata = JsonObject(emptyMap())

        response.messages.forEach { assistantMessage ->
            val messageMetadata = toJsonObject(assistantMessage.metadata)
            if (assistantMessage.content.isEmpty()) {
                pendingMessageMetadata = mergeJsonObjects(pendingMessageMetadata, messageMetadata)
                return@forEach
            }

            messages += Conversation.Message(
                id = Conversation.Message.Id(uuid7()),
                conversationId = conversationId,
                role = Conversation.Message.Role.ASSISTANT,
                content = assistantMessage.content,
                providerMetadata = mergeJsonObjects(
                    responseMetadata,
                    mergeJsonObjects(pendingMessageMetadata, messageMetadata),
                ),
                createdAt = Clock.System.now()
            )
            pendingMessageMetadata = JsonObject(emptyMap())
        }

        if (pendingMessageMetadata.isNotEmpty() && messages.isNotEmpty()) {
            val lastIndex = messages.lastIndex
            val lastMessage = messages[lastIndex]
            messages[lastIndex] = lastMessage.copy(
                providerMetadata = mergeJsonObjects(lastMessage.providerMetadata, pendingMessageMetadata)
            )
        }

        return mergeAdjacentToolCallMessages(messages)
    }

    fun createErrorMessage(
        conversationId: Conversation.Id,
        message: String,
        type: String = "error"
    ): Conversation.Message {
        return Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.SYSTEM,
            content = listOf(
                Conversation.Message.ContentItem.System(
                    level = Conversation.Message.ContentItem.System.SystemLevel.ERROR,
                    content = message,
                    state = Conversation.Message.BlockState.COMPLETE
                )
            ),
            createdAt = Clock.System.now(),
            error = Conversation.Message.GenerationError(message = message, type = type)
        )
    }

    fun extractAssistantText(response: AiRuntimeResponse): String {
        return extractAssistantTexts(response)
            .joinToString("\n")
            .trim()
    }

    fun extractAssistantTexts(response: AiRuntimeResponse): List<String> {
        return response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .map { it.structured.fullText.trim() }
            .filter { it.isNotBlank() }
    }

    private fun toJsonObject(metadata: Map<String, Any?>): JsonObject {
        return JsonObject(
            metadata.mapNotNull { (key, value) ->
                toJsonElement(value)?.let { key to it }
            }.toMap()
        )
    }

    private fun mergeJsonObjects(
        base: JsonObject,
        extra: JsonObject,
    ): JsonObject {
        if (base.isEmpty()) return extra
        if (extra.isEmpty()) return base

        val merged = base.toMutableMap()
        extra.forEach { (key, value) ->
            merged[key] = mergeJsonElements(merged[key], value)
        }
        return JsonObject(merged)
    }

    private fun mergeAdjacentToolCallMessages(
        messages: List<Conversation.Message>,
    ): List<Conversation.Message> {
        if (messages.size < 2) return messages

        val merged = mutableListOf<Conversation.Message>()

        messages.forEach { message ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.canMergeAdjacentToolCallsWith(message)) {
                merged[merged.lastIndex] = previous.copy(
                    content = previous.content + message.content,
                    providerMetadata = mergeJsonObjects(previous.providerMetadata, message.providerMetadata),
                )
            } else {
                merged += message
            }
        }

        return merged
    }

    private fun Conversation.Message.canMergeAdjacentToolCallsWith(
        other: Conversation.Message,
    ): Boolean {
        return isToolCallOnlyAssistantMessage() && other.isToolCallOnlyAssistantMessage()
    }

    private fun Conversation.Message.isToolCallOnlyAssistantMessage(): Boolean {
        return role == Conversation.Message.Role.ASSISTANT &&
            error == null &&
            content.isNotEmpty() &&
            content.all { it is Conversation.Message.ContentItem.ToolCall }
    }

    private fun mergeJsonElements(
        existing: JsonElement?,
        incoming: JsonElement,
    ): JsonElement {
        if (existing == null) return incoming

        return when {
            existing is JsonArray && incoming is JsonArray -> JsonArray(existing + incoming)
            existing is JsonObject && incoming is JsonObject -> mergeJsonObjects(existing, incoming)
            else -> incoming
        }
    }

    private fun toJsonElement(value: Any?): JsonElement? {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value.toString())
            is Map<*, *> -> JsonObject(
                value.entries
                    .mapNotNull { (key, nestedValue) ->
                        (key as? String)?.let { stringKey ->
                            toJsonElement(nestedValue)?.let { stringKey to it }
                        }
                    }
                    .toMap()
            )
            is Iterable<*> -> JsonArray(value.mapNotNull(::toJsonElement))
            is Array<*> -> JsonArray(value.mapNotNull(::toJsonElement))
            else -> JsonPrimitive(value.toString())
        }
    }
}
