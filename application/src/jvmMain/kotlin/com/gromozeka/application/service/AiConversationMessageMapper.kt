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
        return response.messages.mapNotNull { assistantMessage ->
            if (assistantMessage.content.isEmpty()) {
                null
            } else {
                Conversation.Message(
                    id = Conversation.Message.Id(uuid7()),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.ASSISTANT,
                    content = assistantMessage.content,
                    providerMetadata = mergeProviderMetadata(
                        responseMetadata = response.providerMetadata,
                        messageMetadata = assistantMessage.metadata,
                    ),
                    createdAt = Clock.System.now()
                )
            }
        }
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
        return response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()
    }

    private fun mergeProviderMetadata(
        responseMetadata: Map<String, Any?>,
        messageMetadata: Map<String, Any?>,
    ): JsonObject {
        val merged = linkedMapOf<String, JsonElement>()

        responseMetadata.forEach { (key, value) ->
            toJsonElement(value)?.let { merged[key] = it }
        }
        messageMetadata.forEach { (key, value) ->
            toJsonElement(value)?.let { merged[key] = it }
        }

        return JsonObject(merged)
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
