package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskFailure
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkOutboxEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
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
        workerId: String,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerAffinities: Set<ConversationRuntimeWorkerAffinity>,
    ): ConversationRuntimeTask? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state
            if (state != null && state.activeTaskId == taskId) {
                if (state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
                ) {
                    return@mutateRecord null
                }
                val activeTask = record.activeTask ?: return@mutateRecord null
                if (!activeTask.requirements.isSatisfiedBy(workerCapabilities, workerAffinities)) {
                    return@mutateRecord null
                }
                record.state = state.copy(
                    controlState = ConversationExecutionState.ControlState.RUNNING,
                    activeWorkerId = workerId,
                    updatedAt = Clock.System.now(),
                )
                record.appendTrace(
                    conversationId = conversationId,
                    taskId = taskId,
                    workerId = workerId,
                    kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                    status = ConversationRuntimeTraceEntry.Status.STARTED,
                    message = "Runtime task redelivered to $workerId",
                )
                record.bumpRevision()
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
            if (!task.requirements.isSatisfiedBy(workerCapabilities, workerAffinities)) {
                return@mutateRecord null
            }

            record.pendingTasks = record.pendingTasks.toMutableList().apply { removeAt(taskIndex) }
            record.removeScheduledWorkItems(conversationId, task.id)
            record.activeTask = task
            val now = Clock.System.now()
            record.state = (state ?: ConversationExecutionState(
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
            record.appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                workerId = workerId,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task claimed by $workerId",
            )
            record.bumpRevision()
            task
        }

    override suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@mutateRecord false
            }
            val completedControlState = when (state.controlState) {
                ConversationExecutionState.ControlState.PAUSE_REQUESTED -> ConversationExecutionState.ControlState.PAUSED
                else -> state.controlState
            }
            record.state = state.copy(
                controlState = completedControlState,
                activeTaskId = null,
                activeWorkerId = null,
                updatedAt = Clock.System.now(),
            )
            record.activeTask?.let { completedTask ->
                record.completedIdempotencyKeys = record.completedIdempotencyKeys + completedTask.idempotencyKey
            }
            record.activeTask = null
            record.appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                workerId = workerId,
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

    override suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean =
        readRecord(conversationId)?.state?.let { state ->
            state.activeTaskId == taskId && state.activeWorkerId == workerId
        } ?: false

    override suspend fun failActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        message: String,
        type: String?,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@mutateRecord false
            }
            record.recordActiveTaskFailure(
                conversationId = conversationId,
                reason = ConversationRuntimeTaskFailure.Reason.EXECUTION_FAILED,
                message = failureMessage(message, type),
            ) != null
        }

    override suspend fun failPendingTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        message: String,
        type: String?,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val task = record.pendingTasks.firstOrNull { it.id == taskId } ?: return@mutateRecord false
            record.pendingTasks = record.pendingTasks.filterNot { it.id == taskId }
            record.removeScheduledWorkItems(conversationId, taskId)
            val failureMessage = failureMessage(message, type)
            record.failedTasks = record.failedTasks + ConversationRuntimeTaskFailure(
                task = task,
                reason = ConversationRuntimeTaskFailure.Reason.DELIVERY_FAILED,
                message = failureMessage,
                workerId = workerId,
                failedAt = Clock.System.now(),
            )
            record.appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                workerId = workerId,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_FAILED,
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "${ConversationRuntimeTaskFailure.Reason.DELIVERY_FAILED}: $failureMessage",
            )
            if (record.state != null && record.state?.activeTaskId == null && record.pendingTasks.isEmpty()) {
                record.state = null
            }
            record.bumpRevision()
            true
        }

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
            if (state.activeTaskId != execution.runtimeTaskId || state.activeWorkerId != execution.workerId) {
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
                workerId = execution.workerId,
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
        workerId: String,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord false
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
                return@mutateRecord false
            }
            if (record.toolExecutions.isNotEmpty()) {
                record.toolExecutions = emptyList()
                record.bumpRevision()
            }
            true
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

    override suspend fun fail(conversationId: Conversation.Id) {
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.clearRuntime()
            record.bumpRevision()
            Unit
        }
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
        workerId: String,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.state ?: return@mutateRecord emptyList()
            if (state.activeTaskId != taskId || state.activeWorkerId != workerId) {
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
        workerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry> =
        mutateAllRecords { records ->
            val selected = records
                .flatMap { record ->
                    record.eventLog.map { entry -> record to entry }
                }
                .filter { (_, entry) ->
                    val publishLeaseUntil = entry.publishLeaseUntil
                    entry.publishedAt == null && (publishLeaseUntil == null || publishLeaseUntil < now)
                }
                .sortedWith(compareBy<Pair<RuntimeRecord, ConversationRuntimeEventLogEntry>> { it.second.createdAt }.thenBy { it.second.sequence })
                .take(limit)

            selected.forEach { (record, entry) ->
                record.eventLog = record.eventLog.replaceEventLogEntry(
                    entry.copy(
                        publishWorkerId = workerId,
                        publishLeaseUntil = leaseUntil,
                    )
                )
            }

            selected.map { (_, entry) ->
                entry.copy(
                    publishWorkerId = workerId,
                    publishLeaseUntil = leaseUntil,
                )
            }
        }

    override suspend fun markEventLogEntryPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        workerId: String,
        publishedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val entry = record.eventLog.firstOrNull { it.sequence == sequence } ?: return@mutateRecord false
            if (entry.publishedAt != null) {
                return@mutateRecord true
            }
            if (entry.publishWorkerId != null && entry.publishWorkerId != workerId) {
                return@mutateRecord false
            }
            record.eventLog = record.eventLog.replaceEventLogEntry(
                entry.copy(
                    publishedAt = publishedAt,
                    publishWorkerId = null,
                    publishLeaseUntil = null,
                )
            )
            true
        }

    override suspend fun claimUnpublishedWorkItems(
        workerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeWorkOutboxEntry> =
        mutateAllRecords { records ->
            val selected = records
                .flatMap { record ->
                    record.workOutbox.map { entry -> record to entry }
                }
                .filter { (record, entry) ->
                    val publishLeaseUntil = entry.publishLeaseUntil
                    entry.publishedAt == null &&
                        record.canPublishWorkItem(entry.item) &&
                        (publishLeaseUntil == null || publishLeaseUntil < now)
                }
                .sortedWith(compareBy<Pair<RuntimeRecord, ConversationRuntimeWorkOutboxEntry>> { it.second.createdAt }.thenBy { it.second.sequence })
                .take(limit)

            selected.forEach { (record, entry) ->
                record.workOutbox = record.workOutbox.replaceWorkOutboxEntry(
                    entry.copy(
                        publishWorkerId = workerId,
                        publishLeaseUntil = leaseUntil,
                    )
                )
            }

            selected.map { (_, entry) ->
                entry.copy(
                    publishWorkerId = workerId,
                    publishLeaseUntil = leaseUntil,
                )
            }
        }

    override suspend fun markWorkItemPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        workerId: String,
        publishedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val entry = record.workOutbox.firstOrNull { it.sequence == sequence } ?: return@mutateRecord false
            if (entry.publishedAt != null) {
                return@mutateRecord true
            }
            if (entry.publishWorkerId != null && entry.publishWorkerId != workerId) {
                return@mutateRecord false
            }
            record.workOutbox = record.workOutbox.replaceWorkOutboxEntry(
                entry.copy(
                    publishedAt = publishedAt,
                    publishWorkerId = null,
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
            if (entry.publishedAt == null && entry.publishWorkerId == null && entry.publishLeaseUntil == null) {
                return@mutateRecord true
            }
            record.workOutbox = record.workOutbox.replaceWorkOutboxEntry(
                entry.copy(
                    publishedAt = null,
                    publishWorkerId = null,
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

    private suspend fun <T> mutateAllRecords(block: (List<RuntimeRecord>) -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val records = connection.lockAllRecords()
                    val result = block(records)
                    records.forEach { record -> connection.upsertRecord(record) }
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

    private fun Connection.lockAllRecords(): List<RuntimeRecord> =
        prepareStatement("SELECT record_json FROM conversation_runtime_records ORDER BY conversation_id FOR UPDATE").use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.runtimeRecord())
                    }
                }
            }
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

    private fun failureMessage(message: String, type: String?): String =
        if (type.isNullOrBlank()) message else "$type: $message"

    @Serializable
    private data class RuntimeRecord(
        val conversationId: Conversation.Id,
        var revision: Long = 0,
        var state: ConversationExecutionState? = null,
        var activeTask: ConversationRuntimeTask? = null,
        var pendingTasks: List<ConversationRuntimeTask> = emptyList(),
        var toolExecutions: List<ConversationRuntimeToolExecution> = emptyList(),
        var failedTasks: List<ConversationRuntimeTaskFailure> = emptyList(),
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
                failedTasks = failedTasks,
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
            workerId: String? = null,
            kind: ConversationRuntimeTraceEntry.Kind,
            status: ConversationRuntimeTraceEntry.Status,
            message: String? = null,
        ): ConversationRuntimeTraceEntry {
            traceSequence += 1
            val entry = ConversationRuntimeTraceEntry(
                sequence = traceSequence,
                conversationId = conversationId,
                taskId = taskId,
                workerId = workerId,
                kind = kind,
                status = status,
                message = message,
                createdAt = Clock.System.now(),
            )
            trace = (trace + entry).takeLast(TRACE_RETENTION_LIMIT)
            return entry
        }

        fun recordActiveTaskFailure(
            conversationId: Conversation.Id,
            reason: ConversationRuntimeTaskFailure.Reason,
            message: String,
        ): ConversationRuntimeTaskFailure? {
            val currentState = state ?: return null
            val task = activeTask ?: return null
            activeTask = null
            state = null
            pendingTasks = emptyList()
            workOutbox = emptyList()
            toolExecutions = emptyList()
            val failure = ConversationRuntimeTaskFailure(
                task = task,
                reason = reason,
                message = message,
                workerId = currentState.activeWorkerId,
                failedAt = Clock.System.now(),
            )
            failedTasks = failedTasks + failure
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                workerId = currentState.activeWorkerId,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_FAILED,
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "$reason: $message",
            )
            bumpRevision()
            return failure
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

    private companion object {
        const val TRACE_SNAPSHOT_LIMIT = 200
        const val TRACE_RETENTION_LIMIT = 2_000
        const val EVENT_LOG_RETENTION_LIMIT = 10_000
    }
}
