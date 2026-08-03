package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorSyncResult
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeActiveTaskAssignment
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeMemoryOperation
import com.gromozeka.domain.service.ConversationRuntimeSchedulingState
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSignal
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.zaxxer.hikari.HikariDataSource
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import org.postgresql.PGConnection
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

@Service
@DependsOn("postgresFlyway")
class PostgresConversationRuntimeCoordinator(
    private val dataSource: DataSource,
    private val json: Json,
) : ConversationRuntimeCoordinator {
    private val log = KLoggers.logger(this)

    override val schedulingSignals: Flow<ConversationRuntimeSchedulingSignal> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                emitAll(postgresSchedulingSignals())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn(error) { "Conversation runtime scheduling listener disconnected; reconnecting" }
                delay(SCHEDULING_LISTENER_RECONNECT_DELAY_MILLIS)
            }
        }
    }

    override suspend fun submit(task: ConversationRuntimeTask): Boolean =
        mutateRecord(task.conversationId, createIfMissing = true) { record ->
            val transition = record.scheduling.submit(task, Clock.System.now())
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task submitted: placement=${task.placement}",
            )
            record.bumpRevision()
            true
        }

    override suspend fun updatePendingUserTurn(task: ConversationRuntimeTask): Boolean =
        mutateRecord(task.conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.updatePendingUserTurn(task)
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.UPDATED,
                message = "Queued user turn updated: placement=${task.placement}",
            )
            record.bumpRevision()
            true
        }

    override suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        executorCapabilities: Set<ConversationRuntimeCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): ConversationRuntimeTask? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.claim(
                taskId = taskId,
                executor = executor,
                executorCapabilities = executorCapabilities,
                workerWorkspaceMountIds = workerWorkspaceMountIds,
                now = Clock.System.now(),
            )
            val task = transition.result ?: return@mutateRecord null
            if (!transition.changed) return@mutateRecord task
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                executor = executor,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task claimed by $executor",
            )
            record.bumpRevision()
            task
        }

    override suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        outcome: ConversationRuntimeTaskOutcome,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.completeActiveTask(
                taskId = taskId,
                executor = executor,
                outcome = outcome,
                now = Clock.System.now(),
            )
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                executor = executor,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_COMPLETED,
                status = ConversationRuntimeTraceEntry.Status.COMPLETED,
                message = "Runtime task completed",
            )
            record.bumpRevision()
            true
        }

    override suspend fun markActiveTaskStarted(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        startedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.markActiveTaskStarted(taskId, executor, startedAt)
            if (!transition.result) return@mutateRecord false
            if (!transition.changed) return@mutateRecord true
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                executor = executor,
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
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean =
        readRecord(conversationId)?.scheduling?.confirmActiveTaskOwner(taskId, executor) ?: false

    override suspend fun markActiveTaskInDoubt(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.recordActiveTaskIncident(
                taskId = taskId,
                executor = executor,
                kind = ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
                message = message,
                errorType = errorType,
                occurredAt = Clock.System.now(),
            )
            val incident = transition.result ?: return@mutateRecord null
            record.scheduling = transition.state
            record.recordIncidentTrace(incident)
            record.toolExecutions = emptyList()
            record.bumpRevision()
            incident
        }

    override suspend fun recordClaimedTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.recordActiveTaskIncident(
                taskId = taskId,
                executor = executor,
                kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                message = message,
                errorType = errorType,
                occurredAt = Clock.System.now(),
            )
            val incident = transition.result ?: return@mutateRecord null
            record.scheduling = transition.state
            record.recordIncidentTrace(incident)
            record.toolExecutions = emptyList()
            record.bumpRevision()
            incident
        }

    override suspend fun recordPendingTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.recordPendingTaskDeliveryFailure(
                taskId = taskId,
                executor = executor,
                message = message,
                errorType = errorType,
                occurredAt = Clock.System.now(),
            )
            val incident = transition.result ?: return@mutateRecord null
            record.scheduling = transition.state
            record.recordIncidentTrace(incident)
            record.bumpRevision()
            incident
        }

    override suspend fun listActiveTaskAssignments(): List<ConversationRuntimeActiveTaskAssignment> =
        readAllRecords().mapNotNull { record ->
            val task = record.scheduling.activeTask ?: return@mapNotNull null
            val state = record.scheduling.executionState ?: return@mapNotNull null
            val executor = state.activeExecutor ?: return@mapNotNull null
            ConversationRuntimeActiveTaskAssignment(
                conversationId = record.conversationId,
                task = task,
                executor = executor,
                startedAt = state.activeTaskStartedAt,
            )
        }

    override suspend fun findTaskIncident(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeTaskIncident? =
        readRecord(conversationId)?.scheduling?.findIncident(taskId)

    override suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.finishIfIdle()
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.bumpRevision()
            true
        }

    override suspend fun upsertToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.scheduling.executionState ?: return@mutateRecord false
            if (state.activeTaskId != execution.runtimeTaskId || state.activeExecutor != execution.executor) {
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
                executor = execution.executor,
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
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val state = record.scheduling.executionState ?: return@mutateRecord false
            if (state.activeTaskId != taskId || state.activeExecutor != executor) {
                return@mutateRecord false
            }
            if (record.toolExecutions.isNotEmpty()) {
                record.toolExecutions = emptyList()
                record.bumpRevision()
            }
            true
        }

    override suspend fun upsertMemoryOperation(
        conversationId: Conversation.Id,
        operation: ConversationRuntimeMemoryOperation,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = true) { record ->
            val operations = record.memoryOperations.toMutableList()
            val existingIndex = operations.indexOfFirst { it.runId == operation.runId }
            if (existingIndex >= 0 && operations[existingIndex] == operation) {
                return@mutateRecord false
            }
            if (existingIndex >= 0) {
                operations[existingIndex] = operation
            } else {
                operations += operation
            }
            record.memoryOperations = operations.retainedMemoryOperations()
            record.bumpRevision()
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
            val monitoredTaskIds = record.commandMonitors
                .asSequence()
                .filterNot(CommandMonitor::isTerminal)
                .mapTo(mutableSetOf()) { it.commandTaskId }
            val retainedTasks = tasks
                .partition {
                    it.status == CommandTask.Status.WORKING || it.id in monitoredTaskIds
                }
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

    override suspend fun findCommandTasks(conversationId: Conversation.Id): List<CommandTask> =
        readRecord(conversationId)?.commandTasks.orEmpty()

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

    override suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ): CommandMonitorSyncResult =
        mutateRecord(monitor.conversationId, createIfMissing = true) { record ->
            require(events.all { it.conversationId == monitor.conversationId && it.monitorId == monitor.id }) {
                "Command monitor events must belong to the synchronized monitor"
            }
            val storedEvents = record.commandMonitorEvents.toMutableList()
            events.forEach { event ->
                val index = storedEvents.indexOfFirst { it.id == event.id }
                if (index >= 0) {
                    storedEvents[index] = event.copy(
                        deliveredAt = event.deliveredAt ?: storedEvents[index].deliveredAt,
                    )
                } else {
                    storedEvents += event
                }
            }

            val monitors = record.commandMonitors.toMutableList()
            val existingIndex = monitors.indexOfFirst { it.id == monitor.id }
            val existing = monitors.getOrNull(existingIndex)
            val previousStatus = existing?.status
            val storedMonitor = monitor.mergeCoordinatorState(existing)
            if (existingIndex >= 0) {
                monitors[existingIndex] = storedMonitor
            } else {
                monitors += storedMonitor
            }

            val pendingEventMonitorIds = storedEvents.asSequence()
                .filter { it.deliveryRequested && it.deliveredAt == null }
                .mapTo(mutableSetOf()) { it.monitorId }
            val retainedMonitors = monitors
                .partition {
                    !it.isTerminal ||
                        it.id in pendingEventMonitorIds ||
                        (
                            it.terminalNotificationRequestedAt != null &&
                                it.terminalNotificationDeliveredAt == null
                        )
                }
                .let { (active, terminal) ->
                    active + terminal.sortedBy { it.createdAt }
                        .takeLast(COMMAND_MONITOR_TERMINAL_RETENTION_LIMIT)
                }
                .sortedBy { it.createdAt }
            val retainedIds = retainedMonitors.mapTo(mutableSetOf()) { it.id }
            val evictedMonitors = monitors.filterNot { it.id in retainedIds }
            record.commandMonitors = retainedMonitors
            record.commandMonitorEvents = storedEvents
                .filter { it.monitorId in retainedIds }
                .partition { it.deliveryRequested && it.deliveredAt == null }
                .let { (pending, delivered) ->
                    pending + delivered.sortedBy { it.occurredAt }
                        .takeLast(COMMAND_MONITOR_DELIVERED_EVENT_RETENTION_LIMIT)
                }
                .sortedBy { it.occurredAt }

            if (previousStatus != storedMonitor.status) {
                record.appendTrace(
                    conversationId = storedMonitor.conversationId,
                    kind = ConversationRuntimeTraceEntry.Kind.COMMAND_MONITOR,
                    status = storedMonitor.status.toTraceStatus(),
                    message = "${storedMonitor.id.value}: ${storedMonitor.status}",
                )
            }
            record.bumpRevision()
            CommandMonitorSyncResult(storedMonitor, evictedMonitors)
        }

    override suspend fun findCommandMonitors(): List<CommandMonitor> =
        readAllRecords().flatMap { it.commandMonitors }

    override suspend fun findCommandMonitors(conversationId: Conversation.Id): List<CommandMonitor> =
        readRecord(conversationId)?.commandMonitors.orEmpty()

    override suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor? =
        readRecord(conversationId)?.commandMonitors?.firstOrNull { it.id == monitorId }

    override suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id?,
    ): List<CommandMonitorEvent> =
        readRecord(conversationId)?.commandMonitorEvents
            .orEmpty()
            .filter { monitorId == null || it.monitorId == monitorId }

    override suspend fun markCommandMonitorEventsDelivered(
        conversationId: Conversation.Id,
        eventIds: Set<CommandMonitorEvent.Id>,
        deliveredAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            if (eventIds.isEmpty()) return@mutateRecord false
            var changed = false
            record.commandMonitorEvents = record.commandMonitorEvents.map { event ->
                if (event.id in eventIds && event.deliveredAt == null) {
                    check(event.deliveryRequested) {
                        "Command monitor event ${event.id.value} did not request automatic delivery"
                    }
                    changed = true
                    event.copy(deliveredAt = deliveredAt)
                } else {
                    event
                }
            }
            if (changed) record.bumpRevision()
            changed
        }

    override suspend fun markCommandMonitorTerminalNotificationDelivered(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        deliveredAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val index = record.commandMonitors.indexOfFirst { it.id == monitorId }
            if (index < 0) return@mutateRecord false
            val monitor = record.commandMonitors[index]
            if (!monitor.isTerminal ||
                monitor.terminalNotificationRequestedAt == null ||
                monitor.terminalNotificationDeliveredAt != null
            ) {
                return@mutateRecord false
            }
            record.commandMonitors = record.commandMonitors.toMutableList().apply {
                this[index] = monitor.copy(
                    terminalNotificationDeliveredAt = deliveredAt,
                    updatedAt = maxOf(monitor.updatedAt, deliveredAt),
                )
            }
            record.bumpRevision()
            true
        }

    override suspend fun requestCommandMonitorCancellation(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        requestedAt: Instant,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.requestCommandMonitorCancellation(conversationId, monitorId, requestedAt)
        }

    override suspend fun requestPause(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.requestPause(Clock.System.now())
            if (!transition.result) return@mutateRecord false
            if (!transition.changed) return@mutateRecord true
            record.scheduling = transition.state
            record.appendControlTrace(
                conversationId,
                checkNotNull(transition.state.executionState).controlState,
            )
            record.bumpRevision()
            true
        }

    override suspend fun markPaused(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.markPaused(Clock.System.now())
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendControlTrace(conversationId, ConversationExecutionState.ControlState.PAUSED)
            record.bumpRevision()
            true
        }

    override suspend fun requestResume(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.requestResume(Clock.System.now())
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendControlTrace(conversationId, ConversationExecutionState.ControlState.RUNNING)
            record.bumpRevision()
            true
        }

    override suspend fun requestStop(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.requestTerminalState(
                ConversationExecutionState.ControlState.STOPPING,
                Clock.System.now(),
            )
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendControlTrace(
                conversationId,
                transition.state.executionState?.controlState
                    ?: ConversationExecutionState.ControlState.STOPPING,
            )
            record.bumpRevision()
            true
        }

    override suspend fun requestInterrupt(conversationId: Conversation.Id): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.requestTerminalState(
                ConversationExecutionState.ControlState.INTERRUPTING,
                Clock.System.now(),
            )
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendControlTrace(
                conversationId,
                transition.state.executionState?.controlState
                    ?: ConversationExecutionState.ControlState.INTERRUPTING,
            )
            record.bumpRevision()
            true
        }

    override suspend fun abort(conversationId: Conversation.Id) {
        mutateRecord(conversationId, createIfMissing = false) { record ->
            record.scheduling = record.scheduling.abort().state
            record.toolExecutions = emptyList()
            record.bumpRevision()
            Unit
        }
    }

    override suspend fun find(conversationId: Conversation.Id): ConversationExecutionState? =
        readRecord(conversationId)?.scheduling?.executionState

    override suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.cancelByMessageId(messageId)
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
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

    override suspend fun claimActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> =
        mutateRecord(conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.claimActiveInsertions(taskId, executor, placement)
            if (transition.changed) {
                record.scheduling = transition.state
                record.bumpRevision()
            }
            transition.result
        }

    override suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeTask> =
        readRecord(conversationId)?.scheduling?.listPending().orEmpty()

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

    override suspend fun listReadyWorkItems(limit: Int): List<ConversationRuntimeWorkItem> {
        require(limit > 0) { "Conversation runtime ready-work limit must be positive" }
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT ready_task_id, record_json
                    FROM conversation_runtime_records
                    WHERE ready_task_id IS NOT NULL
                    ORDER BY ready_at, conversation_id
                    LIMIT ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, limit)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                val indexedTaskId = ConversationRuntimeTask.Id(result.getString("ready_task_id"))
                                val item = checkNotNull(result.runtimeRecord().readyWorkItem()) {
                                    "Conversation runtime ready-work index points to a non-runnable record"
                                }
                                check(item.taskId == indexedTaskId) {
                                    "Conversation runtime ready-work index is inconsistent: task=${indexedTaskId.value}"
                                }
                                add(item)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun postgresSchedulingSignals(): Flow<ConversationRuntimeSchedulingSignal> = callbackFlow {
        val connection = withContext(Dispatchers.IO) {
            val listenerConnection = openSchedulingListenerConnection()
            try {
                listenerConnection.createStatement().use { statement ->
                    statement.execute("LISTEN $SCHEDULING_NOTIFICATION_CHANNEL")
                }
                listenerConnection
            } catch (error: Throwable) {
                listenerConnection.close()
                throw error
            }
        }
        val listenerJob = try {
            val pgConnection = connection.unwrap(PGConnection::class.java)
            trySend(ConversationRuntimeSchedulingSignal.ListenerReady).getOrThrow()
            launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        pgConnection.getNotifications(0).orEmpty().forEach { notification ->
                            val conversationId = notification.parameter
                                ?.takeIf(String::isNotBlank)
                                ?.let(Conversation::Id)
                                ?: return@forEach
                            trySend(ConversationRuntimeSchedulingSignal.Changed(conversationId)).getOrThrow()
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    close(error)
                }
            }
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
        awaitClose {
            listenerJob.cancel()
            runCatching(connection::close)
        }
    }

    private fun openSchedulingListenerConnection(): Connection =
        if (dataSource is HikariDataSource) {
            DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password)
        } else {
            dataSource.connection
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
                    val schedulingStateBefore = record.schedulingState()
                    val result = block(record)
                    if (createIfMissing || connection.recordExists(conversationId)) {
                        connection.upsertRecord(record)
                    }
                    val schedulingStateChanged = schedulingStateBefore != record.schedulingState()
                    if (schedulingStateChanged) {
                        connection.notifySchedulingChanged(conversationId)
                    }
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }

    private fun Connection.notifySchedulingChanged(conversationId: Conversation.Id) {
        prepareStatement("SELECT pg_notify(?, ?)").use { statement ->
            statement.setString(1, SCHEDULING_NOTIFICATION_CHANNEL)
            statement.setString(2, conversationId.value)
            statement.execute()
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

    private fun Connection.upsertRecord(record: RuntimeRecord) {
        val readyWorkItem = record.readyWorkItem()
        prepareStatement(
            """
            INSERT INTO conversation_runtime_records(
                conversation_id,
                record_json,
                updated_at,
                ready_task_id,
                ready_at
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (conversation_id) DO UPDATE
            SET record_json = EXCLUDED.record_json,
                updated_at = EXCLUDED.updated_at,
                ready_task_id = EXCLUDED.ready_task_id,
                ready_at = EXCLUDED.ready_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, record.conversationId.value)
            statement.setObject(2, jsonb(record))
            statement.setTimestamp(3, Clock.System.now().toTimestamp())
            statement.setString(4, readyWorkItem?.taskId?.value)
            statement.setTimestamp(5, readyWorkItem?.createdAt?.toTimestamp())
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

    @Serializable
    private data class RuntimeRecord(
        val conversationId: Conversation.Id,
        var revision: Long = 0,
        var scheduling: ConversationRuntimeSchedulingState =
            ConversationRuntimeSchedulingState(conversationId),
        var toolExecutions: List<ConversationRuntimeToolExecution> = emptyList(),
        var memoryOperations: List<ConversationRuntimeMemoryOperation> = emptyList(),
        var commandTasks: List<CommandTask> = emptyList(),
        var commandMonitors: List<CommandMonitor> = emptyList(),
        var commandMonitorEvents: List<CommandMonitorEvent> = emptyList(),
        var trace: List<ConversationRuntimeTraceEntry> = emptyList(),
        var eventLog: List<ConversationRuntimeEventLogEntry> = emptyList(),
        var traceSequence: Long = 0,
        var eventSequence: Long = 0,
    ) {
        fun snapshot(): ConversationRuntimeSnapshot =
            ConversationRuntimeSnapshot(
                revision = revision,
                conversationId = conversationId,
                state = scheduling.executionState,
                activeTask = scheduling.activeTask,
                activeInsertions = scheduling.activeInsertions,
                continuationTask = scheduling.continuationTask,
                pendingTasks = scheduling.pendingTasks,
                toolExecutions = toolExecutions,
                memoryOperations = memoryOperations,
                commandTasks = commandTasks,
                commandMonitors = commandMonitors,
                incidents = scheduling.incidents,
                trace = trace.takeLast(TRACE_SNAPSHOT_LIMIT),
                lastEventSequence = eventSequence,
            )

        fun bumpRevision() {
            revision += 1
        }

        fun readyWorkItem(): ConversationRuntimeWorkItem? = scheduling.readyWorkItem()

        fun schedulingState(): SchedulingState =
            SchedulingState(
                readyTaskId = readyWorkItem()?.taskId,
                controlState = scheduling.executionState?.controlState,
                activeTaskId = scheduling.executionState?.activeTaskId,
                activeExecutor = scheduling.executionState?.activeExecutor,
                activeTaskStartedAt = scheduling.executionState?.activeTaskStartedAt,
            )

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

        fun requestCommandMonitorCancellation(
            conversationId: Conversation.Id,
            monitorId: CommandMonitor.Id,
            requestedAt: Instant,
        ): Boolean {
            val index = commandMonitors.indexOfFirst { it.id == monitorId }
            if (index < 0) return false
            val monitor = commandMonitors[index]
            if (monitor.isTerminal) return false
            if (monitor.cancellationRequestedAt != null) return true
            commandMonitors = commandMonitors.toMutableList().apply {
                this[index] = monitor.copy(
                    cancellationRequestedAt = requestedAt,
                    statusMessage = "Cancellation requested",
                    updatedAt = requestedAt,
                )
            }
            appendTrace(
                conversationId = conversationId,
                kind = ConversationRuntimeTraceEntry.Kind.COMMAND_MONITOR,
                status = ConversationRuntimeTraceEntry.Status.UPDATED,
                message = "${monitor.id.value}: cancellation requested",
            )
            bumpRevision()
            return true
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
            executor: ConversationRuntimeExecutorIdentity? = null,
            kind: ConversationRuntimeTraceEntry.Kind,
            status: ConversationRuntimeTraceEntry.Status,
            message: String? = null,
        ): ConversationRuntimeTraceEntry {
            traceSequence += 1
            val entry = ConversationRuntimeTraceEntry(
                sequence = traceSequence,
                conversationId = conversationId,
                taskId = taskId,
                executor = executor,
                kind = kind,
                status = status,
                message = message,
                createdAt = Clock.System.now(),
            )
            trace = (trace + entry).takeLast(TRACE_RETENTION_LIMIT)
            return entry
        }

        fun recordIncidentTrace(incident: ConversationRuntimeTaskIncident) {
            appendTrace(
                conversationId = conversationId,
                taskId = incident.task.id,
                executor = incident.executor,
                kind = when (incident.kind) {
                    ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED ->
                        ConversationRuntimeTraceEntry.Kind.TASK_FAILED
                    ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN ->
                        ConversationRuntimeTraceEntry.Kind.TASK_IN_DOUBT
                },
                status = ConversationRuntimeTraceEntry.Status.FAILED,
                message = "${incident.kind}: " +
                    if (incident.errorType.isNullOrBlank()) {
                        incident.message
                    } else {
                        "${incident.errorType}: ${incident.message}"
                    },
            )
            if (incident.task.payload !is ConversationRuntimeTask.Payload.ExecutionIncident) {
                appendTrace(
                    conversationId = conversationId,
                    taskId = ConversationRuntimeTask.Id("${incident.task.id.value}:incident"),
                    kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                    status = ConversationRuntimeTraceEntry.Status.STARTED,
                    message = "Execution incident handling task submitted",
                )
            }
        }
    }

    private data class SchedulingState(
        val readyTaskId: ConversationRuntimeTask.Id?,
        val controlState: ConversationExecutionState.ControlState?,
        val activeTaskId: ConversationRuntimeTask.Id?,
        val activeExecutor: ConversationRuntimeExecutorIdentity?,
        val activeTaskStartedAt: Instant?,
    )

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))

    private fun CommandTask.Status.toTraceStatus(): ConversationRuntimeTraceEntry.Status = when (this) {
        CommandTask.Status.WORKING -> ConversationRuntimeTraceEntry.Status.STARTED
        CommandTask.Status.COMPLETED -> ConversationRuntimeTraceEntry.Status.COMPLETED
        CommandTask.Status.FAILED -> ConversationRuntimeTraceEntry.Status.FAILED
        CommandTask.Status.CANCELLED -> ConversationRuntimeTraceEntry.Status.CANCELLED
    }

    private fun CommandMonitor.Status.toTraceStatus(): ConversationRuntimeTraceEntry.Status = when (this) {
        CommandMonitor.Status.WORKING -> ConversationRuntimeTraceEntry.Status.STARTED
        CommandMonitor.Status.COMPLETED -> ConversationRuntimeTraceEntry.Status.COMPLETED
        CommandMonitor.Status.FAILED -> ConversationRuntimeTraceEntry.Status.FAILED
        CommandMonitor.Status.CANCELLED -> ConversationRuntimeTraceEntry.Status.CANCELLED
    }

    private fun CommandMonitor.mergeCoordinatorState(existing: CommandMonitor?): CommandMonitor {
        if (existing == null) return this
        val preserveCancellation = cancellationRequestedAt == null && existing.cancellationRequestedAt != null
        return copy(
            cancellationRequestedAt = cancellationRequestedAt ?: existing.cancellationRequestedAt,
            terminalNotificationDeliveredAt =
                terminalNotificationDeliveredAt ?: existing.terminalNotificationDeliveredAt,
            statusMessage = if (preserveCancellation) existing.statusMessage else statusMessage,
            updatedAt = maxOf(updatedAt, existing.updatedAt),
        )
    }

    private companion object {
        fun List<ConversationRuntimeMemoryOperation>.retainedMemoryOperations(): List<ConversationRuntimeMemoryOperation> {
            val (active, terminal) = partition {
                it.status == MemoryRun.Status.QUEUED || it.status == MemoryRun.Status.RUNNING
            }
            return (active + terminal.sortedBy { it.updatedAt }.takeLast(MEMORY_OPERATION_TERMINAL_RETENTION_LIMIT))
                .sortedBy { it.updatedAt }
        }

        const val MEMORY_OPERATION_TERMINAL_RETENTION_LIMIT = 20
        const val COMMAND_TASK_TERMINAL_RETENTION_LIMIT = 100
        const val COMMAND_MONITOR_TERMINAL_RETENTION_LIMIT = 100
        const val COMMAND_MONITOR_DELIVERED_EVENT_RETENTION_LIMIT = 1_000
        const val TRACE_SNAPSHOT_LIMIT = 200
        const val TRACE_RETENTION_LIMIT = 2_000
        const val EVENT_LOG_RETENTION_LIMIT = 10_000
        const val SCHEDULING_NOTIFICATION_CHANNEL = "gromozeka_conversation_runtime_ready"
        const val SCHEDULING_LISTENER_RECONNECT_DELAY_MILLIS = 1_000L
    }
}
