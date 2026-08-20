package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.Conversation

internal fun Conversation.Message.editableText(): String? =
    when (role) {
        Conversation.Message.Role.USER -> content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .firstOrNull()
            ?.text

        Conversation.Message.Role.ASSISTANT -> content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .firstOrNull()
            ?.structured
            ?.fullText

        Conversation.Message.Role.SYSTEM -> null
    }

internal fun Conversation.Message.withEditedText(newText: String): List<Conversation.Message.ContentItem> {
    var updated = false
    val updatedContent = content.map { item ->
        if (updated) {
            return@map item
        }

        when {
            role == Conversation.Message.Role.USER && item is Conversation.Message.ContentItem.UserMessage -> {
                updated = true
                item.copy(text = newText)
            }

            role == Conversation.Message.Role.ASSISTANT &&
                item is Conversation.Message.ContentItem.AssistantMessage -> {
                updated = true
                item.copy(
                    structured = item.structured.copy(
                        fullText = newText,
                        ttsText = null,
                        voiceTone = null,
                        suggestedReplies = emptyList(),
                        failedToParse = false,
                    )
                )
            }

            else -> item
        }
    }

    require(updated) { "Message ${id.value} has no editable text content" }
    return updatedContent
}
