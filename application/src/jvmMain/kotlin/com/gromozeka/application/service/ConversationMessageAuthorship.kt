package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.requireEditableHistoryContent

fun Conversation.Message.attributeAuthenticatedSubmission(actorUser: User): Conversation.Message {
    require(role == Conversation.Message.Role.USER) { "Client submissions must be user messages" }
    require(content.isNotEmpty() && content.all { item -> when (item) {
        is ContentItem.UserMessage, is ContentItem.VisualInteraction, is ContentItem.ImageItem,
        is ContentItem.DocumentItem, is ContentItem.ArtifactItem -> true
        else -> false
    } }) { "Client submissions cannot contain provider or service message blocks" }
    return copy(author = Conversation.Message.Author.User(actorUser.id, actorUser.displayName))
}

internal fun requireEditableConversationMessage(message: Conversation.Message, newContent: List<ContentItem>) {
    require(message.role == Conversation.Message.Role.USER || message.role == Conversation.Message.Role.ASSISTANT) {
        "Service messages cannot be edited"
    }
    requireEditableHistoryContent(newContent)
}
