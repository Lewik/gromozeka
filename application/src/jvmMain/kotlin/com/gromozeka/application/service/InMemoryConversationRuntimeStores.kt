package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeActiveTaskAssignment
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeEventSubscription
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkOutboxEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkConsumer
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
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
    private val commandTasksByConversation = mutableMapOf<Conversation.Id, MutableList<CommandTask>>()
    private val incidentsByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeTaskIncident>>()
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
        worker: ConversationRuntimeWorkerIdentity,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerWorkspaceIds: Set<Workspace.Id>,
    ): ConversationRuntimeTask? =
        mutex.withLock {
            val now = Clock.System.now()
            val state = statesByConversation[conversationId]
            if (state != null && state.activeTaskId == taskId) {
                if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
                ) {
                    return@withLock null
                }
                val activeTask = activeTasksByConversation[conversationId] ?: return@withLock null
                if (state.activeWorker != worker) {
                    return@withLock null
                }
                if (!activeTask.requirements.isSatisfiedBy(worker.workerId, workerCapabilities, workerWorkspaceIds)) {
                    return@withLock null
                }
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
            if (!task.requirements.isSatisfiedBy(worker.workerId, workerCapabilities, workerWorkspaceIds)) {
                return@withLock null
            }
            tasks.removeAt(taskIndex)
            removeScheduledWorkItems(conversationId, task.id)
            activeTasksByConversation[conversationId] = task
            if (tasks.isEmpty()) {
                tasksByConversation.remove(conversationId)
            }

            statesByConversation[conversationId] = (state ?: ConversationExecutionState(
                conversationId = conversationId,
                controlState = ConversationExecutionState.ControlState.RUNNING,
                activeTaskId = null,
                updatedAt = now,
            )).copy(
                controlState = ConversationExecutionState.ControlState.RUNNING,
                activeTaskId = task.id,
                activeWorker = worker,
                activeTaskStartedAt = null,
                updatedAt = now,
            )
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task claimed by ${worker.workerId.value}/${worker.sessionId.value}",
            )
            bumpRevision(conversationId)
            task
        }

    override suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != taskId ||
                state.activeWorker != worker ||
                state.activeTaskStartedAt == null
            ) {
                return@withLock false
            }
            val completedControlState = when (state.controlState) {
                ConversationExecutionState.ControlState.PAUSE_REQUESTED -> ConversationExecutionState.ControlState.PAUSED
                else -> state.controlState
            }
            val completedState = state.copy(
                controlState = completedControlState,
                activeTaskId = null,
                activeWorker = null,
                activeTaskStartedAt = null,
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
                worker = worker,
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

    override suspend fun markActiveTaskStarted(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        startedAt: Instant,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@withLock false
            }
            if (state.activeTaskStartedAt != null) {
                return@withLock true
            }
            statesByConversation[conversationId] = state.copy(
                activeTaskStartedAt = startedAt,
                updatedAt = startedAt,
            )
            appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_STARTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task execution started",
            )
            bumpRevision(conversationId)
            true
        }

    override suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            state.activeTaskId == taskId && state.activeWorker == worker
        }

    override suspend fun markActiveTaskInDoubt(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock null
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@withLock null
            }
            check(state.activeTaskStartedAt != null) {
                "Cannot mark a runtime task outcome unknown before execution started: ${taskId.value}"
            }
            recordActiveTaskIncidentLocked(
                conversationId = conversationId,
                kind = ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
                message = message,
                errorType = errorType,
            )
        }

    override suspend fun recordClaimedTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock null
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@withLock null
            }
            check(state.activeTaskStartedAt == null) {
                "Cannot record a delivery failure after runtime task execution started: ${taskId.value}"
            }
            recordActiveTaskIncidentLocked(
                conversationId = conversationId,
                kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                message = message,
                errorType = errorType,
            )
        }

    override suspend fun recordPendingTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            val tasks = tasksByConversation[conversationId] ?: return@withLock null
            val task = tasks.firstOrNull { it.id == taskId } ?: return@withLock null
            tasks.removeAll { it.id == taskId }
            if (tasks.isEmpty()) {
                tasksByConversation.remove(conversationId)
            }
            removeScheduledWorkItems(conversationId, taskId)
            val incident = ConversationRuntimeTaskIncident(
                task = task,
                kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                message = message,
                errorType = errorType,
                worker = worker,
                executionStartedAt = null,
                occurredAt = Clock.System.now(),
            )
            incidentsByConversation.getOrPut(conversationId) { mutableListOf() }.add(incident)
            completedIdempotencyKeysByConversation
                .getOrPut(conversationId) { mutableSetOf() }
                .add(task.idempotencyKey)
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_FAILED,
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "${ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED}: " +
                    formatIncidentMessage(errorType, message),
            )
            enqueueIncidentTaskIfNeeded(incident)
            if (statesByConversation[conversationId] == null) {
                statesByConversation[conversationId] = ConversationExecutionState(
                    conversationId = conversationId,
                    controlState = ConversationExecutionState.ControlState.RUNNING,
                    activeTaskId = null,
                    updatedAt = Clock.System.now(),
                )
            }
            scheduleNextRunnableTaskIfReady(conversationId)
            bumpRevision(conversationId)
            incident
        }

    override suspend fun listActiveTaskAssignments(): List<ConversationRuntimeActiveTaskAssignment> =
        mutex.withLock {
            activeTasksByConversation.mapNotNull { (conversationId, task) ->
                val state = statesByConversation[conversationId] ?: return@mapNotNull null
                val worker = state.activeWorker ?: return@mapNotNull null
                ConversationRuntimeActiveTaskAssignment(
                    conversationId = conversationId,
                    task = task,
                    worker = worker,
                    startedAt = state.activeTaskStartedAt,
                )
            }
        }

    override suspend fun findTaskIncident(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            incidentsByConversation[conversationId]?.lastOrNull { it.task.id == taskId }
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
            if (state.activeTaskId != execution.runtimeTaskId || state.activeWorker != execution.worker) {
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
                worker = execution.worker,
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
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@withLock false
            }
            if (toolExecutionsByConversation.remove(conversationId) != null) {
                bumpRevision(conversationId)
            }
            true
        }

    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult =
        mutex.withLock {
            val tasks = commandTasksByConversation.getOrPut(task.conversationId) { mutableListOf() }
            val existingIndex = tasks.indexOfFirst { it.id == task.id }
            val previousStatus = tasks.getOrNull(existingIndex)?.status
            if (existingIndex >= 0) {
                tasks[existingIndex] = task
            } else {
                tasks.add(task)
            }
            val retainedTasks = tasks
                .partition { it.status == CommandTask.Status.WORKING }
                .let { (working, terminal) ->
                    working + terminal.sortedBy { it.createdAt }.takeLast(COMMAND_TASK_TERMINAL_RETENTION_LIMIT)
                }
                .sortedBy { it.createdAt }
            val retainedTaskIds = retainedTasks.mapTo(mutableSetOf()) { it.id }
            val evictedTasks = tasks.filterNot { it.id in retainedTaskIds }
            tasks.clear()
            tasks.addAll(retainedTasks)
            if (previousStatus != task.status) {
                appendTrace(
                    conversationId = task.conversationId,
                    kind = ConversationRuntimeTraceEntry.Kind.COMMAND_TASK,
                    status = task.status.toTraceStatus(),
                    message = "${task.id.value}: ${task.status}",
                )
            }
            bumpRevision(task.conversationId)
            CommandTaskUpsertResult(evictedTasks)
        }

    override suspend fun findCommandTasks(): List<CommandTask> = mutex.withLock {
        commandTasksByConversation.values.flatten()
    }

    override suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask? = mutex.withLock {
        commandTasksByConversation[conversationId]?.firstOrNull { it.id == taskId }
    }

    override suspend fun requestCommandTaskCancellation(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        requestedAt: Instant,
    ): Boolean =
        mutex.withLock {
            requestCommandTaskCancellationLocked(conversationId, taskId, requestedAt)
        }

    override suspend fun requestCommandTaskCancellations(
        conversationId: Conversation.Id,
        requestedAt: Instant,
    ): Int =
        mutex.withLock {
            commandTasksByConversation[conversationId]
                .orEmpty()
                .filter { it.status == CommandTask.Status.WORKING }
                .count { task ->
                    requestCommandTaskCancellationLocked(conversationId, task.id, requestedAt)
                }
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
        worker: ConversationRuntimeWorkerIdentity,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock emptyList()
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
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
                commandTasks = commandTasksByConversation[conversationId]?.toList().orEmpty(),
                incidents = incidentsByConversation[conversationId]?.toList().orEmpty(),
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
        leaseOwnerId: String,
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
                        publishLeaseOwnerId = leaseOwnerId,
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
        leaseOwnerId: String,
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
            if (entry.publishLeaseOwnerId != null && entry.publishLeaseOwnerId != leaseOwnerId) {
                return@withLock false
            }
            entries[index] = entry.copy(
                publishedAt = publishedAt,
                publishLeaseOwnerId = null,
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
            if (entry.publishedAt == null && entry.publishLeaseOwnerId == null && entry.publishLeaseUntil == null) {
                return@withLock true
            }
            entries[index] = entry.copy(
                publishedAt = null,
                publishLeaseOwnerId = null,
                publishLeaseUntil = null,
            )
            true
        }

    override suspend fun claimUnpublishedWorkItems(
        leaseOwnerId: String,
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
                        publishLeaseOwnerId = leaseOwnerId,
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
        leaseOwnerId: String,
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
            if (entry.publishLeaseOwnerId != null && entry.publishLeaseOwnerId != leaseOwnerId) {
                return@withLock false
            }
            entries[index] = entry.copy(
                publishedAt = publishedAt,
                publishLeaseOwnerId = null,
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

    private fun requestCommandTaskCancellationLocked(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        requestedAt: Instant,
    ): Boolean {
        val tasks = commandTasksByConversation[conversationId] ?: return false
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) {
            return false
        }
        val task = tasks[index]
        if (task.status != CommandTask.Status.WORKING) {
            return false
        }
        if (task.cancellationRequestedAt != null) {
            return true
        }
        tasks[index] = task.copy(
            cancellationRequestedAt = requestedAt,
            statusMessage = "Cancellation requested",
            updatedAt = requestedAt,
        )
        appendTrace(
            conversationId = conversationId,
            kind = ConversationRuntimeTraceEntry.Kind.COMMAND_TASK,
            status = ConversationRuntimeTraceEntry.Status.UPDATED,
            message = "${task.id.value}: cancellation requested",
        )
        bumpRevision(conversationId)
        return true
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
        worker: ConversationRuntimeWorkerIdentity? = null,
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
            worker = worker,
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

    private fun CommandTask.Status.toTraceStatus(): ConversationRuntimeTraceEntry.Status = when (this) {
        CommandTask.Status.WORKING -> ConversationRuntimeTraceEntry.Status.STARTED
        CommandTask.Status.COMPLETED -> ConversationRuntimeTraceEntry.Status.COMPLETED
        CommandTask.Status.FAILED -> ConversationRuntimeTraceEntry.Status.FAILED
        CommandTask.Status.CANCELLED -> ConversationRuntimeTraceEntry.Status.CANCELLED
    }

    private fun recordActiveTaskIncidentLocked(
        conversationId: Conversation.Id,
        kind: ConversationRuntimeTaskIncident.Kind,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? {
        val state = statesByConversation[conversationId] ?: return null
        val task = activeTasksByConversation[conversationId] ?: return null
        check(
            (kind == ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN) ==
                (state.activeTaskStartedAt != null)
        ) {
            "Runtime incident kind does not match execution start boundary: task=${task.id.value} kind=$kind"
        }
        activeTasksByConversation.remove(conversationId)
        completedIdempotencyKeysByConversation
            .getOrPut(conversationId) { mutableSetOf() }
            .add(task.idempotencyKey)
        tasksByConversation[conversationId]?.removeAll { it.isInternalRuntimeStep() }
        if (tasksByConversation[conversationId].isNullOrEmpty()) {
            tasksByConversation.remove(conversationId)
        }
        workOutboxByConversation.remove(conversationId)
        toolExecutionsByConversation.remove(conversationId)
        val incident = ConversationRuntimeTaskIncident(
            task = task,
            kind = kind,
            message = message,
            errorType = errorType,
            worker = state.activeWorker,
            executionStartedAt = state.activeTaskStartedAt,
            occurredAt = Clock.System.now(),
        )
        incidentsByConversation.getOrPut(conversationId) { mutableListOf() }.add(incident)
        appendTrace(
            conversationId = conversationId,
            taskId = task.id,
            worker = state.activeWorker,
            kind = when (kind) {
                ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED ->
                    ConversationRuntimeTraceEntry.Kind.TASK_FAILED
                ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN ->
                    ConversationRuntimeTraceEntry.Kind.TASK_IN_DOUBT
            },
            status = ConversationRuntimeTraceEntry.Status.FAILED,
            message = "$kind: " +
                formatIncidentMessage(errorType, message),
        )

        if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
            state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            clearConversation(conversationId)
            bumpRevision(conversationId)
            return incident
        }

        enqueueIncidentTaskIfNeeded(incident)
        val pendingTasks = tasksByConversation[conversationId].orEmpty()
        if (pendingTasks.isEmpty()) {
            statesByConversation.remove(conversationId)
        } else {
            statesByConversation[conversationId] = state.copy(
                controlState = when (state.controlState) {
                    ConversationExecutionState.ControlState.PAUSE_REQUESTED ->
                        ConversationExecutionState.ControlState.PAUSED
                    else -> state.controlState
                },
                activeTaskId = null,
                activeWorker = null,
                activeTaskStartedAt = null,
                updatedAt = Clock.System.now(),
            )
            scheduleNextRunnableTaskIfReady(conversationId)
        }
        bumpRevision(conversationId)
        return incident
    }

    private fun enqueueIncidentTaskIfNeeded(incident: ConversationRuntimeTaskIncident) {
        if (incident.task.payload is ConversationRuntimeTask.Payload.ExecutionIncident) {
            return
        }
        val task = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${incident.task.id.value}:incident"),
            conversationId = incident.task.conversationId,
            payload = ConversationRuntimeTask.Payload.ExecutionIncident(incident.task.id),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "${incident.task.idempotencyKey}:incident",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            ),
            createdAt = incident.occurredAt,
        )
        val tasks = tasksByConversation.getOrPut(task.conversationId) { mutableListOf() }
        if (tasks.none { it.id == task.id }) {
            tasks.add(0, task)
            appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Execution incident handling task submitted",
            )
        }
    }

    private fun formatIncidentMessage(errorType: String?, message: String): String =
        if (errorType.isNullOrBlank()) message else "$errorType: $message"

    private companion object {
        const val COMMAND_TASK_TERMINAL_RETENTION_LIMIT = 100
        const val TRACE_SNAPSHOT_LIMIT = 200
        const val TRACE_RETENTION_LIMIT = 2_000
        const val EVENT_LOG_RETENTION_LIMIT = 10_000
    }
}
class InMemoryConversationRuntimeWorkQueue : ConversationRuntimeWorkPublisher, ConversationRuntimeWorkConsumer {
    private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)

    override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

    override suspend fun submit(item: ConversationRuntimeWorkItem) {
        channel.send(InMemoryConversationRuntimeWorkDelivery(item, channel))
    }

    private class InMemoryConversationRuntimeWorkDelivery(
        override val item: ConversationRuntimeWorkItem,
        private val channel: Channel<ConversationRuntimeWorkDelivery>,
        override val redeliveryCount: Int = 0,
    ) : ConversationRuntimeWorkDelivery {
        override val isFinalRedelivery: Boolean = false

        override suspend fun acknowledge() = Unit

        override suspend fun redeliver() {
            delay(ConversationRuntimeTiming.workOutboxScanIntervalMillis)
            channel.send(InMemoryConversationRuntimeWorkDelivery(item, channel, redeliveryCount + 1))
        }

        override suspend fun reject() = Unit
    }
}

