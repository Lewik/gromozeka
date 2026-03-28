package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock

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
}
