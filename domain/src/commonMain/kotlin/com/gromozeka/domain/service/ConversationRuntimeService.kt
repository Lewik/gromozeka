package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface ConversationRuntimeService {
    suspend fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message>

    suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): Boolean = false

    suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean = false

    suspend fun rememberCurrentThread(conversationId: Conversation.Id)
    suspend fun consolidateCurrentMemory(conversationId: Conversation.Id)
    suspend fun repairCurrentMemory(conversationId: Conversation.Id)
    suspend fun maintainMemoryEntities(conversationId: Conversation.Id)
    suspend fun applyCurrentMemoryRetention(conversationId: Conversation.Id)
}

@Serializable
enum class QueuedMessagePlacement {
    AFTER_TOOL_RESULT,
    END_OF_TURN,
}
