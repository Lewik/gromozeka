package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import java.security.MessageDigest
import kotlinx.datetime.Clock

class ConversationMessageMemorySourceMapper {
    fun toChatTurn(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        message: Conversation.Message,
    ): MemorySource.ChatTurn? {
        val contentText = message.extractMemoryText()
        if (contentText.isBlank()) {
            return null
        }

        return MemorySource.ChatTurn(
            id = MemorySource.Id("chat:${message.id.value}"),
            namespace = namespace,
            conversationId = conversationId,
            threadId = threadId,
            sourceMessageId = message.id,
            speakerRole = message.role.toMemoryActorRole(),
            authorLabel = message.role.name.lowercase(),
            contentText = contentText,
            contentHash = contentText.sha256(),
            observedAt = message.createdAt,
            createdAt = Clock.System.now(),
        )
    }

    private fun Conversation.Message.extractMemoryText(): String =
        content.mapNotNull { item ->
            when (role) {
                Conversation.Message.Role.USER -> when (item) {
                    is Conversation.Message.ContentItem.UserMessage -> item.text
                    is Conversation.Message.ContentItem.ImageItem -> "[image:${item.source.type}]"
                    else -> null
                }

                Conversation.Message.Role.ASSISTANT -> when (item) {
                    is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                    else -> null
                }

                Conversation.Message.Role.SYSTEM -> when (item) {
                    is Conversation.Message.ContentItem.System -> "[${item.level.name}] ${item.content}"
                    else -> null
                }
            }
        }.joinToString("\n").trim()

    private fun Conversation.Message.Role.toMemoryActorRole(): MemorySource.ActorRole =
        when (this) {
            Conversation.Message.Role.USER -> MemorySource.ActorRole.USER
            Conversation.Message.Role.ASSISTANT -> MemorySource.ActorRole.ASSISTANT
            Conversation.Message.Role.SYSTEM -> MemorySource.ActorRole.SYSTEM
        }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
