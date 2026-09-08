package com.gromozeka.server.telegram

import com.gromozeka.domain.repository.TelegramChannelRepository
import com.gromozeka.domain.service.*
import org.springframework.stereotype.Service

@Service
class TelegramRuntimeTaskGuard(
    private val conversations: ConversationDomainService,
    private val channels: TelegramChannelRepository,
    private val access: TelegramBindingAccess,
) : ConversationRuntimeTaskGuard {
    override suspend fun validate(task: ConversationRuntimeTask) {
        if (task.payload is ConversationRuntimeTask.Payload.ExecutionIncident) return
        val current = conversations.findById(task.conversationId)?.externalChannel
        if (task.externalChannel?.provider != "telegram" && current?.provider != "telegram") return
        if (current == null || current != task.externalChannel) throw TelegramBindingRejected()
        if (task.payload is ConversationRuntimeTask.Payload.PostMessage) return
        val invocation = channels.find(current.connectionId)?.invocations?.singleOrNull { it.id == task.turnId.value }
            ?: throw TelegramBindingRejected()
        if (invocation.completed || invocation.ownerUserId != task.actorUserId || invocation.binding.conversationId != task.conversationId) {
            throw TelegramBindingRejected()
        }
        access.requireUser(invocation)
    }
}
