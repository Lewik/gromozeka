package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MemoryMaintenanceAction
import com.gromozeka.application.service.memory.MemoryAsyncOperationApplicationService
import com.gromozeka.application.service.memory.MemoryMaintenanceTargetKind
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ActiveGenerationStateSyncService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeIngressService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationRuntimeStateSyncService
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.statesync.observe
import klog.KLoggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ConversationRuntimeApplicationService(
    private val runtimeDispatcher: ConversationRuntimeDispatcher,
    private val runtimeStateSyncService: ConversationRuntimeStateSyncService,
    private val activeGenerationStateSyncService: ActiveGenerationStateSyncService,
    private val memoryOperations: MemoryAsyncOperationApplicationService,
    private val conversationService: ConversationDomainService,
) : ConversationRuntimeService, ConversationRuntimeIngressService {
    private val log = KLoggers.logger(this)

    override suspend fun enqueueAgentInvocation(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
        placement: QueuedMessagePlacement,
    ): Boolean = runtimeDispatcher.enqueueAgentInvocation(conversationId, userMessage, agentDefinitionId, placement)

    override suspend fun enqueueAgentInvocation(
        actorUser: User,
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
        placement: QueuedMessagePlacement,
    ): Boolean = runtimeDispatcher.enqueueAgentInvocation(
        conversationId = conversationId,
        userMessage = userMessage.attributeAuthenticatedSubmission(actorUser),
        agentDefinitionId = agentDefinitionId,
        placement = placement,
        actorUserId = actorUser.id,
    )

    override suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean = runtimeDispatcher.cancelQueuedMessage(conversationId, messageId)

    override suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean = runtimeDispatcher.controlExecution(conversationId, action)

    override suspend fun cancelCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean = runtimeDispatcher.cancelCommandTask(conversationId, taskId)

    override suspend fun cancelCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): Boolean = runtimeDispatcher.cancelCommandMonitor(conversationId, monitorId)

    override suspend fun postMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
    ): Boolean = runtimeDispatcher.postMessage(conversationId, userMessage)

    override suspend fun postMessage(
        actorUser: User,
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
    ): Boolean = runtimeDispatcher.postMessage(
        conversationId = conversationId,
        userMessage = userMessage.attributeAuthenticatedSubmission(actorUser),
        actorUserId = actorUser.id,
    )

    override suspend fun invokeAgent(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
    ): Boolean = runtimeDispatcher.invokeAgent(conversationId, userMessage, agentDefinitionId)

    override suspend fun invokeAgent(
        actorUser: User,
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
    ): Boolean = runtimeDispatcher.invokeAgent(
        conversationId = conversationId,
        userMessage = userMessage.attributeAuthenticatedSubmission(actorUser),
        agentDefinitionId = agentDefinitionId,
        actorUserId = actorUser.id,
    )

    override fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long?,
    ): Flow<ConversationRuntimeEvent> = channelFlow {
        launch {
            runtimeDispatcher.observeConversation(conversationId, afterEventSequence).collect { send(it) }
        }
        launch {
            runtimeStateSyncService.observe(conversationId).collect { state ->
                send(
                    ConversationRuntimeEvent.SnapshotUpdated(
                        conversationId = conversationId,
                        snapshot = state.value,
                        cursorSequence = state.value.lastEventSequence,
                    )
                )
            }
        }
    }

    override fun observeActiveGeneration(
        conversationId: Conversation.Id,
    ): Flow<ActiveGenerationSnapshot?> =
        activeGenerationStateSyncService.observe(conversationId).map { it.value }

    override suspend fun rememberCurrentThread(conversationId: Conversation.Id) {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val queued = memoryOperations.rememberThread(
            conversationIdValue = conversationId.value,
            namespace = MemoryNamespace.forProject(conversation.projectId),
        )
        log.info {
            "Queued current thread for typed memory: conversation=$conversationId operations=${queued.size}"
        }
    }

    override suspend fun consolidateCurrentMemory(conversationId: Conversation.Id) {
        enqueueCurrentMemoryMaintenance(conversationId, MemoryMaintenanceAction.CONSOLIDATE)
    }

    override suspend fun repairCurrentMemory(conversationId: Conversation.Id) {
        enqueueCurrentMemoryMaintenance(conversationId, MemoryMaintenanceAction.REPAIR)
    }

    override suspend fun maintainMemoryEntities(conversationId: Conversation.Id) {
        enqueueCurrentMemoryMaintenance(conversationId, MemoryMaintenanceAction.MAINTAIN_ENTITIES)
    }

    override suspend fun applyCurrentMemoryRetention(conversationId: Conversation.Id) {
        enqueueCurrentMemoryMaintenance(conversationId, MemoryMaintenanceAction.APPLY_RETENTION)
    }

    private suspend fun enqueueCurrentMemoryMaintenance(
        conversationId: Conversation.Id,
        action: MemoryMaintenanceAction,
    ) {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val result = memoryOperations.scheduleMaintenance(
            action = action,
            targetKind = MemoryMaintenanceTargetKind.CONVERSATION_ID,
            targetValue = conversationId.value,
            executionConversationId = conversationId,
            namespace = MemoryNamespace.forProject(conversation.projectId),
        )
        log.info {
            "Queued memory maintenance for conversation $conversationId: action=${action.toolName} run=${result.runId.value}"
        }
    }
}
