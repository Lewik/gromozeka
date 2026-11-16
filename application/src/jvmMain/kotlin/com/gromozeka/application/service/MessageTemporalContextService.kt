package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTemporalContext
import com.gromozeka.domain.model.isHumanInput
import org.springframework.stereotype.Service

@Service
class MessageTemporalContextService {
    fun enrich(
        messages: List<Conversation.Message>,
        enabled: Boolean,
    ): List<Conversation.Message> {
        if (!enabled) return messages

        var previousUserMessage: Conversation.Message? = null
        return messages.map { message ->
            if (!message.isHumanInput()) return@map message

            val elapsedSeconds = previousUserMessage
                ?.createdAt
                ?.let { previous ->
                    if (message.createdAt >= previous) {
                        (message.createdAt - previous).inWholeSeconds
                    } else {
                        null
                    }
                }
            previousUserMessage = message

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

}
