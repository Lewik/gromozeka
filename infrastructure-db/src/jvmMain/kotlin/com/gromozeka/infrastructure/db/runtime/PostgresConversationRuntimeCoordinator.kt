package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeActiveTaskAssignment
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkOutboxEntry
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

@Service
@DependsOn("postgresFlyway")
class PostgresConversationRuntimeCoordinator(
    private val dataSource: DataSource,
    private val json: Json,
) : ConversationRuntimeCoordinator {

    override suspend fun submit(task: ConversationRuntimeTask): Boolean =
        mutateRecord(task.conversationId, createIfMissing = true) { record ->
            if (record.state?.controlState == ConversationExecutionState.ControlState.STOPPING ||
                record.state?.controlState == ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                return@mutateRecord false
            }
            if (task.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && record.state?.activeTaskId == null) {
                return@mutateRecord false
            }
            if (task.idempotencyKey in record.completedIdempotencyKeys) {
                return@mutateRecord false
            }
            if (record.activeTask?.idempotencyKey == task.idempotencyKey) {
                return@mutateRecord false
            }

            val userMessageId = task.userMessageIdOrNull()
            val pendingTasks = record.pendingTasks
                .filterNot { existingTask ->
                    existingTask.idempotencyKey == task.idempotencyKey ||
                        (userMessageId != null && existingTask.userMessageIdOrNull() == userMessageId)
                }
                .toMutableList()
            val insertIndex = if (task.isInternalRuntimeStep()) {
                pendingTasks.indexOfFirst { !it.isInternalRuntimeStep() }.takeIf { it >= 0 } ?: pendingTasks.size
            } else {
                pendingTasks.size
            }
            pendingTasks.add(insertIndex, task)

            record.pendingTasks = pendingTasks
            record.appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task submitted: placement=${task.placement}",
            )
            record.scheduleNextRunnableTaskIfReady(task.conversationId)
            record.bumpRevision()
            true
        }

    override suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): ConversationRuntimeTask? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val now = Clock.System.now()
            val state = record.state
            if (state != null && state.activeTaskId == taskId) {
                if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
                ) {
                    return@mutateRecord null
                }
                val activeTask = record.activeTask ?: return@mutateRecord null
                if (state.activeWorker != worker) {
                    return@mutateRecord null
                }
                if (!activeTask.requirements.isSatisfiedBy(worker.workerId, workerCapabilities, workerWorkspaceMountIds)) {
                    return@mutateRecord null
                }
                return@mutateRecord activeTask
            }
            if (state != null && (state.controlState != ConversationExecutionState.ControlState.RUNNING || state.activeTaskId != null)) {
                return@mutateRecord null
            }

            val taskIndex = record.pendingTasks.indexOfFirst { it.placement == QueuedMessagePlacement.END_OF_TURN }
            if (taskIndex < 0 || record.pendingTasks[taskIndex].id != taskId) {
                return@mutateRecord null
            }
            val task = record.pendingTasks[taskIndex]
            if (!task.requirements.isSatisfiedBy(worker.workerId, workerCapabilities, workerWorkspaceMountIds)) {
                return@mutateRecord null
            }

            record.pendingTasks = record.pendingTasks.toMutableList().apply { removeAt(taskIndex) }
            record.removeScheduledWorkItems(conversationId, task.id)
            record.activeTask = task
            record.state = (state ?: ConversationExecutionState(
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
            record.appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task claimed by ${worker.workerId.value}/${worker.sessionId.value}",
            )
            record.bumpRevision()
            task
        }

    override suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != taskId ||
                state.activeWorker != worker ||
                state.activeTaskStartedAt == null
            ) {
                return@mutateRecord false
            }
            val completedControlState = when (state.controlState) {
                ConversationExecutionState.ControlState.PAUSE_REQUESTED -> ConversationExecutionState.ControlState.PAUSED
                else -> state.controlState
            }
            record.state = state.copy(
                controlState = completedControlState,
                activeTaskId = null,
                activeWorker = null,
                activeTaskStartedAt = null,
                updatedAt = Clock.System.now(),
            )
            record.activeTask?.let { completedTask ->
                record.completedIdempotencyKeys = record.completedIdempotencyKeys + completedTask.idempotencyKey
            }
            record.activeTask = null
            record.appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_COMPLETED,
                status = ConversationRuntimeTraceEntry.Status.COMPLETED,
                message = "Runtime task completed",
            )
            record.bumpRevision()

            if (completedControlState != ConversationExecutionState.ControlState.STOPPING &&
                completedControlState != ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                if (record.pendingTasks.none { it.isInternalRuntimeStep() }) {
                    val activeInsertions = record.pendingTasks
                        .filter { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                        .map { it.copy(placement = QueuedMessagePlacement.END_OF_TURN) }
                    if (activeInsertions.isNotEmpty()) {
                        val existingEndOfTurn = record.pendingTasks
                            .filterNot { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                        record.pendingTasks = activeInsertions + existingEndOfTurn
                        record.bumpRevision()
                    }
                }
                record.scheduleNextRunnableTaskIfReady(conversationId)
            }
            true
        }

    override suspend fun markActiveTaskStarted(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        startedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@mutateRecord false
            }
            if (state.activeTaskStartedAt != null) {
                return@mutateRecord true
            }
            record.state = state.copy(
                activeTaskStartedAt = startedAt,
                updatedAt = startedAt,
            )
            record.appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_STARTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task execution started",
            )
            record.bumpRevision()
            true
        }

    override suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean =
        readRecord(conversationId)?.state?.let { state ->
            state.activeTaskId == taskId && state.activeWorker == worker
        } ?: false

    override suspend fun markActiveTaskInDoubt(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord null
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@mutateRecord null
            }
            check(state.activeTaskStartedAt != null) {
                "Cannot mark a runtime task outcome unknown before execution started: ${taskId.value}"
            }
            record.recordActiveTaskIncident(
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
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord null
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@mutateRecord null
            }
            check(state.activeTaskStartedAt == null) {
                "Cannot record a delivery failure after runtime task execution started: ${taskId.value}"
            }
            record.recordActiveTaskIncident(
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
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val task = record.pendingTasks.firstOrNull { it.id == taskId } ?: return@mutateRecord null
            record.pendingTasks = record.pendingTasks.filterNot { it.id == taskId }
            record.removeScheduledWorkItems(conversationId, taskId)
            val incident = ConversationRuntimeTaskIncident(
                task = task,
                kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                message = message,
                errorType = errorType,
                worker = worker,
                executionStartedAt = null,
                occurredAt = Clock.System.now(),
            )
            record.incidents = record.incidents + incident
            record.completedIdempotencyKeys = record.completedIdempotencyKeys + task.idempotencyKey
            record.appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                worker = worker,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_FAILED,
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "${ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED}: " +
                    if (errorType.isNullOrBlank()) message else "$errorType: $message",
            )
            record.enqueueIncidentTaskIfNeeded(incident)
            if (record.state == null) {
                record.state = ConversationExecutionState(
                    conversationId = conversationId,
                    controlState = ConversationExecutionState.ControlState.RUNNING,
                    activeTaskId = null,
                    updatedAt = Clock.System.now(),
                )
            }
            record.scheduleNextRunnableTaskIfReady(conversationId)
            record.bumpRevision()
            incident
        }

    override suspend fun listActiveTaskAssignments(): List<ConversationRuntimeActiveTaskAssignment> =
        readAllRecords().mapNotNull { record ->
            val task = record.activeTask ?: return@mapNotNull null
            val state = record.state ?: return@mapNotNull null
            val worker = state.activeWorker ?: return@mapNotNull null
            ConversationRuntimeActiveTaskAssignment(
                conversationId = record.conversationId,
                task = task,
                worker = worker,
                startedAt = state.activeTaskStartedAt,
            )
        }

    override suspend fun findTaskIncident(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeTaskIncident? =
        readRecord(conversationId)?.incidents?.lastOrNull { it.task.id == taskId }

    override suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != null) {
                return@mutateRecord false
            }
            if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                record.clearRuntime()
                record.bumpRevision()
                return@mutateRecord true
            }
            if (record.pendingTasks.any { it.placement == QueuedMessagePlacement.END_OF_TURN }) {
                return@mutateRecord false
            }
            record.state = null
            record.bumpRevision()
            true
        }

    override suspend fun upsertToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != execution.runtimeTaskId || state.activeWorker != execution.worker) {
                return@mutateRecord false
            }
            val executions = record.toolExecutions.toMutableList()
            val existingIndex = executions.indexOfFirst { it.toolCallId == execution.toolCallId }
            if (existingIndex >= 0) {
                executions[existingIndex] = execution
            } else {
                executions += execution
            }
            record.toolExecutions = executions
            record.appendTrace(
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
            record.bumpRevision()
            true
        }

    override suspend fun clearToolExecutions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@mutateRecord false
            }
            if (record.toolExecutions.isNotEmpty()) {
                record.toolExecutions = emptyList()
                record.bumpRevision()
            }
            true
        }

    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult =
        mutateRecord(task.conversationId, createIfMissing = true) { record ->
            val tasks = record.commandTasks.toMutableList()
            val existingIndex = tasks.indexOfFirst { it.id == task.id }
            val previousStatus = tasks.getOrNull(existingIndex)?.status
            if (existingIndex >= 0) {
                tasks[existingIndex] = task
            } else {
                tasks += task
            }
            val retainedTasks = tasks
                .partition { it.status == CommandTask.Status.WORKING }
                .let { (working, terminal) ->
                    working + terminal.sortedBy { it.createdAt }.takeLast(COMMAND_TASK_TERMINAL_RETENTION_LIMIT)
                }
                .sortedBy { it.createdAt }
            val retainedTaskIds = retainedTasks.mapTo(mutableSetOf()) { it.id }
            val evictedTasks = tasks.filterNot { it.id in retainedTaskIds }
            record.commandTasks = retainedTasks
            if (previousStatus != task.status) {
                record.appendTrace(
                    conversationId = task.conversationId,
                    kind = ConversationRuntimeTraceEntry.Kind.COMMAND_TASK,
                    status = task.status.toTraceStatus(),
                    message = "${task.id.value}: ${task.status}",
                )
            }
            record.bumpRevision()
            CommandTaskUpsertResult(evictedTasks)
        }

    override suspend fun findCommandTasks(): List<CommandTask> =
        readAllRecords().flatMap { it.commandTasks }

    override suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask? = readRecord(conversationId)?.commandTasks?.firstOrNull { it.id == taskId }

    override suspend fun requestCommandTaskCancellation(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        requestedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.requestCommandTaskCancellation(conversationId, taskId, requestedAt)
        }

    override suspend fun requestCommandTaskCancellations(
        conversationId: Conversation.Id,
        requestedAt: Instant,
    ): Int =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.commandTasks
                .filter { it.status == CommandTask.Status.WORKING }
                .count { task ->
                    record.requestCommandTaskCancellation(conversationId, task.id, requestedAt)
                }
        }

    override suspend fun requestPause(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            when (state.controlState) {
                ConversationExecutionState.ControlState.RUNNING -> {
                    record.state = state.copy(
                        controlState = ConversationExecutionState.ControlState.PAUSE_REQUESTED,
                        updatedAt = Clock.System.now(),
                    )
                    record.appendControlTrace(conversationId, ConversationExecutionState.ControlState.PAUSE_REQUESTED)
                    record.bumpRevision()
                    true
                }
                ConversationExecutionState.ControlState.PAUSE_REQUESTED,
                ConversationExecutionState.ControlState.PAUSED -> true
                ConversationExecutionState.ControlState.STOPPING,
                ConversationExecutionState.ControlState.INTERRUPTING -> false
            }
        }

    override suspend fun markPaused(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.controlState != ConversationExecutionState.ControlState.PAUSE_REQUESTED) {
                return@mutateRecord false
            }
            record.state = state.copy(
                controlState = ConversationExecutionState.ControlState.PAUSED,
                updatedAt = Clock.System.now(),
            )
            record.appendControlTrace(conversationId, ConversationExecutionState.ControlState.PAUSED)
            record.bumpRevision()
            true
        }

    override suspend fun requestResume(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.controlState != ConversationExecutionState.ControlState.PAUSED &&
                state.controlState != ConversationExecutionState.ControlState.PAUSE_REQUESTED
            ) {
                return@mutateRecord false
            }
            record.state = state.copy(
                controlState = ConversationExecutionState.ControlState.RUNNING,
                updatedAt = Clock.System.now(),
            )
            record.appendControlTrace(conversationId, ConversationExecutionState.ControlState.RUNNING)
            record.scheduleNextRunnableTaskIfReady(conversationId)
            record.bumpRevision()
            true
        }

    override suspend fun requestStop(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.requestTerminalStatus(conversationId, ConversationExecutionState.ControlState.STOPPING)
        }

    override suspend fun requestInterrupt(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.requestTerminalStatus(conversationId, ConversationExecutionState.ControlState.INTERRUPTING)
        }

    override suspend fun abort(conversationId: Conversation.Id) {
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.clearRuntime()
            record.bumpRevision()
            Unit
        }
    }

    override suspend fun find(conversationId: Conversation.Id): ConversationExecutionState? =
        readRecord(conversationId)?.state

    override suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val removed = record.pendingTasks.any { it.userMessageIdOrNull() == messageId }
            if (!removed) {
                return@mutateRecord false
            }
            record.pendingTasks = record.pendingTasks.filterNot { it.userMessageIdOrNull() == messageId }
            record.removeScheduledWorkItems(conversationId, ConversationRuntimeTask.Id(messageId.value))
            record.appendTrace(
                conversationId = conversationId,
                taskId = ConversationRuntimeTask.Id(messageId.value),
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CANCELLED,
                status = ConversationRuntimeTraceEntry.Status.CANCELLED,
                message = "Queued runtime task cancelled",
            )
            record.bumpRevision()
            true
        }

    override suspend fun takeActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord emptyList()
            if (state.activeTaskId != taskId || state.activeWorker != worker) {
                return@mutateRecord emptyList()
            }
            val ready = record.pendingTasks.filter { it.placement == placement }
            if (ready.isNotEmpty()) {
                val readyIds = ready.mapTo(mutableSetOf()) { it.id }
                record.pendingTasks = record.pendingTasks.filterNot { it.id in readyIds }
                record.bumpRevision()
            }
            ready
        }

    override suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeTask> =
        readRecord(conversationId)?.pendingTasks.orEmpty()

    override suspend fun snapshot(conversationId: Conversation.Id): ConversationRuntimeSnapshot {
        val record = readRecord(conversationId) ?: return ConversationRuntimeSnapshot(
            revision = 0,
            conversationId = conversationId,
            state = null,
            pendingTasks = emptyList(),
        )
        return record.snapshot()
    }

    override suspend fun recordEvent(event: ConversationRuntimeEvent): ConversationRuntimeEventLogEntry =
        mutateRecord(event.conversationId, createIfMissing = true) { record ->
            val sequence = record.eventSequence + 1
            record.eventSequence = sequence
            val entry = ConversationRuntimeEventLogEntry(
                sequence = sequence,
                conversationId = event.conversationId,
                event = event,
                createdAt = Clock.System.now(),
            )
            record.eventLog = (record.eventLog + entry).takeLast(EVENT_LOG_RETENTION_LIMIT)
            record.appendTrace(
                conversationId = event.conversationId,
                taskId = (event as? ConversationRuntimeEvent.MessageEmitted)?.taskId,
                kind = ConversationRuntimeTraceEntry.Kind.EVENT_PUBLISHED,
                status = ConversationRuntimeTraceEntry.Status.COMPLETED,
                message = "${event::class.simpleName ?: "RuntimeEvent"}#$sequence",
            )
            record.bumpRevision()
            entry
        }

    override suspend fun listEventLogEntries(
        conversationId: Conversation.Id,
        afterSequence: Long?,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry> {
        val entries = readRecord(conversationId)?.eventLog.orEmpty()
        return if (afterSequence == null) {
            entries.takeLast(limit)
        } else {
            entries.filter { it.sequence > afterSequence }.take(limit)
        }
    }

    override suspend fun claimUnpublishedEventLogEntries(
        leaseOwnerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry> {
        require(limit > 0) { "Conversation runtime event outbox claim limit must be positive" }
        require(leaseUntil > now) { "Conversation runtime event outbox lease must end in the future" }
        val candidateConversationIds = readAllRecords()
            .mapNotNull { record ->
                record.eventLog
                    .asSequence()
                    .filter { it.isAvailableForPublish(now) }
                    .minWithOrNull(compareBy<ConversationRuntimeEventLogEntry> { it.createdAt }.thenBy { it.sequence })
                    ?.let { entry -> record.conversationId to entry }
            }
            .sortedWith(
                compareBy<Pair<Conversation.Id, ConversationRuntimeEventLogEntry>> { it.second.createdAt }
                    .thenBy { it.second.sequence }
            )
            .map { it.first }
        val claimed = mutableListOf<ConversationRuntimeEventLogEntry>()

        for (conversationId in candidateConversationIds) {
            if (claimed.size >= limit) break
            val claimedForConversation = tryMutateExistingRecord(conversationId) { record ->
                val selected = record.eventLog
                    .filter { it.isAvailableForPublish(now) }
                    .sortedWith(compareBy<ConversationRuntimeEventLogEntry> { it.createdAt }.thenBy { it.sequence })
                    .take(limit - claimed.size)
                    .map {
                        it.copy(
                            publishLeaseOwnerId = leaseOwnerId,
                            publishLeaseUntil = leaseUntil,
                        )
                    }
                if (selected.isNotEmpty()) {
                    val selectedBySequence = selected.associateBy { it.sequence }
                    record.eventLog = record.eventLog.map { selectedBySequence[it.sequence] ?: it }
                }
                selected
            }.orEmpty()
            claimed += claimedForConversation
        }
        return claimed.sortedWith(
            compareBy<ConversationRuntimeEventLogEntry> { it.createdAt }.thenBy { it.sequence }
        )
    }

    override suspend fun markEventLogEntryPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        leaseOwnerId: String,
        publishedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val entry = record.eventLog.firstOrNull { it.sequence == sequence } ?: return@mutateRecord false
            if (entry.publishedAt != null) {
                return@mutateRecord true
            }
            if (entry.publishLeaseOwnerId != null && entry.publishLeaseOwnerId != leaseOwnerId) {
                return@mutateRecord false
            }
            record.eventLog = record.eventLog.replaceEventLogEntry(
                entry.copy(
                    publishedAt = publishedAt,
                    publishLeaseOwnerId = null,
                    publishLeaseUntil = null,
                )
            )
            true
        }

    override suspend fun claimUnpublishedWorkItems(
        leaseOwnerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeWorkOutboxEntry> {
        require(limit > 0) { "Conversation runtime work outbox claim limit must be positive" }
        require(leaseUntil > now) { "Conversation runtime work outbox lease must end in the future" }
        val candidateConversationIds = readAllRecords()
            .mapNotNull { record ->
                record.workOutbox
                    .asSequence()
                    .filter { record.canClaimForPublish(it, now) }
                    .minWithOrNull(compareBy<ConversationRuntimeWorkOutboxEntry> { it.createdAt }.thenBy { it.sequence })
                    ?.let { entry -> record.conversationId to entry }
            }
            .sortedWith(
                compareBy<Pair<Conversation.Id, ConversationRuntimeWorkOutboxEntry>> { it.second.createdAt }
                    .thenBy { it.second.sequence }
            )
            .map { it.first }
        val claimed = mutableListOf<ConversationRuntimeWorkOutboxEntry>()

        for (conversationId in candidateConversationIds) {
            if (claimed.size >= limit) break
            val claimedForConversation = tryMutateExistingRecord(conversationId) { record ->
                val selected = record.workOutbox
                    .filter { record.canClaimForPublish(it, now) }
                    .sortedWith(compareBy<ConversationRuntimeWorkOutboxEntry> { it.createdAt }.thenBy { it.sequence })
                    .take(limit - claimed.size)
                    .map {
                        it.copy(
                            publishLeaseOwnerId = leaseOwnerId,
                            publishLeaseUntil = leaseUntil,
                        )
                    }
                if (selected.isNotEmpty()) {
                    val selectedBySequence = selected.associateBy { it.sequence }
                    record.workOutbox = record.workOutbox.map { selectedBySequence[it.sequence] ?: it }
                }
                selected
            }.orEmpty()
            claimed += claimedForConversation
        }
        return claimed.sortedWith(
            compareBy<ConversationRuntimeWorkOutboxEntry> { it.createdAt }.thenBy { it.sequence }
        )
    }

    override suspend fun markWorkItemPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        leaseOwnerId: String,
        publishedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val entry = record.workOutbox.firstOrNull { it.sequence == sequence } ?: return@mutateRecord false
            if (entry.publishedAt != null) {
                return@mutateRecord true
            }
            if (entry.publishLeaseOwnerId != null && entry.publishLeaseOwnerId != leaseOwnerId) {
                return@mutateRecord false
            }
            record.workOutbox = record.workOutbox.replaceWorkOutboxEntry(
                entry.copy(
                    publishedAt = publishedAt,
                    publishLeaseOwnerId = null,
                    publishLeaseUntil = null,
                )
            )
            true
        }

    override suspend fun releasePublishedWorkItem(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val entry = record.workOutbox.firstOrNull { it.item.taskId == taskId } ?: return@mutateRecord false
            if (entry.publishedAt == null && entry.publishLeaseOwnerId == null && entry.publishLeaseUntil == null) {
                return@mutateRecord true
            }
            record.workOutbox = record.workOutbox.replaceWorkOutboxEntry(
                entry.copy(
                    publishedAt = null,
                    publishLeaseOwnerId = null,
                    publishLeaseUntil = null,
                )
            )
            true
        }

    private suspend fun readRecord(conversationId: Conversation.Id): RuntimeRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT record_json FROM conversation_runtime_records WHERE conversation_id = ?").use { statement ->
                    statement.setString(1, conversationId.value)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.runtimeRecord() else null
                    }
                }
            }
        }

    private suspend fun readAllRecords(): List<RuntimeRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT record_json FROM conversation_runtime_records ORDER BY conversation_id"
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(result.runtimeRecord())
                            }
                        }
                    }
                }
            }
        }

    private suspend fun <T> mutateRecord(
        conversationId: Conversation.Id,
        createIfMissing: Boolean,
        block: (RuntimeRecord) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val record = connection.lockRecord(conversationId)
                        ?: if (createIfMissing) connection.insertAndLockRecord(conversationId) else RuntimeRecord(conversationId)
                    val result = block(record)
                    if (createIfMissing || connection.recordExists(conversationId)) {
                        connection.upsertRecord(record)
                    }
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }

    private suspend fun <T : Any> tryMutateExistingRecord(
        conversationId: Conversation.Id,
        block: (RuntimeRecord) -> T,
    ): T? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val record = connection.tryLockRecord(conversationId)
                    if (record == null) {
                        connection.rollback()
                        return@use null
                    }
                    val result = block(record)
                    connection.upsertRecord(record)
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }

    private fun Connection.lockRecord(conversationId: Conversation.Id): RuntimeRecord? =
        prepareStatement("SELECT record_json FROM conversation_runtime_records WHERE conversation_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, conversationId.value)
            statement.executeQuery().use { result ->
                if (result.next()) result.runtimeRecord() else null
            }
        }

    private fun Connection.tryLockRecord(conversationId: Conversation.Id): RuntimeRecord? =
        prepareStatement(
            "SELECT record_json FROM conversation_runtime_records WHERE conversation_id = ? FOR UPDATE SKIP LOCKED"
        ).use { statement ->
            statement.setString(1, conversationId.value)
            statement.executeQuery().use { result ->
                if (result.next()) result.runtimeRecord() else null
            }
        }

    private fun Connection.insertAndLockRecord(conversationId: Conversation.Id): RuntimeRecord {
        val record = RuntimeRecord(conversationId)
        prepareStatement(
            """
            INSERT INTO conversation_runtime_records(conversation_id, record_json, updated_at)
            VALUES (?, CAST(? AS jsonb), ?)
            ON CONFLICT (conversation_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, conversationId.value)
            statement.setString(2, json.encodeToString(record))
            statement.setTimestamp(3, Clock.System.now().toTimestamp())
            statement.executeUpdate()
        }
        return lockRecord(conversationId) ?: record
    }

    private fun Connection.recordExists(conversationId: Conversation.Id): Boolean =
        prepareStatement("SELECT 1 FROM conversation_runtime_records WHERE conversation_id = ?").use { statement ->
            statement.setString(1, conversationId.value)
            statement.executeQuery().use { it.next() }
        }

    private fun Connection.upsertRecord(record: RuntimeRecord) {
        prepareStatement(
            """
            INSERT INTO conversation_runtime_records(conversation_id, record_json, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT (conversation_id) DO UPDATE
            SET record_json = EXCLUDED.record_json,
                updated_at = EXCLUDED.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, record.conversationId.value)
            statement.setObject(2, jsonb(record))
            statement.setTimestamp(3, Clock.System.now().toTimestamp())
            statement.executeUpdate()
        }
    }

    private fun ResultSet.runtimeRecord(): RuntimeRecord =
        json.decodeFromString(getString("record_json"))

    private fun jsonb(record: RuntimeRecord): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = json.encodeToString(record)
        }

    private fun ConversationRuntimeEventLogEntry.isAvailableForPublish(now: Instant): Boolean =
        publishedAt == null && publishLeaseUntil?.let { it < now } != false

    private fun RuntimeRecord.canClaimForPublish(
        entry: ConversationRuntimeWorkOutboxEntry,
        now: Instant,
    ): Boolean =
        entry.publishedAt == null &&
            canPublishWorkItem(entry.item) &&
            entry.publishLeaseUntil?.let { it < now } != false

    @Serializable
    private data class RuntimeRecord(
        val conversationId: Conversation.Id,
        var revision: Long = 0,
        var state: ConversationExecutionState? = null,
        var activeTask: ConversationRuntimeTask? = null,
        var pendingTasks: List<ConversationRuntimeTask> = emptyList(),
        var toolExecutions: List<ConversationRuntimeToolExecution> = emptyList(),
        var commandTasks: List<CommandTask> = emptyList(),
        var incidents: List<ConversationRuntimeTaskIncident> = emptyList(),
        var trace: List<ConversationRuntimeTraceEntry> = emptyList(),
        var eventLog: List<ConversationRuntimeEventLogEntry> = emptyList(),
        var workOutbox: List<ConversationRuntimeWorkOutboxEntry> = emptyList(),
        var completedIdempotencyKeys: Set<String> = emptySet(),
        var traceSequence: Long = 0,
        var eventSequence: Long = 0,
        var workSequence: Long = 0,
    ) {
        fun snapshot(): ConversationRuntimeSnapshot =
            ConversationRuntimeSnapshot(
                revision = revision,
                conversationId = conversationId,
                state = state,
                activeTask = activeTask,
                pendingTasks = pendingTasks,
                toolExecutions = toolExecutions,
                commandTasks = commandTasks,
                incidents = incidents,
                trace = trace.takeLast(TRACE_SNAPSHOT_LIMIT),
                lastEventSequence = eventSequence,
            )

        fun bumpRevision() {
            revision += 1
        }

        fun scheduleNextRunnableTaskIfReady(conversationId: Conversation.Id) {
            val currentState = state
            if (currentState?.activeTaskId != null) {
                return
            }
            if (currentState != null && currentState.controlState != ConversationExecutionState.ControlState.RUNNING) {
                return
            }
            val task = pendingTasks.firstOrNull { it.placement == QueuedMessagePlacement.END_OF_TURN } ?: return
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

        fun removeScheduledWorkItems(
            conversationId: Conversation.Id,
            taskId: ConversationRuntimeTask.Id,
        ) {
            workOutbox = workOutbox.filterNot {
                it.item.conversationId == conversationId &&
                    it.item.reason == ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED &&
                    it.item.taskId == taskId
            }
        }

        fun canPublishWorkItem(item: ConversationRuntimeWorkItem): Boolean =
            state == null || (state?.controlState == ConversationExecutionState.ControlState.RUNNING && state?.activeTaskId == null)

        fun appendWorkItemIfMissing(item: ConversationRuntimeWorkItem) {
            if (workOutbox.any { it.item.reason == item.reason && it.item.taskId == item.taskId }) {
                return
            }
            workSequence += 1
            workOutbox = workOutbox + ConversationRuntimeWorkOutboxEntry(
                sequence = workSequence,
                item = item,
                createdAt = item.createdAt,
            )
        }

        fun requestCommandTaskCancellation(
            conversationId: Conversation.Id,
            taskId: CommandTask.Id,
            requestedAt: Instant,
        ): Boolean {
            val index = commandTasks.indexOfFirst { it.id == taskId }
            if (index < 0) {
                return false
            }
            val task = commandTasks[index]
            if (task.status != CommandTask.Status.WORKING) {
                return false
            }
            if (task.cancellationRequestedAt != null) {
                return true
            }
            commandTasks = commandTasks.toMutableList().apply {
                this[index] = task.copy(
                    cancellationRequestedAt = requestedAt,
                    statusMessage = "Cancellation requested",
                    updatedAt = requestedAt,
                )
            }
            appendTrace(
                conversationId = conversationId,
                kind = ConversationRuntimeTraceEntry.Kind.COMMAND_TASK,
                status = ConversationRuntimeTraceEntry.Status.UPDATED,
                message = "${task.id.value}: cancellation requested",
            )
            bumpRevision()
            return true
        }

        fun requestTerminalStatus(
            conversationId: Conversation.Id,
            controlState: ConversationExecutionState.ControlState,
        ): Boolean {
            val removedTasks = pendingTasks.size
            pendingTasks = emptyList()
            workOutbox = emptyList()
            val currentState = state
            if (currentState == null && removedTasks == 0) {
                return false
            }
            if (currentState?.activeTaskId != null) {
                state = currentState.copy(
                    controlState = controlState,
                    updatedAt = Clock.System.now(),
                )
            }
            appendControlTrace(conversationId, controlState)
            if (currentState?.activeTaskId == null) {
                clearRuntime()
            }
            bumpRevision()
            return true
        }

        fun clearRuntime() {
            state = null
            activeTask = null
            pendingTasks = emptyList()
            toolExecutions = emptyList()
            workOutbox = emptyList()
        }

        fun appendControlTrace(
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

        fun appendTrace(
            conversationId: Conversation.Id,
            taskId: ConversationRuntimeTask.Id? = null,
            worker: ConversationRuntimeWorkerIdentity? = null,
            kind: ConversationRuntimeTraceEntry.Kind,
            status: ConversationRuntimeTraceEntry.Status,
            message: String? = null,
        ): ConversationRuntimeTraceEntry {
            traceSequence += 1
            val entry = ConversationRuntimeTraceEntry(
                sequence = traceSequence,
                conversationId = conversationId,
                taskId = taskId,
                worker = worker,
                kind = kind,
                status = status,
                message = message,
                createdAt = Clock.System.now(),
            )
            trace = (trace + entry).takeLast(TRACE_RETENTION_LIMIT)
            return entry
        }

        fun recordActiveTaskIncident(
            conversationId: Conversation.Id,
            kind: ConversationRuntimeTaskIncident.Kind,
            message: String,
            errorType: String?,
        ): ConversationRuntimeTaskIncident? {
            val currentState = state ?: return null
            val task = activeTask ?: return null
            check(
                (kind == ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN) ==
                    (currentState.activeTaskStartedAt != null)
            ) {
                "Runtime incident kind does not match execution start boundary: task=${task.id.value} kind=$kind"
            }
            activeTask = null
            completedIdempotencyKeys = completedIdempotencyKeys + task.idempotencyKey
            pendingTasks = pendingTasks.filterNot { it.isInternalRuntimeStep() }
            workOutbox = emptyList()
            toolExecutions = emptyList()
            val incident = ConversationRuntimeTaskIncident(
                task = task,
                kind = kind,
                message = message,
                errorType = errorType,
                worker = currentState.activeWorker,
                executionStartedAt = currentState.activeTaskStartedAt,
                occurredAt = Clock.System.now(),
            )
            incidents = incidents + incident
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                worker = currentState.activeWorker,
                kind = when (kind) {
                    ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED ->
                        ConversationRuntimeTraceEntry.Kind.TASK_FAILED
                    ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN ->
                        ConversationRuntimeTraceEntry.Kind.TASK_IN_DOUBT
                },
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "$kind: " +
                    if (errorType.isNullOrBlank()) message else "$errorType: $message",
            )

            if (currentState.controlState == ConversationExecutionState.ControlState.STOPPING ||
                currentState.controlState == ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                clearRuntime()
                bumpRevision()
                return incident
            }

            enqueueIncidentTaskIfNeeded(incident)
            if (pendingTasks.isEmpty()) {
                state = null
            } else {
                state = currentState.copy(
                    controlState = when (currentState.controlState) {
                        ConversationExecutionState.ControlState.PAUSE_REQUESTED ->
                            ConversationExecutionState.ControlState.PAUSED
                        else -> currentState.controlState
                    },
                    activeTaskId = null,
                    activeWorker = null,
                    activeTaskStartedAt = null,
                    updatedAt = Clock.System.now(),
                )
                scheduleNextRunnableTaskIfReady(conversationId)
            }
            bumpRevision()
            return incident
        }

        fun enqueueIncidentTaskIfNeeded(incident: ConversationRuntimeTaskIncident) {
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
            if (pendingTasks.none { it.id == task.id }) {
                pendingTasks = listOf(task) + pendingTasks
                appendTrace(
                    conversationId = task.conversationId,
                    taskId = task.id,
                    kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                    status = ConversationRuntimeTraceEntry.Status.STARTED,
                    message = "Execution incident handling task submitted",
                )
            }
        }
    }

    private fun List<ConversationRuntimeEventLogEntry>.replaceEventLogEntry(
        replacement: ConversationRuntimeEventLogEntry,
    ): List<ConversationRuntimeEventLogEntry> =
        map { entry -> if (entry.sequence == replacement.sequence) replacement else entry }

    private fun List<ConversationRuntimeWorkOutboxEntry>.replaceWorkOutboxEntry(
        replacement: ConversationRuntimeWorkOutboxEntry,
    ): List<ConversationRuntimeWorkOutboxEntry> =
        map { entry -> if (entry.sequence == replacement.sequence) replacement else entry }

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))

    private fun CommandTask.Status.toTraceStatus(): ConversationRuntimeTraceEntry.Status = when (this) {
        CommandTask.Status.WORKING -> ConversationRuntimeTraceEntry.Status.STARTED
        CommandTask.Status.COMPLETED -> ConversationRuntimeTraceEntry.Status.COMPLETED
        CommandTask.Status.FAILED -> ConversationRuntimeTraceEntry.Status.FAILED
        CommandTask.Status.CANCELLED -> ConversationRuntimeTraceEntry.Status.CANCELLED
    }

    private companion object {
        const val COMMAND_TASK_TERMINAL_RETENTION_LIMIT = 100
        const val TRACE_SNAPSHOT_LIMIT = 200
        const val TRACE_RETENTION_LIMIT = 2_000
        const val EVENT_LOG_RETENTION_LIMIT = 10_000
    }
}
