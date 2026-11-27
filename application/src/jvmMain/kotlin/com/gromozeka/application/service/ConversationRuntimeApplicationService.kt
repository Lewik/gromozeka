package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MemoryMaintenanceAction
import com.gromozeka.application.service.memory.MemoryAsyncOperationApplicationService
import com.gromozeka.application.service.memory.MemoryMaintenanceTargetKind
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageDelivery
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
    private val collaboration: AgentCollaborationService? = null,
) : ConversationRuntimeService, ConversationRuntimeIngressService {
    private val log = KLoggers.logger(this)

    override suspend fun submitMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id?,
        delivery: MessageDelivery,
    ): Boolean {
        if (agentDefinitionId == null) requireLocalInput(conversationId)
        else requireConnectedAgent(conversationId, agentDefinitionId)
        return runtimeDispatcher.submitMessage(
            conversationId = conversationId,
            message = message,
            agentDefinitionId = agentDefinitionId,
            delivery = delivery,
            autoRespondAgentIds = if (agentDefinitionId == null) autoResponders(conversationId, message) else emptySet(),
        )
    }

    override suspend fun submitMessage(
        actorUser: User,
        conversationId: Conversation.Id,
        message: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id?,
        delivery: MessageDelivery,
    ): Boolean {
        val attributedMessage = message.attributeAuthenticatedSubmission(actorUser)
        if (agentDefinitionId == null) requireLocalInput(conversationId)
        else {
            require(actorUser.canUseAi) { "AI access is not allowed for this user" }
            requireConnectedAgent(conversationId, agentDefinitionId)
        }
        return runtimeDispatcher.submitMessage(
            conversationId = conversationId,
            message = attributedMessage,
            agentDefinitionId = agentDefinitionId,
            delivery = delivery,
            actorUserId = actorUser.id,
            autoRespondAgentIds = if (agentDefinitionId == null && actorUser.canUseAi) {
                autoResponders(conversationId, attributedMessage)
            } else emptySet(),
        )
    }

    override suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean = runtimeDispatcher.cancelQueuedMessage(conversationId, messageId)

    override suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean {
        val accepted = runtimeDispatcher.controlExecution(conversationId, action)
        // A durable peer/user wait remains cancellable after the foreground turn has ended.
        val cancelledCollaboration = if (action in setOf(ConversationRuntimeControlAction.STOP, ConversationRuntimeControlAction.INTERRUPT)) {
            collaboration?.cancelConversation(conversationId) == true
        } else false
        return accepted || cancelledCollaboration
    }

    override suspend fun cancelCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean = runtimeDispatcher.cancelCommandTask(conversationId, taskId)

    override suspend fun cancelCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): Boolean = runtimeDispatcher.cancelCommandMonitor(conversationId, monitorId)

    private suspend fun autoResponders(
        conversationId: Conversation.Id,
        message: Conversation.Message,
    ): Set<AgentDefinition.Id> {
        return requireNotNull(conversationService.findById(conversationId)) {
            "Conversation not found: ${conversationId.value}"
        }.also { require(it.externalChannel == null) { "Send messages through the connected external channel" } }.autoRespondersFor(message)
    }

    private suspend fun requireConnectedAgent(
        conversationId: Conversation.Id,
        agentDefinitionId: AgentDefinition.Id,
    ) {
        val conversation = conversationService.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        require(conversation.externalChannel == null) { "Send messages through the connected external channel" }
        require(Conversation.Participant.Agent(agentDefinitionId) in conversation.participants) {
            "Agent ${agentDefinitionId.value} is not connected to conversation ${conversationId.value}"
        }
    }

    private suspend fun requireLocalInput(conversationId: Conversation.Id) {
        val conversation = requireNotNull(conversationService.findById(conversationId))
        require(conversation.externalChannel == null) { "Send messages through the connected external channel" }
    }

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
        requireLocalInput(conversationId)
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
        requireLocalInput(conversationId)
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
