package com.gromozeka.client

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.remote.protocol.MemoryActionCompletedResponse
import com.gromozeka.remote.protocol.MemoryActionRequest
import kotlinx.coroutines.flow.Flow

internal class RemoteConversationRuntimeService(
    private val client: GromozekaWsClient,
) : ConversationRuntimeService {
    override suspend fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message> =
        client.sendMessage(conversationId, userMessage, agent)

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
        client.requestTyped<MemoryActionRequest, MemoryActionCompletedResponse>(MemoryActionRequest(conversationId, action))
    }
}
