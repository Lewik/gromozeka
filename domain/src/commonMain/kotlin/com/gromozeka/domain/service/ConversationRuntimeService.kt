package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface ConversationRuntimeService {
    suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Boolean

    fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long? = null,
    ): Flow<ConversationRuntimeEvent>

    suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): Boolean

    suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean

    suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean

    suspend fun cancelCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean

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
