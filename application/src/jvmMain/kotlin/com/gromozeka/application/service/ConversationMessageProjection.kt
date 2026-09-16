package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryMessage
import com.gromozeka.domain.repository.PositionedConversationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

object ConversationMessageProjection {
    fun project(entry: PositionedConversationMessage, textBudget: Int = 16_384): ConversationHistoryMessage {
        var remaining = textBudget
        var deferred = entry.message.content.size > MAX_CONTENT_BLOCKS
        fun preview(text: String): String {
            val value = text.take(remaining)
            remaining -= value.length
            if (value.length < text.length) deferred = true
            return value
        }
        val content = entry.message.content.take(MAX_CONTENT_BLOCKS).map { item ->
            when (item) {
                is Conversation.Message.ContentItem.Thinking -> item.copy(thinking = preview(item.thinking), signature = null)
                is Conversation.Message.ContentItem.ContextCompactionResult -> item.copy(payload = when (val payload = item.payload) {
                    is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState -> payload.copy(state = JsonObject(emptyMap()))
                    is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.copy(text = preview(payload.text))
                })
                is Conversation.Message.ContentItem.UserMessage -> item.copy(text = preview(item.text))
                is Conversation.Message.ContentItem.AssistantMessage -> item.copy(structured = item.structured.copy(fullText = preview(item.structured.fullText), ttsText = null))
                is Conversation.Message.ContentItem.System -> item.copy(content = preview(item.content))
                is Conversation.Message.ContentItem.ToolResult -> item.copy(result = item.result.map { data -> when (data) {
                    is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.copy(content = preview(data.content))
                    is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> {
                        deferred = true
                        Conversation.Message.ContentItem.ToolResult.Data.Text(data.fileName ?: data.mediaType.value)
                    }
                    else -> data
                } })
                is Conversation.Message.ContentItem.ToolCall -> if (item.call.input.toString().length > remaining) {
                    deferred = true
                    item.copy(call = item.call.copy(input = JsonNull))
                } else { remaining -= item.call.input.toString().length; item }
                is Conversation.Message.ContentItem.ImageItem -> if (item.source is Conversation.Message.ImageSource.Base64ImageSource) {
                    deferred = true
                    Conversation.Message.ContentItem.System(Conversation.Message.ContentItem.System.SystemLevel.INFO, "Image")
                } else item
                is Conversation.Message.ContentItem.DocumentItem -> if (item.source is Conversation.Message.DocumentSource.Base64DocumentSource) {
                    deferred = true
                    Conversation.Message.ContentItem.System(Conversation.Message.ContentItem.System.SystemLevel.INFO, "Document")
                } else item
                else -> item
            }
        }
        var projected = entry.message.copy(
            content = content,
            providerMetadata = JsonObject(entry.message.providerMetadata.filterKeys { it == "compactionBoundary" }),
        )
        if (Json.encodeToString(projected).encodeToByteArray().size > MAX_MESSAGE_BYTES) {
            deferred = true
            projected = projected.copy(
                instructions = emptyList(),
                originalIds = emptyList(),
                providerMetadata = JsonObject(emptyMap()),
                author = when (val author = projected.author) {
                    is Conversation.Message.Author.User -> author.copy(displayName = author.displayName.take(256), identityKey = null)
                    is Conversation.Message.Author.Agent -> author.copy(displayName = author.displayName.take(256))
                    null -> null
                },
                error = projected.error?.let { it.copy(message = it.message.take(1_024), type = it.type?.take(128)) },
                content = projected.content.map { block ->
                    if (Json.encodeToString<Conversation.Message.ContentItem>(block).encodeToByteArray().size <= MAX_MESSAGE_BYTES / 2) block
                    else Conversation.Message.ContentItem.System(Conversation.Message.ContentItem.System.SystemLevel.INFO, "Content available on demand")
                },
            )
            while (Json.encodeToString(projected).encodeToByteArray().size > MAX_MESSAGE_BYTES && projected.content.size > 1) {
                projected = projected.copy(content = projected.content.take(projected.content.size / 2))
            }
        }
        return ConversationHistoryMessage(entry.position, projected, deferred)
    }
    const val MAX_MESSAGE_BYTES = 32 * 1024
    private const val MAX_CONTENT_BLOCKS = 32
}
