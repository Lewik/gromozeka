package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRuntimeService {
    suspend fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message>

    suspend fun rememberCurrentThread(conversationId: Conversation.Id)
    suspend fun consolidateCurrentMemory(conversationId: Conversation.Id)
    suspend fun repairCurrentMemory(conversationId: Conversation.Id)
    suspend fun maintainMemoryEntities(conversationId: Conversation.Id)
    suspend fun applyCurrentMemoryRetention(conversationId: Conversation.Id)
}