class InMemoryConversationRuntimeWorkerRegistry : ConversationRuntimeWorkerRegistry {
    private val mutex = Mutex()
    private val registrations = mutableMapOf<ConversationRuntimeWorkerId, ConversationRuntimeWorkerRegistration>()

    override suspend fun register(
        registration: ConversationRuntimeWorkerRegistration,
        staleBefore: Instant,
    ): Boolean =
        mutex.withLock {
            val workerId = registration.identity.workerId
            val existing = registrations[workerId]
            if (existing != null &&
                existing.identity != registration.identity &&
                existing.isOnline(staleBefore)
            ) {
                return@withLock false
            }
            registrations[workerId] = registration
            true
        }

    override suspend fun heartbeat(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean =
        mutex.withLock {
            val existing = registrations[identity.workerId] ?: return@withLock false
            if (existing.identity != identity || existing.stoppedAt != null || at < existing.lastHeartbeatAt) {
                return@withLock false
            }
            registrations[identity.workerId] = existing.copy(lastHeartbeatAt = at)
            true
        }

    override suspend fun unregister(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean =
        mutex.withLock {
            val existing = registrations[identity.workerId] ?: return@withLock false
            if (existing.identity != identity || at < existing.startedAt) {
                return@withLock false
            }
            registrations[identity.workerId] = existing.copy(
                lastHeartbeatAt = maxOf(existing.lastHeartbeatAt, at),
                stoppedAt = at,
            )
            true
        }

    override suspend fun find(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerRegistration? =
        mutex.withLock {
            registrations[workerId]
        }

    override suspend fun list(): List<ConversationRuntimeWorkerRegistration> =
        mutex.withLock {
            registrations.values.sortedBy { it.identity.workerId.value }
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
