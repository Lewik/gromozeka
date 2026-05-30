package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeEventSubscription
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskFailure
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkOutboxEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkQueue
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class InMemoryConversationRuntimeCoordinator : ConversationRuntimeCoordinator {
    private val mutex = Mutex()
    private val tasksByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeTask>>()
    private val activeTasksByConversation = mutableMapOf<Conversation.Id, ConversationRuntimeTask>()
    private val statesByConversation = mutableMapOf<Conversation.Id, ConversationExecutionState>()
    private val toolExecutionsByConversation =
        mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeToolExecution>>()
    private val failedTasksByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeTaskFailure>>()
    private val traceByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeTraceEntry>>()
    private val eventLogByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeEventLogEntry>>()
    private val workOutboxByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeWorkOutboxEntry>>()
    private val completedIdempotencyKeysByConversation = mutableMapOf<Conversation.Id, MutableSet<String>>()
    private val revisionsByConversation = mutableMapOf<Conversation.Id, Long>()
    private val traceSequencesByConversation = mutableMapOf<Conversation.Id, Long>()
    private val eventSequencesByConversation = mutableMapOf<Conversation.Id, Long>()
    private val workSequencesByConversation = mutableMapOf<Conversation.Id, Long>()

    override suspend fun submit(task: ConversationRuntimeTask): Boolean =
        mutex.withLock {
            val state = statesByConversation[task.conversationId]
            if (state?.controlState == ConversationExecutionState.ControlState.STOPPING ||
                state?.controlState == ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                return@withLock false
            }
            if (task.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && state?.activeTaskId == null) {
                return@withLock false
            }
            if (completedIdempotencyKeysByConversation[task.conversationId]?.contains(task.idempotencyKey) == true) {
                return@withLock false
            }
            if (activeTasksByConversation[task.conversationId]?.idempotencyKey == task.idempotencyKey) {
                return@withLock false
            }

            val tasks = tasksByConversation.getOrPut(task.conversationId) { mutableListOf() }
            val userMessageId = task.userMessageIdOrNull()
            tasks.removeAll { existingTask ->
                existingTask.idempotencyKey == task.idempotencyKey ||
                    (userMessageId != null && existingTask.userMessageIdOrNull() == userMessageId)
            }
            val taskToStore = task
            val insertIndex = if (taskToStore.isInternalRuntimeStep()) {
                tasks.indexOfFirst { !it.isInternalRuntimeStep() }.takeIf { it >= 0 } ?: tasks.size
            } else {
                tasks.size
            }
            tasks.add(insertIndex, taskToStore)
            appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task submitted: placement=${task.placement}",
            )
            scheduleNextRunnableTaskIfReady(task.conversationId)
            bumpRevision(task.conversationId)
            true
        }

    override suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerAffinities: Set<ConversationRuntimeWorkerAffinity>,
    ): ConversationRuntimeTask? =
        mutex.withLock {
            val state = statesByConversation[conversationId]
            if (state != null && state.activeTaskId == taskId) {
                if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
                ) {
                    return@withLock null
                }
                val activeTask = activeTasksByConversation[conversationId] ?: return@withLock null
                if (!activeTask.requirements.isSatisfiedBy(workerCapabilities, workerAffinities)) {
                    return@withLock null
                }
                statesByConversation[conversationId] = state.copy(
                    controlState = ConversationExecutionState.ControlState.RUNNING,
                    activeWorkerId = workerId,
                    updatedAt = Clock.System.now(),
                )
                appendTrace(
                    conversationId = conversationId,
                    taskId = taskId,
                    workerId = workerId,
                    kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                    status = ConversationRuntimeTraceEntry.Status.STARTED,
                    message = "Runtime task redelivered to $workerId",
                )
                bumpRevision(conversationId)
                return@withLock activeTask
            }
            if (state != null && (state.controlState != ConversationExecutionState.ControlState.RUNNING || state.activeTaskId != null)) {
                return@withLock null
            }

            val tasks = tasksByConversation[conversationId] ?: return@withLock null
            val taskIndex = tasks.indexOfFirst { it.placement == QueuedMessagePlacement.END_OF_TURN }
            if (taskIndex < 0 || tasks[taskIndex].id != taskId) {
                return@withLock null
            }
            val task = tasks[taskIndex]
            if (!task.requirements.isSatisfiedBy(workerCapabilities, workerAffinities)) {
                return@withLock null
            }
            tasks.removeAt(taskIndex)
            removeScheduledWorkItems(conversationId, task.id)
            activeTasksByConversation[conversationId] = task
            if (tasks.isEmpty()) {
                tasksByConversation.remove(conversationId)
            }

            val now = Clock.System.now()
            statesByConversation[conversationId] = (state ?: ConversationExecutionState(
                conversationId = conversationId,
                controlState = ConversationExecutionState.ControlState.RUNNING,
                activeTaskId = null,
                updatedAt = now,
            )).copy(
                controlState = ConversationExecutionState.ControlState.RUNNING,
                activeTaskId = task.id,
                activeWorkerId = workerId,
                updatedAt = now,
            )
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                workerId = workerId,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task claimed by $workerId",
            )
            bumpRevision(conversationId)
            task
        }

    override suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@withLock false
            }
            val completedControlState = when (state.controlState) {
                ConversationExecutionState.ControlState.PAUSE_REQUESTED -> ConversationExecutionState.ControlState.PAUSED
                else -> state.controlState
            }
            val completedState = state.copy(
                controlState = completedControlState,
                activeTaskId = null,
                activeWorkerId = null,
                updatedAt = Clock.System.now(),
            )
            statesByConversation[conversationId] = completedState
            activeTasksByConversation.remove(conversationId)?.let { completedTask ->
                completedIdempotencyKeysByConversation
                    .getOrPut(conversationId) { mutableSetOf() }
                    .add(completedTask.idempotencyKey)
            }
            appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                workerId = workerId,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_COMPLETED,
                status = ConversationRuntimeTraceEntry.Status.COMPLETED,
                message = "Runtime task completed",
            )
            bumpRevision(conversationId)

            if (completedState.controlState != ConversationExecutionState.ControlState.STOPPING &&
                completedState.controlState != ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                tasksByConversation[conversationId]?.let { tasks ->
                    val hasInternalContinuation = tasks.any { it.isInternalRuntimeStep() }
                    if (!hasInternalContinuation) {
                        val promotedActiveInsertions = tasks
                            .filter { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                            .map { it.copy(placement = QueuedMessagePlacement.END_OF_TURN) }
                        if (promotedActiveInsertions.isNotEmpty()) {
                            val existingEndOfTurn = tasks
                                .filterNot { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                            tasks.clear()
                            tasks.addAll(promotedActiveInsertions + existingEndOfTurn)
                            bumpRevision(conversationId)
                        }
                    }
                }
                scheduleNextRunnableTaskIfReady(conversationId)
            }
            true
        }

    override suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            state.activeTaskId == taskId && state.activeWorkerId == workerId
        }

    override suspend fun failActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        message: String,
        type: String?,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@withLock false
            }
            recordActiveTaskFailure(
                conversationId = conversationId,
                reason = ConversationRuntimeTaskFailure.Reason.EXECUTION_FAILED,
                message = if (type.isNullOrBlank()) message else "$type: $message",
            )
            true
        }

    override suspend fun failPendingTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        message: String,
        type: String?,
    ): Boolean =
        mutex.withLock {
            val tasks = tasksByConversation[conversationId] ?: return@withLock false
            val task = tasks.firstOrNull { it.id == taskId } ?: return@withLock false
            tasks.removeAll { it.id == taskId }
            if (tasks.isEmpty()) {
                tasksByConversation.remove(conversationId)
            }
            removeScheduledWorkItems(conversationId, taskId)
            val failureMessage = if (type.isNullOrBlank()) message else "$type: $message"
            failedTasksByConversation.getOrPut(conversationId) { mutableListOf() }.add(
                ConversationRuntimeTaskFailure(
                    task = task,
                    reason = ConversationRuntimeTaskFailure.Reason.DELIVERY_FAILED,
                    message = failureMessage,
                    workerId = workerId,
                    failedAt = Clock.System.now(),
                )
            )
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                workerId = workerId,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_FAILED,
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "${ConversationRuntimeTaskFailure.Reason.DELIVERY_FAILED}: $failureMessage",
            )
            val state = statesByConversation[conversationId]
            if (state != null && state.activeTaskId == null && tasksByConversation[conversationId].isNullOrEmpty()) {
                statesByConversation.remove(conversationId)
            }
            bumpRevision(conversationId)
            true
        }

    override suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != null) {
                return@withLock false
            }
            if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                clearConversation(conversationId)
                bumpRevision(conversationId)
                return@withLock true
            }
            val hasPendingEndOfTurn = tasksByConversation[conversationId]
                ?.any { it.placement == QueuedMessagePlacement.END_OF_TURN }
                ?: false
            if (hasPendingEndOfTurn) {
                return@withLock false
            }
            statesByConversation.remove(conversationId)
            bumpRevision(conversationId)
            true
        }

    override suspend fun upsertToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != execution.runtimeTaskId || state.activeWorkerId != execution.workerId) {
                return@withLock false
            }
            val executions = toolExecutionsByConversation.getOrPut(conversationId) { mutableListOf() }
            val existingIndex = executions.indexOfFirst { it.toolCallId == execution.toolCallId }
            if (existingIndex >= 0) {
                executions[existingIndex] = execution
            } else {
                executions.add(execution)
            }
            appendTrace(
                conversationId = conversationId,
                taskId = execution.runtimeTaskId,
                workerId = execution.workerId,
                kind = ConversationRuntimeTraceEntry.Kind.TOOL_EXECUTION,
                status = when (execution.status) {
                    ConversationRuntimeToolExecution.Status.RUNNING -> ConversationRuntimeTraceEntry.Status.STARTED
                    ConversationRuntimeToolExecution.Status.COMPLETED -> ConversationRuntimeTraceEntry.Status.COMPLETED
                    ConversationRuntimeToolExecution.Status.FAILED -> ConversationRuntimeTraceEntry.Status.FAILED
                },
                message = "${execution.toolName}: ${execution.status}",
            )
            bumpRevision(conversationId)
            true
        }

    override suspend fun clearToolExecutions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@withLock false
            }
            if (toolExecutionsByConversation.remove(conversationId) != null) {
                bumpRevision(conversationId)
            }
            true
        }

    override suspend fun requestPause(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            when (state.controlState) {
                ConversationExecutionState.ControlState.RUNNING -> {
                    statesByConversation[conversationId] = state.copy(
                        controlState = ConversationExecutionState.ControlState.PAUSE_REQUESTED,
                        updatedAt = Clock.System.now(),
                    )
                    appendControlTrace(conversationId, ConversationExecutionState.ControlState.PAUSE_REQUESTED)
                    bumpRevision(conversationId)
                    true
                }
                ConversationExecutionState.ControlState.PAUSE_REQUESTED,
                ConversationExecutionState.ControlState.PAUSED -> true
                ConversationExecutionState.ControlState.STOPPING,
                ConversationExecutionState.ControlState.INTERRUPTING -> false
            }
        }

    override suspend fun markPaused(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.controlState != ConversationExecutionState.ControlState.PAUSE_REQUESTED) {
                return@withLock false
            }
            statesByConversation[conversationId] = state.copy(
                controlState = ConversationExecutionState.ControlState.PAUSED,
                updatedAt = Clock.System.now(),
            )
            appendControlTrace(conversationId, ConversationExecutionState.ControlState.PAUSED)
            bumpRevision(conversationId)
            true
        }

    override suspend fun requestResume(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.controlState != ConversationExecutionState.ControlState.PAUSED &&
                state.controlState != ConversationExecutionState.ControlState.PAUSE_REQUESTED
            ) {
                return@withLock false
            }
            statesByConversation[conversationId] = state.copy(
                controlState = ConversationExecutionState.ControlState.RUNNING,
                updatedAt = Clock.System.now(),
            )
            appendControlTrace(conversationId, ConversationExecutionState.ControlState.RUNNING)
            scheduleNextRunnableTaskIfReady(conversationId)
            bumpRevision(conversationId)
            true
        }

    override suspend fun requestStop(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            requestTerminalStatus(conversationId, ConversationExecutionState.ControlState.STOPPING)
        }

    override suspend fun requestInterrupt(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            requestTerminalStatus(conversationId, ConversationExecutionState.ControlState.INTERRUPTING)
        }

    override suspend fun fail(conversationId: Conversation.Id) {
        mutex.withLock {
            clearConversation(conversationId)
            bumpRevision(conversationId)
        }
    }

    override suspend fun abort(conversationId: Conversation.Id) {
        mutex.withLock {
            clearConversation(conversationId)
            bumpRevision(conversationId)
        }
    }

    override suspend fun find(conversationId: Conversation.Id): ConversationExecutionState? =
        mutex.withLock {
            statesByConversation[conversationId]
        }

    override suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean =
        mutex.withLock {
            val tasks = tasksByConversation[conversationId] ?: return@withLock false
            val removed = tasks.removeAll { it.userMessageIdOrNull() == messageId }
            if (tasks.isEmpty()) {
                tasksByConversation.remove(conversationId)
            }
            if (removed) {
                removeScheduledWorkItems(conversationId, ConversationRuntimeTask.Id(messageId.value))
                appendTrace(
                    conversationId = conversationId,
                    taskId = ConversationRuntimeTask.Id(messageId.value),
                    kind = ConversationRuntimeTraceEntry.Kind.TASK_CANCELLED,
                    status = ConversationRuntimeTraceEntry.Status.CANCELLED,
                    message = "Queued runtime task cancelled",
                )
                bumpRevision(conversationId)
            }
            removed
        }

    override suspend fun takeActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock emptyList()
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@withLock emptyList()
            }
            val tasks = tasksByConversation[conversationId] ?: return@withLock emptyList()
            val ready = tasks.filter { it.placement == placement }
            tasks.removeAll(ready.toSet())
            if (tasks.isEmpty()) {
                tasksByConversation.remove(conversationId)
            }
            if (ready.isNotEmpty()) {
                bumpRevision(conversationId)
            }
            ready
        }

    override suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeTask> =
        mutex.withLock {
            tasksByConversation[conversationId]?.toList().orEmpty()
        }

    override suspend fun snapshot(conversationId: Conversation.Id): ConversationRuntimeSnapshot =
        mutex.withLock {
            ConversationRuntimeSnapshot(
                revision = revisionsByConversation[conversationId] ?: 0L,
                conversationId = conversationId,
                state = statesByConversation[conversationId],
                activeTask = activeTasksByConversation[conversationId],
                pendingTasks = tasksByConversation[conversationId]?.toList().orEmpty(),
                toolExecutions = toolExecutionsByConversation[conversationId]?.toList().orEmpty(),
                failedTasks = failedTasksByConversation[conversationId]?.toList().orEmpty(),
                trace = traceByConversation[conversationId]?.takeLast(TRACE_SNAPSHOT_LIMIT).orEmpty(),
                lastEventSequence = eventSequencesByConversation[conversationId] ?: 0L,
            )
        }

    override suspend fun recordEvent(event: ConversationRuntimeEvent): ConversationRuntimeEventLogEntry =
        mutex.withLock {
            val eventSequence = (eventSequencesByConversation[event.conversationId] ?: 0L) + 1L
            eventSequencesByConversation[event.conversationId] = eventSequence
            val eventLogEntry = ConversationRuntimeEventLogEntry(
                sequence = eventSequence,
                conversationId = event.conversationId,
                event = event,
                createdAt = Clock.System.now(),
            )
            eventLogByConversation.getOrPut(event.conversationId) { mutableListOf() }.apply {
                add(eventLogEntry)
                trimToLast(EVENT_LOG_RETENTION_LIMIT)
            }
            appendTrace(
                conversationId = event.conversationId,
                taskId = (event as? ConversationRuntimeEvent.MessageEmitted)?.taskId,
                kind = ConversationRuntimeTraceEntry.Kind.EVENT_PUBLISHED,
                status = ConversationRuntimeTraceEntry.Status.COMPLETED,
                message = "${event::class.simpleName ?: "RuntimeEvent"}#$eventSequence",
            )
            bumpRevision(event.conversationId)
            eventLogEntry
        }

    override suspend fun claimUnpublishedEventLogEntries(
        workerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry> =
        mutex.withLock {
            eventLogByConversation.values
                .asSequence()
                .flatten()
                .filter { entry ->
                    val publishLeaseUntil = entry.publishLeaseUntil
                    entry.publishedAt == null &&
                        (publishLeaseUntil == null || publishLeaseUntil < now)
                }
                .sortedWith(compareBy<ConversationRuntimeEventLogEntry> { it.createdAt }.thenBy { it.sequence })
                .take(limit)
                .map { entry ->
                    val leased = entry.copy(
                        publishWorkerId = workerId,
                        publishLeaseUntil = leaseUntil,
                    )
                    replaceEventLogEntry(leased)
                    leased
                }
                .toList()
        }

    override suspend fun markEventLogEntryPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        workerId: String,
        publishedAt: Instant,
    ): Boolean =
        mutex.withLock {
            val entries = eventLogByConversation[conversationId] ?: return@withLock false
            val index = entries.indexOfFirst { it.sequence == sequence }
            if (index < 0) {
                return@withLock false
            }
            val entry = entries[index]
            if (entry.publishedAt != null) {
                return@withLock true
            }
            if (entry.publishWorkerId != null && entry.publishWorkerId != workerId) {
                return@withLock false
            }
            entries[index] = entry.copy(
                publishedAt = publishedAt,
                publishWorkerId = null,
                publishLeaseUntil = null,
            )
            true
        }

    override suspend fun releasePublishedWorkItem(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): Boolean =
        mutex.withLock {
            val entries = workOutboxByConversation[conversationId] ?: return@withLock false
            val index = entries.indexOfFirst { it.item.taskId == taskId }
            if (index < 0) {
                return@withLock false
            }
            val entry = entries[index]
            if (entry.publishedAt == null && entry.publishWorkerId == null && entry.publishLeaseUntil == null) {
                return@withLock true
            }
            entries[index] = entry.copy(
                publishedAt = null,
                publishWorkerId = null,
                publishLeaseUntil = null,
            )
            true
        }

    override suspend fun claimUnpublishedWorkItems(
        workerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeWorkOutboxEntry> =
        mutex.withLock {
            workOutboxByConversation.values
                .asSequence()
                .flatten()
                .filter { entry ->
                    val publishLeaseUntil = entry.publishLeaseUntil
                    entry.publishedAt == null &&
                        canPublishWorkItem(entry.item) &&
                        (publishLeaseUntil == null || publishLeaseUntil < now)
                }
                .sortedWith(compareBy<ConversationRuntimeWorkOutboxEntry> { it.createdAt }.thenBy { it.sequence })
                .take(limit)
                .map { entry ->
                    val leased = entry.copy(
                        publishWorkerId = workerId,
                        publishLeaseUntil = leaseUntil,
                    )
                    replaceWorkOutboxEntry(leased)
                    leased
                }
                .toList()
        }

    override suspend fun markWorkItemPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        workerId: String,
        publishedAt: Instant,
    ): Boolean =
        mutex.withLock {
            val entries = workOutboxByConversation[conversationId] ?: return@withLock false
            val index = entries.indexOfFirst { it.sequence == sequence }
            if (index < 0) {
                return@withLock false
            }
            val entry = entries[index]
            if (entry.publishedAt != null) {
                return@withLock true
            }
            if (entry.publishWorkerId != null && entry.publishWorkerId != workerId) {
                return@withLock false
            }
            entries[index] = entry.copy(
                publishedAt = publishedAt,
                publishWorkerId = null,
                publishLeaseUntil = null,
            )
            true
        }

    override suspend fun listEventLogEntries(
        conversationId: Conversation.Id,
        afterSequence: Long?,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry> =
        mutex.withLock {
            val entries = eventLogByConversation[conversationId].orEmpty()
            if (afterSequence == null) {
                entries.takeLast(limit)
            } else {
                entries.filter { it.sequence > afterSequence }.take(limit)
            }
        }

    private fun replaceEventLogEntry(entry: ConversationRuntimeEventLogEntry) {
        val entries = eventLogByConversation[entry.conversationId] ?: return
        val index = entries.indexOfFirst { it.sequence == entry.sequence }
        if (index >= 0) {
            entries[index] = entry
        }
    }

    private fun replaceWorkOutboxEntry(entry: ConversationRuntimeWorkOutboxEntry) {
        val entries = workOutboxByConversation[entry.item.conversationId] ?: return
        val index = entries.indexOfFirst { it.sequence == entry.sequence }
        if (index >= 0) {
            entries[index] = entry
        }
    }

    private fun canPublishWorkItem(item: ConversationRuntimeWorkItem): Boolean {
        val state = statesByConversation[item.conversationId]
        return state == null ||
            (state.controlState == ConversationExecutionState.ControlState.RUNNING && state.activeTaskId == null)
    }

    private fun removeScheduledWorkItems(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ) {
        val entries = workOutboxByConversation[conversationId] ?: return
        entries.removeAll {
            it.item.reason == ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED &&
                it.item.taskId == taskId
        }
        if (entries.isEmpty()) {
            workOutboxByConversation.remove(conversationId)
        }
    }

    private fun requestTerminalStatus(
        conversationId: Conversation.Id,
        controlState: ConversationExecutionState.ControlState,
    ): Boolean {
        val removedTasks = tasksByConversation.remove(conversationId)?.size ?: 0
        workOutboxByConversation.remove(conversationId)
        val state = statesByConversation[conversationId]
        if (state == null && removedTasks == 0) {
            return false
        }
        if (state?.activeTaskId != null) {
            statesByConversation[conversationId] = state.copy(
                controlState = controlState,
                updatedAt = Clock.System.now(),
            )
        }
        appendControlTrace(conversationId, controlState)
        if (state?.activeTaskId == null) {
            clearConversation(conversationId)
        }
        bumpRevision(conversationId)
        return true
    }

    private fun bumpRevision(conversationId: Conversation.Id): Long {
        val next = (revisionsByConversation[conversationId] ?: 0L) + 1L
        revisionsByConversation[conversationId] = next
        return next
    }

    private fun clearConversation(conversationId: Conversation.Id) {
        tasksByConversation.remove(conversationId)
        activeTasksByConversation.remove(conversationId)
        statesByConversation.remove(conversationId)
        toolExecutionsByConversation.remove(conversationId)
        workOutboxByConversation.remove(conversationId)
    }

    private fun scheduleNextRunnableTaskIfReady(conversationId: Conversation.Id) {
        val state = statesByConversation[conversationId]
        if (state?.activeTaskId != null) {
            return
        }
        if (state != null && state.controlState != ConversationExecutionState.ControlState.RUNNING) {
            return
        }
        val task = tasksByConversation[conversationId]
            ?.firstOrNull { it.placement == QueuedMessagePlacement.END_OF_TURN }
            ?: return
        appendWorkItemIfMissing(
            ConversationRuntimeWorkItem(
                conversationId = conversationId,
                reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
                taskId = task.id,
                requirements = task.requirements,
                createdAt = Clock.System.now(),
            )
        )
    }

    private fun appendWorkItemIfMissing(item: ConversationRuntimeWorkItem) {
        val entries = workOutboxByConversation.getOrPut(item.conversationId) { mutableListOf() }
        if (entries.any { it.item.reason == item.reason && it.item.taskId == item.taskId }) {
            return
        }
        val sequence = (workSequencesByConversation[item.conversationId] ?: 0L) + 1L
        workSequencesByConversation[item.conversationId] = sequence
        entries += ConversationRuntimeWorkOutboxEntry(
            sequence = sequence,
            item = item,
            createdAt = item.createdAt,
        )
    }

    private fun appendControlTrace(
        conversationId: Conversation.Id,
        controlState: ConversationExecutionState.ControlState,
    ) {
        appendTrace(
            conversationId = conversationId,
            kind = ConversationRuntimeTraceEntry.Kind.CONTROL_REQUESTED,
            status = ConversationRuntimeTraceEntry.Status.UPDATED,
            message = "Runtime control requested: $controlState",
        )
    }

    private fun appendTrace(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id? = null,
        workerId: String? = null,
        kind: ConversationRuntimeTraceEntry.Kind,
        status: ConversationRuntimeTraceEntry.Status,
        message: String? = null,
    ): ConversationRuntimeTraceEntry {
        val sequence = (traceSequencesByConversation[conversationId] ?: 0L) + 1L
        traceSequencesByConversation[conversationId] = sequence
        val entry = ConversationRuntimeTraceEntry(
            sequence = sequence,
            conversationId = conversationId,
            taskId = taskId,
            workerId = workerId,
            kind = kind,
            status = status,
            message = message,
            createdAt = Clock.System.now(),
        )
        traceByConversation.getOrPut(conversationId) { mutableListOf() }.apply {
            add(entry)
            trimToLast(TRACE_RETENTION_LIMIT)
        }
        return entry
    }

    private fun <T> MutableList<T>.trimToLast(limit: Int) {
        if (size > limit) {
            subList(0, size - limit).clear()
        }
    }

    private fun recordActiveTaskFailure(
        conversationId: Conversation.Id,
        reason: ConversationRuntimeTaskFailure.Reason,
        message: String,
    ): ConversationRuntimeTaskFailure? {
        val state = statesByConversation[conversationId] ?: return null
        val task = activeTasksByConversation.remove(conversationId) ?: return null
        statesByConversation.remove(conversationId)
        tasksByConversation.remove(conversationId)
        workOutboxByConversation.remove(conversationId)
        toolExecutionsByConversation.remove(conversationId)
        val failure = ConversationRuntimeTaskFailure(
            task = task,
            reason = reason,
            message = message,
            workerId = state.activeWorkerId,
            failedAt = Clock.System.now(),
        )
        failedTasksByConversation.getOrPut(conversationId) { mutableListOf() }.add(failure)
        appendTrace(
            conversationId = conversationId,
            taskId = task.id,
            workerId = state.activeWorkerId,
            kind = ConversationRuntimeTraceEntry.Kind.TASK_FAILED,
            status = ConversationRuntimeTraceEntry.Status.FAILED,
            message = "$reason: $message",
        )
        bumpRevision(conversationId)
        return failure
    }

    private companion object {
        const val TRACE_SNAPSHOT_LIMIT = 200
        const val TRACE_RETENTION_LIMIT = 2_000
        const val EVENT_LOG_RETENTION_LIMIT = 10_000
    }
}
class InMemoryConversationRuntimeWorkQueue : ConversationRuntimeWorkQueue {
    private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)

    override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

    override suspend fun submit(item: ConversationRuntimeWorkItem) {
        channel.send(InMemoryConversationRuntimeWorkDelivery(item, channel))
    }

    private class InMemoryConversationRuntimeWorkDelivery(
        override val item: ConversationRuntimeWorkItem,
        private val channel: Channel<ConversationRuntimeWorkDelivery>,
        override val retryCount: Int = 0,
    ) : ConversationRuntimeWorkDelivery {
        override val isFinalRetry: Boolean = false

        override suspend fun ack() = Unit

        override suspend fun retry() {
            delay(ConversationRuntimeTiming.workOutboxScanIntervalMillis)
            channel.send(InMemoryConversationRuntimeWorkDelivery(item, channel, retryCount + 1))
        }

        override suspend fun fail() = Unit
    }
}

