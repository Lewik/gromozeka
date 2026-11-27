package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTemporalContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class MessageTemporalContextService {
    fun enrich(
        messages: List<Conversation.Message>,
        enabled: Boolean,
        previousHumanMessageAt: Instant? = null,
    ): List<Conversation.Message> {
        if (!enabled) return messages

        var previousUserMessageAt = previousHumanMessageAt
        return messages.map { message ->
            if (!message.isHumanInputMessage()) return@map message

            val elapsedSeconds = previousUserMessageAt
                ?.let { previous ->
                    if (message.createdAt >= previous) {
                        (message.createdAt - previous).inWholeSeconds
                    } else {
                        null
                    }
                }
            previousUserMessageAt = message.createdAt

            message.copy(
                instructions = message.instructions
                    .filterNot { it is Conversation.Message.Instruction.MessageTemporalRuntimeContext } +
                    Conversation.Message.Instruction.MessageTemporalRuntimeContext(
                        MessageTemporalContext(
                            sentAt = message.createdAt,
                            elapsedSincePreviousUserMessageSeconds = elapsedSeconds,
                        )
                    )
            )
        }
    }

    fun latestHumanMessageAt(messages: List<Conversation.Message>, inherited: Instant? = null): Instant? =
        messages.lastOrNull { it.isHumanInputMessage() }?.createdAt ?: inherited

    private fun Conversation.Message.isHumanInputMessage(): Boolean =
        role == Conversation.Message.Role.USER &&
            content.any { it is Conversation.Message.ContentItem.UserMessage || it is Conversation.Message.ContentItem.VisualInteraction } &&
            providerMetadata["synthetic"] != JsonPrimitive(true) &&
            instructions.none { it is Conversation.Message.Instruction.Source.Agent }
}
