package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import org.springframework.stereotype.Service

@Service
class ExternalConversationIngressApplicationService(
    private val conversations: ConversationDomainService,
    private val dispatcher: ConversationRuntimeDispatcher,
    private val coordinator: ConversationRuntimeCoordinator,
) : ExternalConversationIngressService {
    override suspend fun importMessage(channel: ExternalConversationChannel, message: Conversation.Message, replaceOriginalId: Conversation.Message.Id?): Boolean {
        requireBinding(channel, message.conversationId)
        return dispatcher.importChannelMessage(channel, message, replaceOriginalId)
    }

    override suspend fun invokeAgent(channel: ExternalConversationChannel, conversationId: Conversation.Id,
        rootMessageId: Conversation.Message.Id, agentId: AgentDefinition.Id, actor: User, invocationId: String): Boolean {
        require(actor.canUseAi) { "AI access is not allowed for this user" }
        val conversation = requireBinding(channel, conversationId)
        require(Conversation.Participant.Agent(agentId) in conversation.participants)
        require(Conversation.Participant.User(actor.id) in conversation.participants)
        return dispatcher.invokeChannelAgent(channel, conversationId, rootMessageId, agentId, actor.id, invocationId)
    }

    override suspend fun stop(conversationId: Conversation.Id, turnId: ConversationRuntimeTurnId): Boolean {
        val stopped = coordinator.requestTurnStop(conversationId, turnId)
        if (stopped) dispatcher.publishSnapshot(conversationId)
        return stopped
    }

    private suspend fun requireBinding(channel: ExternalConversationChannel, conversationId: Conversation.Id): Conversation =
        requireNotNull(conversations.findById(conversationId)).also {
            require(it.externalChannel == channel) { "External conversation binding changed" }
        }
}
