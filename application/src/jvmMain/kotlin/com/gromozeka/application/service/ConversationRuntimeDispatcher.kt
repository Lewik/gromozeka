package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ConversationRuntimeDispatcher(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val runtimeWorkPublisher: ConversationRuntimeWorkPublisher,
    private val runtimeWorkerRegistry: ConversationRuntimeWorkerRegistry,
    @Qualifier("applicationScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val outboxLeaseOwnerId = "server:${uuid7()}"

    init {
        launchRuntimeLoop("work-outbox-publish", ConversationRuntimeTiming.workOutboxScanIntervalMillis) {
            publishRuntimeWorkOutbox()
        }
        launchRuntimeLoop("event-outbox-publish", ConversationRuntimeTiming.eventOutboxScanIntervalMillis) {
            publishRuntimeEventOutbox()
        }
        launchRuntimeLoop(
            "worker-availability-scan",
            ConversationRuntimeTiming.workerAvailabilityScanIntervalMillis,
        ) {
            recordUnavailableWorkerIncidents()
        }
    }

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
            publishRuntimeWorkOutbox()
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

    suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agentDefinitionId: AgentDefinition.Id,
    ): Boolean {
        val task = queuedRuntimeTask(conversationId, userMessage, agentDefinitionId, QueuedMessagePlacement.END_OF_TURN)
        return submitRuntimeTask(task)
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
            publishRuntimeWorkOutbox()
        }
        return accepted
    }

    private fun launchRuntimeLoop(
        name: String,
        intervalMillis: Long,
        block: suspend () -> Unit,
    ) {
        coroutineScope.launch {
            while (true) {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.error(error) { "Conversation runtime background loop failed: name=$name error=${error.message}" }
                }
                delay(intervalMillis)
            }
        }
    }

    private suspend fun recordUnavailableWorkerIncidents() {
        val now = Clock.System.now()
        val staleBefore = now - ConversationRuntimeTiming.workerRegistrationStaleAfter
        val registrations = runtimeWorkerRegistry.list().associateBy { it.identity.workerId }
        val incidents = runtimeCoordinator.listActiveTaskAssignments().mapNotNull { assignment ->
            val registration = registrations[assignment.worker.workerId]
            val unavailableReason = registration.unavailableReason(assignment.worker, staleBefore)
                ?: return@mapNotNull null
            if (assignment.startedAt == null) {
                runtimeCoordinator.recordClaimedTaskDeliveryFailure(
                    conversationId = assignment.conversationId,
                    taskId = assignment.task.id,
                    worker = assignment.worker,
                    message = "$unavailableReason before task execution started; the task was not executed",
                    errorType = "WorkerUnavailable",
                )
            } else {
                runtimeCoordinator.markActiveTaskInDoubt(
                    conversationId = assignment.conversationId,
                    taskId = assignment.task.id,
                    worker = assignment.worker,
                    message = "$unavailableReason while task execution was active; the task outcome is unknown",
                    errorType = "WorkerUnavailable",
                )
            }
        }
        if (incidents.isEmpty()) {
            return
        }

        incidents.forEach { incident ->
            publishRuntimeSnapshot(incident.task.conversationId)
            publishRuntimeEvent(
                ConversationRuntimeEvent.ExecutionFailed(
                    conversationId = incident.task.conversationId,
                    message = incident.message,
                    failureType = incident.kind.name,
                )
            )
        }
        publishRuntimeWorkOutbox()
        log.warn {
            "Recorded conversation runtime incidents after worker loss: " +
                incidents.joinToString {
                    "${it.task.conversationId.value}/${it.task.id.value}/${it.kind}"
                }
        }
    }

    private fun ConversationRuntimeWorkerRegistration?.unavailableReason(
        expectedWorker: com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity,
        staleBefore: kotlinx.datetime.Instant,
    ): String? =
        when {
            this == null ->
                "Worker ${expectedWorker.workerId.value}/${expectedWorker.sessionId.value} is not registered"
            identity != expectedWorker ->
                "Worker ${expectedWorker.workerId.value}/${expectedWorker.sessionId.value} was replaced by " +
                    "${identity.workerId.value}/${identity.sessionId.value}"
            stoppedAt != null ->
                "Worker ${identity.workerId.value}/${identity.sessionId.value} stopped at $stoppedAt"
            lastHeartbeatAt < staleBefore ->
                "Worker ${identity.workerId.value}/${identity.sessionId.value} has not reported since " +
                    "$lastHeartbeatAt"
            else -> null
        }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        publishLiveRuntimeEvent(runtimeSnapshotEvent(conversationId))
    }

    private suspend fun publishRuntimeEvent(event: ConversationRuntimeEvent) {
        val logEntry = runtimeCoordinator.recordEvent(event)
        if (publishLiveRuntimeEvent(event.withCursorSequence(logEntry.sequence))) {
            runtimeCoordinator.markEventLogEntryPublished(
                conversationId = logEntry.conversationId,
                sequence = logEntry.sequence,
                leaseOwnerId = outboxLeaseOwnerId,
                publishedAt = Clock.System.now(),
            )
        }
    }

    private suspend fun publishRuntimeEventOutbox() {
        val now = Clock.System.now()
        val entries = runtimeCoordinator.claimUnpublishedEventLogEntries(
            leaseOwnerId = outboxLeaseOwnerId,
            now = now,
            leaseUntil = now + ConversationRuntimeTiming.eventPublishLeaseDuration,
            limit = EVENT_OUTBOX_BATCH_SIZE,
        )
        entries.forEach { entry ->
            if (publishLiveRuntimeEvent(entry.event.withCursorSequence(entry.sequence))) {
                runtimeCoordinator.markEventLogEntryPublished(
                    conversationId = entry.conversationId,
                    sequence = entry.sequence,
                    leaseOwnerId = outboxLeaseOwnerId,
                    publishedAt = Clock.System.now(),
                )
            }
        }
    }

    private suspend fun publishRuntimeWorkOutbox() {
        val now = Clock.System.now()
        val entries = runtimeCoordinator.claimUnpublishedWorkItems(
            leaseOwnerId = outboxLeaseOwnerId,
            now = now,
            leaseUntil = now + ConversationRuntimeTiming.workPublishLeaseDuration,
            limit = WORK_OUTBOX_BATCH_SIZE,
        )
        entries.forEach { entry ->
            try {
                runtimeWorkPublisher.submit(entry.item)
                runtimeCoordinator.markWorkItemPublished(
                    conversationId = entry.item.conversationId,
                    sequence = entry.sequence,
                    leaseOwnerId = outboxLeaseOwnerId,
                    publishedAt = Clock.System.now(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn(error) {
                    "Failed to publish runtime work item; durable outbox will retry after lease expires: " +
                        "conversation=${entry.item.conversationId.value} task=${entry.item.taskId.value} " +
                        "sequence=${entry.sequence} error=${error.message}"
                }
            }
        }
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
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )

    private companion object {
        const val EVENT_REPLAY_BATCH_SIZE = 1_000
        const val EVENT_OUTBOX_BATCH_SIZE = 1_000
        const val WORK_OUTBOX_BATCH_SIZE = 1_000
    }
}
