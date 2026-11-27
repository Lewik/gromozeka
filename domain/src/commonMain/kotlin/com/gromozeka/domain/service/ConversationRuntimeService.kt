package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageDelivery
import com.gromozeka.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface ConversationRuntimeService {
    suspend fun submitMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id?,
        delivery: MessageDelivery,
    ): Boolean

    fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long? = null,
    ): Flow<ConversationRuntimeEvent>

    fun observeActiveGeneration(
        conversationId: Conversation.Id,
    ): Flow<ActiveGenerationSnapshot?>

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

    suspend fun cancelCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): Boolean

    suspend fun rememberCurrentThread(conversationId: Conversation.Id)
    suspend fun consolidateCurrentMemory(conversationId: Conversation.Id)
    suspend fun repairCurrentMemory(conversationId: Conversation.Id)
    suspend fun maintainMemoryEntities(conversationId: Conversation.Id)
    suspend fun applyCurrentMemoryRetention(conversationId: Conversation.Id)
}

interface ConversationRuntimeIngressService {
    suspend fun submitMessage(
        actorUser: User,
        conversationId: Conversation.Id,
        message: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id?,
        delivery: MessageDelivery,
    ): Boolean
}

@Serializable
enum class QueuedMessagePlacement {
    AFTER_TOOL_RESULT,
    END_OF_TURN,
}
