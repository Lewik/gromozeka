package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.PromptSnapshot
import com.gromozeka.domain.model.ConversationHistoryMessage
import com.gromozeka.domain.repository.PositionedConversationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        fun previewObject(value: JsonObject): JsonObject {
            val length = value.toString().length
            if (length > remaining) { deferred = true; return JsonObject(emptyMap()) }
            remaining -= length
            return value
        }
        fun previewAttachment(data: Conversation.Message.ContentItem.ToolResult.Data): Conversation.Message.ContentItem.ToolResult.Data = when (data) {
            is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.copy(content = preview(data.content))
            is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> {
                deferred = true
                Conversation.Message.ContentItem.ToolResult.Data.Text(data.fileName ?: data.mediaType.value)
            }
            else -> data
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
                is Conversation.Message.ContentItem.PeerMessage -> item.copy(text = preview(item.text).ifEmpty { "…" })
                is Conversation.Message.ContentItem.PromptMessage -> item.copy(snapshot = item.snapshot.copy(entries = item.snapshot.entries.map { prompt ->
                    when (prompt) {
                        is PromptSnapshot.Entry.Catalog -> prompt.copy(text = preview(prompt.text))
                        PromptSnapshot.Entry.Environment -> prompt
                    }
                }))
                is Conversation.Message.ContentItem.VisualUpdated -> item.copy(visuals = item.visuals.map { visual ->
                    visual.copy(state = previewObject(visual.state))
                })
                is Conversation.Message.ContentItem.RuntimeNotification -> item.copy(content = preview(item.content),
                    details = previewObject(item.details), attachments = item.attachments.map(::previewAttachment))
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
                    Conversation.Message.ContentItem.RuntimeNotification(Conversation.Message.ContentItem.RuntimeNotification.Level.INFO, "Image")
                } else item
                is Conversation.Message.ContentItem.DocumentItem -> if (item.source is Conversation.Message.DocumentSource.Base64DocumentSource) {
                    deferred = true
                    Conversation.Message.ContentItem.RuntimeNotification(Conversation.Message.ContentItem.RuntimeNotification.Level.INFO, "Document")
                } else item
                else -> item
            }
        }
        // Keep only the tiny presentation flag, never the original provider response/replay data.
        val presentationMetadata = entry.message.providerMetadata.filter { (key, value) ->
            key == "collaborationReceipt" && value == JsonPrimitive(true) &&
                entry.message.role == Conversation.Message.Role.RUNTIME_NOTIFICATION
        }
        var projected = entry.message.copy(
            content = content,
            providerMetadata = JsonObject(entry.message.providerMetadata.filterKeys { it == "compactionBoundary" } + presentationMetadata),
        )
        if (Json.encodeToString(projected).encodeToByteArray().size > MAX_MESSAGE_BYTES) {
            deferred = true
            projected = projected.copy(
                instructions = emptyList(),
                originalIds = emptyList(),
                providerMetadata = JsonObject(presentationMetadata),
                author = when (val author = projected.author) {
                    is Conversation.Message.Author.User -> author.copy(displayName = author.displayName.take(256), identityKey = null)
                    is Conversation.Message.Author.Agent -> author.copy(displayName = author.displayName.take(256))
                    is Conversation.Message.Author.Runtime -> author.copy(displayName = author.displayName.take(256))
                    null -> null
                },
                error = projected.error?.let { it.copy(message = it.message.take(1_024), type = it.type?.take(128)) },
                content = projected.content.map { block ->
                    if (Json.encodeToString<Conversation.Message.ContentItem>(block).encodeToByteArray().size <= MAX_MESSAGE_BYTES / 2) block
                    else when (block) {
                        is Conversation.Message.ContentItem.PeerMessage -> block.copy(text = block.text.take(512).ifEmpty { "…" },
                            sender = block.sender.copy(agentName = block.sender.agentName.take(128), conversationTitle = block.sender.conversationTitle.take(128)),
                            recipient = block.recipient.copy(agentName = block.recipient.agentName.take(128), conversationTitle = block.recipient.conversationTitle.take(128)))
                        is Conversation.Message.ContentItem.PromptMessage -> block.copy(agentName = block.agentName.take(128),
                            snapshot = block.snapshot.copy(entries = block.snapshot.entries.take(32).map { prompt ->
                                if (prompt is PromptSnapshot.Entry.Catalog) prompt.copy(name = prompt.name.take(128), text = "") else prompt
                            }))
                        is Conversation.Message.ContentItem.VisualUpdated -> block.copy(visuals = block.visuals.map {
                            it.copy(title = it.title.take(128), state = JsonObject(emptyMap()))
                        })
                        is Conversation.Message.ContentItem.RuntimeNotification -> block.copy(content = block.content.take(512),
                            details = JsonObject(emptyMap()), attachments = emptyList())
                        else -> Conversation.Message.ContentItem.RuntimeNotification(Conversation.Message.ContentItem.RuntimeNotification.Level.INFO, "Content available on demand")
                    }
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
