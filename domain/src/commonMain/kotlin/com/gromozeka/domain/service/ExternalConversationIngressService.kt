package com.gromozeka.domain.service

import com.gromozeka.domain.model.*

class ExternalConversationIngressDeferred : IllegalStateException("Conversation is stopping; external input remains queued")

interface ExternalConversationIngressService {
    suspend fun importMessage(channel: ExternalConversationChannel, message: Conversation.Message, replaceOriginalId: Conversation.Message.Id? = null): Boolean
    suspend fun invokeAgent(channel: ExternalConversationChannel, conversationId: Conversation.Id,
        rootMessageId: Conversation.Message.Id, agentId: AgentDefinition.Id, actor: User, invocationId: String): Boolean
    suspend fun stop(conversationId: Conversation.Id, turnId: ConversationRuntimeTurnId): Boolean
}