class InMemoryConversationRuntimeEventBus : ConversationRuntimeEventBus {
    private val mutex = Mutex()
    private val subscribersByConversation = mutableMapOf<Conversation.Id, MutableSet<Channel<ConversationRuntimeEvent>>>()

    override suspend fun subscribe(conversationId: Conversation.Id): ConversationRuntimeEventSubscription {
        val channel = Channel<ConversationRuntimeEvent>(Channel.UNLIMITED)
        mutex.withLock {
            subscribersByConversation.getOrPut(conversationId) { mutableSetOf() }.add(channel)
        }

        return object : ConversationRuntimeEventSubscription {
            override val events: Flow<ConversationRuntimeEvent> = channel.receiveAsFlow()

            override suspend fun close() {
                mutex.withLock {
                    val subscribers = subscribersByConversation[conversationId]
                    subscribers?.remove(channel)
                    if (subscribers?.isEmpty() == true) {
                        subscribersByConversation.remove(conversationId)
                    }
                }
                channel.close()
            }
        }
    }

    override suspend fun publish(event: ConversationRuntimeEvent) {
        val subscribers = mutex.withLock {
            subscribersByConversation[event.conversationId]?.toList().orEmpty()
        }
        subscribers.forEach { subscriber ->
            subscriber.trySend(event)
        }
    }
}
