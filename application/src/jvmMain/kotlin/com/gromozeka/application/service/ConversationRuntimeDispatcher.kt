package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.QueuedMessagePlacement
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ConversationRuntimeDispatcher(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
) {
    private val log = KLoggers.logger(this)

    suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
        placement: QueuedMessagePlacement,
    ): Boolean {
        val state = runtimeCoordinator.find(conversationId)
        val pendingTasks = runtimeCoordinator.listPending(conversationId)
        val effectivePlacement = if (placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && state?.activeTaskId == null) {
            QueuedMessagePlacement.END_OF_TURN
        } else {
            placement
        }
        val canAcceptQueuedTask = state == null ||
            state.controlState == ConversationExecutionState.ControlState.RUNNING ||
            state.controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED ||
            state.controlState == ConversationExecutionState.ControlState.PAUSED
        val canQueue = when (effectivePlacement) {
            QueuedMessagePlacement.AFTER_TOOL_RESULT -> canAcceptQueuedTask && state?.activeTaskId != null
            QueuedMessagePlacement.END_OF_TURN ->
                canAcceptQueuedTask &&
                    (
                        placement == QueuedMessagePlacement.AFTER_TOOL_RESULT ||
                            state != null ||
                            pendingTasks.any { it.placement == QueuedMessagePlacement.END_OF_TURN }
                    )
        }
        if (!canQueue) {
            log.info {
                "Rejected queued message without active runtime: conversation=${conversationId.value} " +
                    "message=${userMessage.id.value} placement=$placement"
            }
            return false
        }

        val task = queuedRuntimeTask(conversationId, userMessage, agentDefinitionId, effectivePlacement)
        val accepted = submitRuntimeTask(task)
        if (accepted) {
            log.info {
                "Queued runtime message: conversation=${conversationId.value} message=${userMessage.id.value} " +
                    "placement=$effectivePlacement requestedPlacement=$placement"
            }
        }
        return accepted
    }

    suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean {
        val removed = runtimeCoordinator.cancelByMessageId(conversationId, messageId)
        if (removed) {
            publishRuntimeSnapshot(conversationId)
            log.info { "Cancelled runtime queued message: conversation=${conversationId.value} message=${messageId.value}" }
        }
        return removed
    }

    suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean {
        val cancelledCommands = if (action == ConversationRuntimeControlAction.INTERRUPT) {
            runtimeCoordinator.requestCommandTaskCancellations(conversationId, Clock.System.now())
        } else {
            0
        }
        val runtimeControlAccepted = when (action) {
            ConversationRuntimeControlAction.PAUSE -> runtimeCoordinator.requestPause(conversationId)
            ConversationRuntimeControlAction.RESUME -> runtimeCoordinator.requestResume(conversationId)
            ConversationRuntimeControlAction.STOP -> runtimeCoordinator.requestStop(conversationId)
            ConversationRuntimeControlAction.INTERRUPT -> runtimeCoordinator.requestInterrupt(conversationId)
        }
        val accepted = runtimeControlAccepted || cancelledCommands > 0
        if (accepted) {
            publishRuntimeSnapshot(conversationId)
            log.info { "Runtime execution control accepted: conversation=${conversationId.value} action=$action" }
        } else {
            log.info { "Runtime execution control ignored without active turn: conversation=${conversationId.value} action=$action" }
        }
        return accepted
    }

    suspend fun cancelCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean {
        val accepted = runtimeCoordinator.requestCommandTaskCancellation(
            conversationId = conversationId,
            taskId = taskId,
            requestedAt = Clock.System.now(),
        )
        if (accepted) {
            publishRuntimeSnapshot(conversationId)
        }
        return accepted
    }

    suspend fun cancelCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): Boolean {
        val accepted = runtimeCoordinator.requestCommandMonitorCancellation(
            conversationId = conversationId,
            monitorId = monitorId,
            requestedAt = Clock.System.now(),
        )
        if (accepted) {
            publishRuntimeSnapshot(conversationId)
        }
        return accepted
    }

    suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
    ): Boolean {
        val task = queuedRuntimeTask(conversationId, userMessage, agentDefinitionId, QueuedMessagePlacement.END_OF_TURN)
        return submitRuntimeTask(task)
    }

    suspend fun submitMemoryRunCompletion(
        conversationId: Conversation.Id,
        runId: MemoryRun.Id,
        agentDefinitionId: AgentDefinition.Id,
        statusToolName: String,
    ): Boolean =
        submitRuntimeTask(
            ConversationRuntimeTask(
                id = ConversationRuntimeTask.Id("${runId.value}:conversation-delivery"),
                conversationId = conversationId,
                payload = ConversationRuntimeTask.Payload.MemoryRunCompletion(
                    runId = runId,
                    agentDefinitionId = agentDefinitionId,
                    statusToolName = statusToolName,
                ),
                placement = QueuedMessagePlacement.END_OF_TURN,
                idempotencyKey = "conversation:${conversationId.value}:memory-run:${runId.value}:delivery",
                requirements = ConversationRuntimeTaskRequirements(
                    capabilities = setOf(
                        ConversationRuntimeCapability.CONVERSATION_TURN,
                        ConversationRuntimeCapability.MEMORY_PIPELINE,
                    ),
                    target = ConversationRuntimeTaskTarget.Server,
                ),
                createdAt = Clock.System.now(),
            )
        )

    suspend fun submitBackgroundActivityCompletion(
        conversationId: Conversation.Id,
        sourceKey: String,
    ): Boolean =
        submitRuntimeTask(
            ConversationRuntimeTask(
                id = ConversationRuntimeTask.Id("background-activity:${stableIdentifier(sourceKey)}"),
                conversationId = conversationId,
                payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion(
                    sourceKey = sourceKey,
                ),
                placement = QueuedMessagePlacement.END_OF_TURN,
                idempotencyKey =
                    "conversation:${conversationId.value}:background-activity:$sourceKey",
                requirements = ConversationRuntimeTaskRequirements(
                    capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                    target = ConversationRuntimeTaskTarget.Server,
                ),
                createdAt = Clock.System.now(),
            )
        )

    private fun stableIdentifier(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    suspend fun publishSnapshot(conversationId: Conversation.Id) {
        publishRuntimeSnapshot(conversationId)
    }

    fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long? = null,
    ): Flow<ConversationRuntimeEvent> = flow {
        val subscription = runtimeEventBus.subscribe(conversationId)
        try {
            var emittedEventSequence = afterEventSequence ?: 0L
            replayRuntimeEvents(conversationId, afterEventSequence) { event ->
                event.cursorSequence?.let { emittedEventSequence = maxOf(emittedEventSequence, it) }
                emit(event)
            }
            emit(runtimeSnapshotEvent(conversationId))
            subscription.events.collect { event ->
                val cursorSequence = event.cursorSequence
                if (cursorSequence != null &&
                    cursorSequence <= emittedEventSequence &&
                    event !is ConversationRuntimeEvent.SnapshotUpdated
                ) {
                    return@collect
                }
                cursorSequence?.let { emittedEventSequence = maxOf(emittedEventSequence, it) }
                emit(event)
            }
        } finally {
            subscription.close()
        }
    }

    private suspend fun submitRuntimeTask(task: ConversationRuntimeTask): Boolean {
        val accepted = runtimeCoordinator.submit(task)
        if (accepted) {
            publishRuntimeSnapshot(task.conversationId)
        }
        return accepted
    }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        publishLiveRuntimeEvent(runtimeSnapshotEvent(conversationId))
    }

    private suspend fun publishRuntimeEvent(event: ConversationRuntimeEvent) {
        val logEntry = runtimeCoordinator.recordEvent(event)
        publishLiveRuntimeEvent(event.withCursorSequence(logEntry.sequence))
    }

    private suspend fun publishLiveRuntimeEvent(event: ConversationRuntimeEvent): Boolean =
        try {
            runtimeEventBus.publish(event)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to publish live runtime event; durable cursor replay will recover it: " +
                    "conversation=${event.conversationId.value} event=${event::class.simpleName} error=${error.message}"
            }
            false
        }

    private suspend fun runtimeSnapshotEvent(conversationId: Conversation.Id): ConversationRuntimeEvent.SnapshotUpdated =
        ConversationRuntimeEvent.SnapshotUpdated(
            conversationId = conversationId,
            snapshot = runtimeCoordinator.snapshot(conversationId),
        )

    private suspend fun replayRuntimeEvents(
        conversationId: Conversation.Id,
        afterEventSequence: Long?,
        emitEvent: suspend (ConversationRuntimeEvent) -> Unit,
    ) {
        var cursor = afterEventSequence
        while (true) {
            val entries = runtimeCoordinator.listEventLogEntries(
                conversationId = conversationId,
                afterSequence = cursor,
                limit = EVENT_REPLAY_BATCH_SIZE,
            )
            if (entries.isEmpty()) {
                return
            }
            entries.forEach { entry ->
                emitEvent(entry.event.withCursorSequence(entry.sequence))
            }
            cursor = entries.last().sequence
            if (entries.size < EVENT_REPLAY_BATCH_SIZE) {
                return
            }
        }
    }

    private fun ConversationRuntimeEvent.withCursorSequence(sequence: Long): ConversationRuntimeEvent =
        when (this) {
            is ConversationRuntimeEvent.SnapshotUpdated -> copy(cursorSequence = sequence)
            is ConversationRuntimeEvent.MessageEmitted -> copy(cursorSequence = sequence)
            is ConversationRuntimeEvent.ExecutionCompleted -> copy(cursorSequence = sequence)
            is ConversationRuntimeEvent.ExecutionFailed -> copy(cursorSequence = sequence)
        }

    private fun queuedRuntimeTask(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
        placement: QueuedMessagePlacement,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(userMessage.id.value),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.UserTurn(
                userMessage = userMessage,
                agentDefinitionId = agentDefinitionId,
            ),
            placement = placement,
            idempotencyKey = "conversation:${conversationId.value}:message:${userMessage.id.value}",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )

    private companion object {
        const val EVENT_REPLAY_BATCH_SIZE = 1_000
    }
}
