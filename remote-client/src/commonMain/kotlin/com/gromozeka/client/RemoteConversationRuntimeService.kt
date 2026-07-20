package com.gromozeka.client

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.remote.protocol.CancelQueuedMessageRequest
import com.gromozeka.remote.protocol.CancelCommandTaskRequest
import com.gromozeka.remote.protocol.ControlConversationRuntimeRequest
import com.gromozeka.remote.protocol.EnqueueMessageRequest
import com.gromozeka.remote.protocol.MemoryActionAcceptedResponse
import com.gromozeka.remote.protocol.MemoryActionRequest
import com.gromozeka.remote.protocol.OperationResultResponse
import com.gromozeka.remote.protocol.SubmitMessageRequest
import kotlinx.coroutines.flow.Flow

internal class RemoteConversationRuntimeService(
    private val client: GromozekaWsClient,
) : ConversationRuntimeService {
    override suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
    ): Boolean =
        client.requestTyped<SubmitMessageRequest, OperationResultResponse>(
            SubmitMessageRequest(conversationId, userMessage, agentDefinitionId)
        ).success

    override fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long?,
    ): Flow<ConversationRuntimeEvent> =
        client.observeConversation(conversationId, afterEventSequence)

    override suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
        placement: QueuedMessagePlacement,
    ): Boolean =
        client.requestTyped<EnqueueMessageRequest, OperationResultResponse>(
            EnqueueMessageRequest(conversationId, userMessage, agentDefinitionId, placement)
        ).success

    override suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean =
        client.requestTyped<CancelQueuedMessageRequest, OperationResultResponse>(
            CancelQueuedMessageRequest(conversationId, messageId)
        ).success

    override suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean =
        client.requestTyped<ControlConversationRuntimeRequest, OperationResultResponse>(
            ControlConversationRuntimeRequest(conversationId, action)
        ).success

    override suspend fun cancelCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean =
        client.requestTyped<CancelCommandTaskRequest, OperationResultResponse>(
            CancelCommandTaskRequest(conversationId, taskId)
        ).success

    override suspend fun rememberCurrentThread(conversationId: Conversation.Id) =
        runMemoryAction(conversationId, MemoryAction.REMEMBER_THREAD)

    override suspend fun consolidateCurrentMemory(conversationId: Conversation.Id) =
        runMemoryAction(conversationId, MemoryAction.CONSOLIDATE)

    override suspend fun repairCurrentMemory(conversationId: Conversation.Id) =
        runMemoryAction(conversationId, MemoryAction.REPAIR)

    override suspend fun maintainMemoryEntities(conversationId: Conversation.Id) =
        runMemoryAction(conversationId, MemoryAction.MAINTAIN_ENTITIES)

    override suspend fun applyCurrentMemoryRetention(conversationId: Conversation.Id) =
        runMemoryAction(conversationId, MemoryAction.APPLY_RETENTION)

    private suspend fun runMemoryAction(conversationId: Conversation.Id, action: MemoryAction) {
        client.requestTyped<MemoryActionRequest, MemoryActionAcceptedResponse>(MemoryActionRequest(conversationId, action))
    }
}
