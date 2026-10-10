package com.gromozeka.infrastructure.db.runtime

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
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
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTurnSummary
import com.gromozeka.domain.service.ConversationRuntimeTurnId
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
import kotlin.time.Clock
import kotlin.time.Instant
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
    private val collaborationRepository: com.gromozeka.domain.repository.AgentCollaborationRepository? = null,
) : ConversationRuntimeCoordinator {
    private val log = KLoggers.logger(this)
    private val inventoryLog = KLoggers.logger("com.gromozeka.runtime.commandInventory")
    // Keys are fixed operation/scope/outcome names, never Worker ids or payloads.
    private val inventoryWarningTimes = ConcurrentHashMap<String, AtomicLong>()

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

    override suspend fun submit(task: ConversationRuntimeTask, acceptPreviouslySubmitted: Boolean): Boolean =
        submitInternal(task, acceptPreviouslySubmitted, null)

    override suspend fun submitUserInput(task: ConversationRuntimeTask, mode: com.gromozeka.domain.model.UserMessageDeliveryMode): Boolean =
        submitInternal(task, false, mode)

    private suspend fun submitInternal(task: ConversationRuntimeTask, acceptPreviouslySubmitted: Boolean,
        userDeliveryMode: com.gromozeka.domain.model.UserMessageDeliveryMode?): Boolean =
        mutateRecord(task.conversationId, createIfMissing = true) { record ->
            val transition = record.scheduling.submit(task, Clock.System.now(), acceptPreviouslySubmitted, userDeliveryMode)
            if (!transition.changed) return@mutateRecord transition.result
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task submitted: placement=${transition.state.pendingTasks.firstOrNull { it.id == task.id }?.placement ?: task.placement}",
            )
            record.bumpRevision()
            true
        }

    override suspend fun updatePendingMessageSubmission(task: ConversationRuntimeTask): Boolean =
        mutateRecord(task.conversationId, createIfMissing = false) { record ->
            val transition = record.scheduling.updatePendingMessageSubmission(task)
            if (!transition.result) return@mutateRecord false
            record.scheduling = transition.state
            record.appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.UPDATED,
                message = "Queued message submission updated: placement=${task.placement}",
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
        find(conversationId)?.let { it.activeTaskId == taskId && it.activeExecutor == executor } ?: false

    override suspend fun markActiveTaskInDoubt(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.SCHEDULING, RuntimeComponent.TOOLS),
        ) { record ->
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
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.SCHEDULING, RuntimeComponent.TOOLS),
        ) { record ->
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
        readActiveSchedulingRecords().mapNotNull { record ->
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
        readJson(conversationId, """
            SELECT entry FROM conversation_runtime_records,
                LATERAL jsonb_array_elements(COALESCE(scheduling -> 'incidents', '[]'::jsonb))
                    WITH ORDINALITY AS items(entry, ordinal)
            WHERE conversation_id = ? AND entry #>> '{task,id}' = ?
            ORDER BY ordinal DESC LIMIT 1
        """.trimIndent(), taskId.value)?.let { json.decodeFromString(it) }

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
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.SCHEDULING, RuntimeComponent.TOOLS),
        ) { record ->
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
            if (execution.status == ConversationRuntimeToolExecution.Status.FAILED || execution.isError == true) {
                record.recordTurnProblem(record.scheduling.activeTask?.turnId, ConversationRuntimeTurnSummary.Problem(
                    key = "tool:${execution.toolCallId.value}",
                    message = execution.toolName,
                    occurredAt = execution.completedAt ?: execution.startedAt,
                ))
            }
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
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.SCHEDULING, RuntimeComponent.TOOLS),
        ) { record ->
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
        mutateRecord(
            conversationId,
            createIfMissing = true,
            components = setOf(RuntimeComponent.MEMORY),
        ) { record ->
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
        mutateRecord(
            task.conversationId,
            createIfMissing = true,
            components = setOf(RuntimeComponent.COMMANDS, RuntimeComponent.MONITORS),
        ) { record ->
            val tasks = record.commandTasks.toMutableList()
            val existingIndex = tasks.indexOfFirst { it.id == task.id }
            val existing = tasks.getOrNull(existingIndex)
            val previousStatus = existing?.status
            val storedTask = task.mergeCoordinatorState(existing)
            if (existingIndex >= 0) {
                tasks[existingIndex] = storedTask
            } else {
                tasks += storedTask
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
            if (retainedTasks == record.commandTasks) return@mutateRecord CommandTaskUpsertResult(storedTask, evictedTasks)
            record.commandTasks = retainedTasks
            if (previousStatus != storedTask.status) {
                record.appendTrace(
                    conversationId = storedTask.conversationId,
                    kind = ConversationRuntimeTraceEntry.Kind.COMMAND_TASK,
                    status = storedTask.status.toTraceStatus(),
                    message = "${storedTask.id.value}: ${storedTask.status}",
                )
            }
            record.bumpRevision()
            CommandTaskUpsertResult(storedTask, evictedTasks)
        }

    override suspend fun findCommandTasks(): List<CommandTask> =
        readInventory<CommandTask>(RuntimeComponent.COMMANDS)

    override suspend fun findCommandTasks(workerId: ConversationRuntimeWorkerId): List<CommandTask> =
        readWorkerInventory(WorkerCommandInventoryKind.TASKS, workerId) { json.decodeFromString<CommandTask>(it) }

    override suspend fun findCommandTasks(conversationId: Conversation.Id): List<CommandTask> =
        readInventory<CommandTask>(RuntimeComponent.COMMANDS, conversationId)

    override suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask? = readInventory<CommandTask>(RuntimeComponent.COMMANDS, conversationId, taskId.value).firstOrNull()

    override suspend fun requestCommandTaskCancellation(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        requestedAt: Instant,
    ): Boolean =
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.COMMANDS),
        ) { record ->
            record.requestCommandTaskCancellation(conversationId, taskId, requestedAt)
        }

    override suspend fun requestCommandTaskCancellations(
        conversationId: Conversation.Id,
        requestedAt: Instant,
    ): Int =
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.COMMANDS),
        ) { record ->
            record.commandTasks
                .filter { it.status == CommandTask.Status.WORKING && it.visualId == null }
                .count { task ->
                    record.requestCommandTaskCancellation(conversationId, task.id, requestedAt)
                }
        }

    override suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ): CommandMonitorSyncResult =
        mutateRecord(
            monitor.conversationId,
            createIfMissing = true,
            components = setOf(RuntimeComponent.MONITORS, RuntimeComponent.MONITOR_EVENTS),
        ) { record ->
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
            val retainedEvents = storedEvents
                .filter { it.monitorId in retainedIds }
                .partition { it.deliveryRequested && it.deliveredAt == null }
                .let { (pending, delivered) ->
                    pending + delivered.sortedBy { it.occurredAt }
                        .takeLast(COMMAND_MONITOR_DELIVERED_EVENT_RETENTION_LIMIT)
                }
                .sortedBy { it.occurredAt }

            if (retainedMonitors == record.commandMonitors && retainedEvents == record.commandMonitorEvents) {
                return@mutateRecord CommandMonitorSyncResult(storedMonitor, evictedMonitors)
            }
            record.commandMonitors = retainedMonitors
            record.commandMonitorEvents = retainedEvents
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
        readInventory<CommandMonitor>(RuntimeComponent.MONITORS)

    override suspend fun findCommandMonitors(workerId: ConversationRuntimeWorkerId): List<CommandMonitor> =
        readWorkerInventory(WorkerCommandInventoryKind.MONITORS, workerId) { json.decodeFromString<CommandMonitor>(it) }

    override suspend fun findCommandMonitors(conversationId: Conversation.Id): List<CommandMonitor> =
        readInventory<CommandMonitor>(RuntimeComponent.MONITORS, conversationId)

    override suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor? =
        readInventory<CommandMonitor>(RuntimeComponent.MONITORS, conversationId, monitorId.value).firstOrNull()

    override suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id?,
    ): List<CommandMonitorEvent> =
        readInventory<CommandMonitorEvent>(RuntimeComponent.MONITOR_EVENTS, conversationId, monitorId?.value, "monitorId")

    override suspend fun markCommandMonitorEventsDelivered(
        conversationId: Conversation.Id,
        eventIds: Set<CommandMonitorEvent.Id>,
        deliveredAt: Instant,
    ): Boolean =
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.MONITOR_EVENTS),
        ) { record ->
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
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.MONITORS),
        ) { record ->
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
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.MONITORS),
        ) { record ->
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

    override suspend fun requestInterrupt(conversationId: Conversation.Id, expectedTurnId: com.gromozeka.domain.service.ConversationRuntimeTurnId?): Boolean =
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.SCHEDULING, RuntimeComponent.COMMANDS),
        ) { record ->
            val current = record.scheduling.activeTask ?: record.scheduling.continuationTask
            val transition = record.scheduling.requestTerminalState(
                ConversationExecutionState.ControlState.INTERRUPTING,
                Clock.System.now(),
                expectedTurnId,
            )
            if (!transition.result) return@mutateRecord false
            if (expectedTurnId != null && current?.turnId == expectedTurnId) {
                record.commandTasks.filter { it.status == CommandTask.Status.WORKING }.forEach {
                    record.requestCommandTaskCancellation(conversationId, it.id, Clock.System.now())
                }
            }
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
        mutateRecord(
            conversationId,
            createIfMissing = false,
            components = setOf(RuntimeComponent.SCHEDULING, RuntimeComponent.TOOLS),
        ) { record ->
            record.scheduling = record.scheduling.abort(Clock.System.now()).state
            record.toolExecutions = emptyList()
            record.bumpRevision()
            Unit
        }
    }

    override suspend fun find(conversationId: Conversation.Id): ConversationExecutionState? =
        readJson(conversationId, "SELECT scheduling -> 'executionState' FROM conversation_runtime_records WHERE conversation_id = ?")?.let { json.decodeFromString(it) }

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
        readJson(conversationId, """
            SELECT CASE WHEN jsonb_typeof(scheduling -> 'continuationTask') = 'object'
                THEN jsonb_build_array(scheduling -> 'continuationTask') ELSE '[]'::jsonb END
                || COALESCE(scheduling -> 'pendingTasks', '[]'::jsonb)
            FROM conversation_runtime_records WHERE conversation_id = ?
        """.trimIndent())?.let { json.decodeFromString<List<ConversationRuntimeTask>>(it) }.orEmpty()

    override suspend fun schedulingSnapshot(conversationId: Conversation.Id): ConversationRuntimeSchedulingSnapshot =
        readJson(conversationId, """
            SELECT jsonb_build_object('conversationId', conversation_id, 'lastEventSequence', event_sequence)
                || (scheduling - 'completedIdempotencyKeys' - 'pendingTurnTerminationInstructions' - 'lastTurn')
            FROM conversation_runtime_records WHERE conversation_id = ?
        """.trimIndent())?.let { json.decodeFromString(it) } ?: ConversationRuntimeSchedulingSnapshot(conversationId)

    override suspend fun snapshot(conversationId: Conversation.Id): ConversationRuntimeSnapshot = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            connection.autoCommit = false
            try {
                val record = connection.loadRecord(conversationId, RuntimeComponent.entries.toSet() - RuntimeComponent.MONITOR_EVENTS, snapshot = true)
                    ?: RuntimeRecord(conversationId)
                connection.prepareStatement("SELECT entry_json FROM conversation_runtime_trace WHERE conversation_id = ? ORDER BY sequence DESC LIMIT ?").use { statement ->
                    statement.setString(1, conversationId.value)
                    statement.setInt(2, TRACE_SNAPSHOT_LIMIT)
                    statement.executeQuery().use { rows ->
                        record.trace = buildList {
                            while (rows.next()) add(json.decodeFromString<ConversationRuntimeTraceEntry>(rows.getString(1)))
                        }.asReversed()
                    }
                }
                // Use this same read transaction: nested pool acquisitions can deadlock
                // when many conversation snapshots are loading concurrently.
                val requests = if (collaborationRepository == null) emptyList() else connection.prepareStatement(
                    "SELECT record_json::text FROM agent_requests WHERE source_conversation_id = ? OR target_conversation_id = ? " +
                        "ORDER BY (state IN ('WORKING','WAITING_USER','WAITING_RESULT')) DESC, (record_json ->> 'createdAt') DESC, id LIMIT 32"
                ).use { statement ->
                    statement.setString(1, conversationId.value); statement.setString(2, conversationId.value)
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) add(json.decodeFromString<com.gromozeka.domain.model.AgentRequest>(rows.getString(1)))
                    } }
                }
                val result = record.snapshot().copy(agentRequests = requests)
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override suspend fun lastEventSequence(conversationId: Conversation.Id): Long = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT event_sequence FROM conversation_runtime_records WHERE conversation_id = ?"
            ).use { statement ->
                statement.setString(1, conversationId.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
        }
    }

    override suspend fun recordEvent(event: ConversationRuntimeEvent): ConversationRuntimeEventLogEntry =
        mutateRecord(event.conversationId, createIfMissing = true, components =
            if (event is ConversationRuntimeEvent.MessageEmitted && (event.message.error != null ||
                event.message.content.any { it is Conversation.Message.ContentItem.ToolResult && it.isError })) {
                setOf(RuntimeComponent.SCHEDULING)
            } else emptySet()
        ) { record ->
            if (event is ConversationRuntimeEvent.MessageEmitted) {
                event.message.error?.let { error ->
                    record.recordTurnProblem(event.turnId, ConversationRuntimeTurnSummary.Problem(
                        key = "message:${event.message.id.value}", message = error.message,
                        occurredAt = event.message.createdAt,
                    ))
                }
                event.message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                    .filter { it.isError }.forEach { result ->
                        val detail = result.result.filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
                            .joinToString("\n") { it.content }.take(2_000)
                        record.recordTurnProblem(event.turnId, ConversationRuntimeTurnSummary.Problem(
                            key = "tool:${result.toolUseId.value}",
                            message = listOf(result.toolName, detail).filter(String::isNotBlank).joinToString(": "),
                            occurredAt = event.message.createdAt,
                        ))
                    }
            }
            val sequence = record.eventSequence + 1
            record.eventSequence = sequence
            val entry = ConversationRuntimeEventLogEntry(
                sequence = sequence,
                conversationId = event.conversationId,
                event = event,
                createdAt = Clock.System.now(),
            )
            record.eventLog = record.eventLog + entry
            record.appendTrace(
                conversationId = event.conversationId,
                taskId = when (event) {
                    is ConversationRuntimeEvent.MessageEmitted -> event.taskId
                    is ConversationRuntimeEvent.HistoryChanged -> event.taskId
                    else -> null
                },
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
        require(limit > 0)
        val direction = if (afterSequence == null) "DESC" else "ASC"
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT entry_json FROM conversation_runtime_events
                    WHERE conversation_id = ? AND sequence > ?
                    ORDER BY sequence $direction LIMIT ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, conversationId.value)
                    statement.setLong(2, afterSequence ?: 0L)
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(json.decodeFromString<ConversationRuntimeEventLogEntry>(rows.getString(1)))
                        }.sortedBy { it.sequence }
                    }
                }
            }
        }
    }

    override suspend fun findEmittedMessageIds(
        conversationId: Conversation.Id,
        turnId: ConversationRuntimeTurnId,
        afterSequence: Long,
    ): Set<Conversation.Message.Id> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT message_id FROM conversation_runtime_events
                WHERE conversation_id = ? AND turn_id = ? AND sequence > ? AND message_id IS NOT NULL
                ORDER BY sequence
            """.trimIndent()).use { statement ->
                statement.setString(1, conversationId.value)
                statement.setString(2, turnId.value)
                statement.setLong(3, afterSequence)
                statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(Conversation.Message.Id(rows.getString(1))) } }
            }
        }
    }

    override suspend fun findHistoryChanged(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeEvent.HistoryChanged? = readJson(conversationId, """
        SELECT entry_json FROM conversation_runtime_events
        WHERE conversation_id = ? AND task_id = ?
            AND event_type = 'com.gromozeka.domain.service.ConversationRuntimeEvent.HistoryChanged'
        ORDER BY sequence DESC LIMIT 1
    """.trimIndent(), taskId.value)?.let {
        json.decodeFromString<ConversationRuntimeEventLogEntry>(it).event as ConversationRuntimeEvent.HistoryChanged
    }

    override suspend fun listReadyWorkItems(limit: Int): List<ConversationRuntimeWorkItem> {
        require(limit > 0) { "Conversation runtime ready-work limit must be positive" }
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT ready_task_id, jsonb_build_object('conversationId', conversation_id, 'scheduling', scheduling - 'completedIdempotencyKeys' - 'incidents' - 'lastTurn') AS record_json
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

    private suspend fun readJson(conversationId: Conversation.Id, sql: String, key: String? = null): String? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, conversationId.value)
                    if (key != null) statement.setString(2, key)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1)?.takeUnless { it == "null" } else null
                    }
                }
            }
        }

    private suspend inline fun <reified T> readInventory(
        component: RuntimeComponent,
        conversationId: Conversation.Id? = null,
        itemId: String? = null,
        idField: String = "id",
    ): List<T> {
        require(idField == "id" || idField == "monitorId")
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val filters = buildList {
                    if (conversationId != null) add("conversation_id = ?")
                    if (itemId != null) add("entry ->> '$idField' = ?")
                }
                val where = if (filters.isEmpty()) "" else "WHERE " + filters.joinToString(" AND ")
                connection.prepareStatement("SELECT entry FROM conversation_runtime_records, LATERAL jsonb_array_elements(${component.column}) WITH ORDINALITY AS items(entry, ordinal) $where ORDER BY conversation_id, ordinal").use { statement ->
                    var index = 1
                    if (conversationId != null) statement.setString(index++, conversationId.value)
                    if (itemId != null) statement.setString(index, itemId)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(json.decodeFromString<T>(rows.getString(1))) }
                    }
                }
            }
        }
    }

    private suspend fun readActiveSchedulingRecords(): List<RuntimeRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val filter = "WHERE jsonb_typeof(scheduling -> 'activeTask') = 'object'"
            connection.prepareStatement("SELECT jsonb_build_object('conversationId', conversation_id, 'scheduling', scheduling - 'completedIdempotencyKeys' - 'incidents' - 'lastTurn') AS record_json FROM conversation_runtime_records $filter ORDER BY conversation_id").use { statement ->
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.runtimeRecord()) } }
            }
        }
    }

    private suspend fun <T> readWorkerInventory(
        kind: WorkerCommandInventoryKind,
        workerId: ConversationRuntimeWorkerId,
        decode: (String) -> T,
    ): List<T> = loggedInventoryRead("worker", kind.operation, workerId, "items") {
        withContext(Dispatchers.IO) {
            val encodedWorkerId = json.encodeToString(workerId)
            dataSource.connection.use { connection ->
                connection.prepareStatement(workerCommandInventorySql(kind)).use { statement ->
                    statement.setString(1, "[$encodedWorkerId]")
                    statement.setString(2, encodedWorkerId)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) add(decode(result.getString("item_json")))
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> loggedInventoryRead(
        scope: String,
        operation: String,
        workerId: ConversationRuntimeWorkerId?,
        resultUnit: String,
        read: suspend () -> List<T>,
    ): List<T> {
        val started = System.nanoTime()
        try {
            val result = read()
            logInventoryRead(scope, operation, workerId, resultUnit, started, result.size, null)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logInventoryRead(scope, operation, workerId, resultUnit, started, null, error)
            throw error
        }
    }

    private fun logInventoryRead(
        scope: String,
        operation: String,
        workerId: ConversationRuntimeWorkerId?,
        resultUnit: String,
        started: Long,
        count: Int?,
        error: Throwable?,
    ) {
        // Includes dispatch, connection acquisition and decoding; this is not pure SQL execution time.
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        val status = if (error == null) "ok" else "failed"
        val metadata = {
            "event=command_inventory_read scope=$scope operation=$operation " +
                "worker_id=${workerId?.let { json.encodeToString(it.value) } ?: "null"} " +
                "elapsed_ms=$elapsedMillis result_count=${count ?: "unknown"} result_unit=$resultUnit " +
                "status=$status error_type=${error?.javaClass?.simpleName ?: "none"}"
        }
        inventoryLog.debug { metadata() }
        if (error != null || elapsedMillis >= SLOW_INVENTORY_READ_MILLIS) {
            val outcome = if (error == null) "slow" else "failed"
            val key = "$scope/$operation/$outcome"
            val gate = inventoryWarningTimes.computeIfAbsent(key) { AtomicLong(Long.MIN_VALUE) }
            val previous = gate.get()
            val now = System.nanoTime()
            if ((previous == Long.MIN_VALUE || now - previous >= INVENTORY_WARNING_INTERVAL_NANOS) &&
                gate.compareAndSet(previous, now)
            ) {
                // Do not log exception messages/stack traces: SQL or decoder errors can contain payloads.
                inventoryLog.warn { metadata() + " warning=$outcome warning_interval_ms=60000" }
            }
        }
    }

    private suspend fun <T> mutateRecord(
        conversationId: Conversation.Id,
        createIfMissing: Boolean,
        components: Set<RuntimeComponent> = setOf(RuntimeComponent.SCHEDULING),
        block: (RuntimeRecord) -> T,
    ): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                var stored = connection.loadRecord(conversationId, components, lock = true)
                if (stored == null && createIfMissing) {
                    connection.prepareStatement("INSERT INTO conversation_runtime_records(conversation_id, scheduling) VALUES (?, ?) ON CONFLICT DO NOTHING").use { statement ->
                        statement.setString(1, conversationId.value)
                        statement.setObject(2, jsonb(json.encodeToString(ConversationRuntimeSchedulingState(conversationId))))
                        statement.executeUpdate()
                    }
                    stored = checkNotNull(connection.loadRecord(conversationId, components, lock = true))
                }
                val record = stored ?: RuntimeRecord(conversationId)
                val before = record.copy()
                val result = block(record)
                if (stored != null && record != before) {
                    val changedComponents = RuntimeComponent.entries.filter { record.componentValue(it) != before.componentValue(it) }
                    check(components.containsAll(changedComponents)) { "Runtime mutation changed an unloaded component" }
                    connection.updateRecord(record, changedComponents)
                    connection.appendJournal(record)
                    if (RuntimeComponent.SCHEDULING in changedComponents && before.schedulingState() != record.schedulingState()) {
                        connection.notifySchedulingChanged(conversationId)
                    }
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

    private fun Connection.loadRecord(
        conversationId: Conversation.Id,
        components: Set<RuntimeComponent>,
        lock: Boolean = false,
        snapshot: Boolean = false,
    ): RuntimeRecord? {
        val fields = buildList {
            add("'conversationId', conversation_id, 'revision', revision, 'eventSequence', event_sequence, 'traceSequence', trace_sequence")
            components.forEach { component ->
                val expression = if (snapshot && component == RuntimeComponent.SCHEDULING) "scheduling - 'completedIdempotencyKeys'" else component.column
                add("'${component.field}', $expression")
            }
        }.joinToString(", ")
        val suffix = if (lock) " FOR UPDATE" else ""
        return prepareStatement("SELECT jsonb_build_object($fields) AS record_json FROM conversation_runtime_records WHERE conversation_id = ?$suffix").use { statement ->
            statement.setString(1, conversationId.value)
            statement.executeQuery().use { result -> if (result.next()) result.runtimeRecord() else null }
        }
    }

    private fun Connection.updateRecord(record: RuntimeRecord, components: List<RuntimeComponent>) {
        val assignments = buildList {
            add("revision = ?, event_sequence = ?, trace_sequence = ?, updated_at = ?")
            components.forEach { add("${it.column} = ?") }
            if (RuntimeComponent.SCHEDULING in components) add("ready_task_id = ?, ready_at = ?")
        }.joinToString(", ")
        prepareStatement("UPDATE conversation_runtime_records SET $assignments WHERE conversation_id = ?").use { statement ->
            statement.setLong(1, record.revision)
            statement.setLong(2, record.eventSequence)
            statement.setLong(3, record.traceSequence)
            statement.setTimestamp(4, Clock.System.now().toTimestamp())
            var index = 5
            components.forEach { statement.setObject(index++, jsonb(record.encodeComponent(it))) }
            if (RuntimeComponent.SCHEDULING in components) {
                val ready = record.readyWorkItem()
                statement.setString(index++, ready?.taskId?.value)
                statement.setTimestamp(index++, ready?.createdAt?.toTimestamp())
            }
            statement.setString(index, record.conversationId.value)
            check(statement.executeUpdate() == 1) { "Locked runtime record disappeared" }
        }
    }

    private fun Connection.appendJournal(record: RuntimeRecord) {
        if (record.trace.isNotEmpty()) {
            prepareStatement("INSERT INTO conversation_runtime_trace(conversation_id, sequence, created_at, entry_json) VALUES (?, ?, ?, ?)").use { statement ->
                record.trace.forEach { entry ->
                    statement.setString(1, record.conversationId.value)
                    statement.setLong(2, entry.sequence)
                    statement.setTimestamp(3, entry.createdAt.toTimestamp())
                    statement.setObject(4, jsonb(json.encodeToString(entry)))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            pruneJournal("conversation_runtime_trace", record.conversationId, TRACE_RETENTION_LIMIT)
        }
        if (record.eventLog.isNotEmpty()) {
            prepareStatement("INSERT INTO conversation_runtime_events(conversation_id, sequence, created_at, event_type, task_id, turn_id, message_id, entry_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
                record.eventLog.forEach { entry ->
                    val event = entry.event
                    val encodedEntry = json.encodeToJsonElement(ConversationRuntimeEventLogEntry.serializer(), entry)
                    val encodedEvent = (encodedEntry as kotlinx.serialization.json.JsonObject).getValue("event")
                    val eventType = (encodedEvent as kotlinx.serialization.json.JsonObject).getValue("type")
                        .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    statement.setString(1, record.conversationId.value)
                    statement.setLong(2, entry.sequence)
                    statement.setTimestamp(3, entry.createdAt.toTimestamp())
                    statement.setString(4, eventType)
                    statement.setString(5, when (event) {
                        is ConversationRuntimeEvent.MessageEmitted -> event.taskId?.value
                        is ConversationRuntimeEvent.HistoryChanged -> event.taskId.value
                        else -> null
                    })
                    statement.setString(6, (event as? ConversationRuntimeEvent.MessageEmitted)?.turnId?.value)
                    statement.setString(7, (event as? ConversationRuntimeEvent.MessageEmitted)?.message?.id?.value)
                    statement.setObject(8, jsonb(encodedEntry.toString()))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            pruneJournal("conversation_runtime_events", record.conversationId, EVENT_LOG_RETENTION_LIMIT)
        }
    }

    private fun Connection.pruneJournal(table: String, conversationId: Conversation.Id, limit: Int) {
        prepareStatement("""
            DELETE FROM $table WHERE conversation_id = ? AND sequence < (
                SELECT sequence FROM $table WHERE conversation_id = ?
                ORDER BY sequence DESC OFFSET ? LIMIT 1
            )
        """.trimIndent()).use { statement ->
            statement.setString(1, conversationId.value)
            statement.setString(2, conversationId.value)
            statement.setInt(3, limit - 1)
            statement.executeUpdate()
        }
    }

    private fun RuntimeRecord.componentValue(component: RuntimeComponent): Any = when (component) {
        RuntimeComponent.SCHEDULING -> scheduling
        RuntimeComponent.TOOLS -> toolExecutions
        RuntimeComponent.MEMORY -> memoryOperations
        RuntimeComponent.COMMANDS -> commandTasks
        RuntimeComponent.MONITORS -> commandMonitors
        RuntimeComponent.MONITOR_EVENTS -> commandMonitorEvents
    }

    private fun RuntimeRecord.encodeComponent(component: RuntimeComponent): String = when (component) {
        RuntimeComponent.SCHEDULING -> json.encodeToString(scheduling)
        RuntimeComponent.TOOLS -> json.encodeToString(toolExecutions)
        RuntimeComponent.MEMORY -> json.encodeToString(memoryOperations)
        RuntimeComponent.COMMANDS -> json.encodeToString(commandTasks)
        RuntimeComponent.MONITORS -> json.encodeToString(commandMonitors)
        RuntimeComponent.MONITOR_EVENTS -> json.encodeToString(commandMonitorEvents)
    }

    private enum class RuntimeComponent(val field: String, val column: String) {
        SCHEDULING("scheduling", "scheduling"),
        TOOLS("toolExecutions", "tool_executions"),
        MEMORY("memoryOperations", "memory_operations"),
        COMMANDS("commandTasks", "command_tasks"),
        MONITORS("commandMonitors", "command_monitors"),
        MONITOR_EVENTS("commandMonitorEvents", "command_monitor_events"),
    }

    private fun ResultSet.runtimeRecord(): RuntimeRecord = json.decodeFromString(getString("record_json"))

    private fun jsonb(encoded: String): PGobject = PGobject().apply {
        type = "jsonb"
        value = encoded
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
                lastTurn = scheduling.lastTurn,
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
            trace = trace + entry
            return entry
        }

        fun recordTurnProblem(turnId: ConversationRuntimeTurnId?, problem: ConversationRuntimeTurnSummary.Problem) {
            val turn = scheduling.lastTurn?.takeIf { it.turnId == turnId } ?: return
            scheduling = scheduling.copy(lastTurn = turn.copy(problems = turn.problems.filterNot { it.key == problem.key } + problem))
        }

        fun recordIncidentTrace(incident: ConversationRuntimeTaskIncident) {
            recordTurnProblem(incident.task.turnId, ConversationRuntimeTurnSummary.Problem(
                key = "incident:${incident.task.id.value}", message = incident.message,
                occurredAt = incident.occurredAt,
                outcomeUnknown = incident.kind == ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
            ))
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

    private fun CommandTask.mergeCoordinatorState(existing: CommandTask?): CommandTask {
        if (existing == null) return this
        val preserveCancellationStatus =
            !isTerminal && cancellationRequestedAt == null && existing.cancellationRequestedAt != null
        return copy(
            cancellationRequestedAt = cancellationRequestedAt ?: existing.cancellationRequestedAt,
            completionNotificationRequestedAt =
                completionNotificationRequestedAt ?: existing.completionNotificationRequestedAt,
            completionNotificationDeliveredAt =
                completionNotificationDeliveredAt ?: existing.completionNotificationDeliveredAt,
            statusMessage = if (preserveCancellationStatus) existing.statusMessage else statusMessage,
            updatedAt = maxOf(updatedAt, existing.updatedAt),
        )
    }

    private fun CommandMonitor.mergeCoordinatorState(existing: CommandMonitor?): CommandMonitor {
        if (existing == null) return this
        val preserveCancellationStatus =
            !isTerminal && cancellationRequestedAt == null && existing.cancellationRequestedAt != null
        return copy(
            cancellationRequestedAt = cancellationRequestedAt ?: existing.cancellationRequestedAt,
            terminalNotificationRequestedAt =
                terminalNotificationRequestedAt ?: existing.terminalNotificationRequestedAt,
            terminalNotificationDeliveredAt =
                terminalNotificationDeliveredAt ?: existing.terminalNotificationDeliveredAt,
            statusMessage = if (preserveCancellationStatus) existing.statusMessage else statusMessage,
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
        const val SLOW_INVENTORY_READ_MILLIS = 500L
        const val INVENTORY_WARNING_INTERVAL_NANOS = 60_000_000_000L
        const val SCHEDULING_NOTIFICATION_CHANNEL = "gromozeka_conversation_runtime_ready"
        const val SCHEDULING_LISTENER_RECONNECT_DELAY_MILLIS = 1_000L
    }
}

/** Only these trusted, fixed JSON paths may be interpolated into inventory SQL. */
internal enum class WorkerCommandInventoryKind(val fieldName: String, val operation: String) {
    TASKS("command_tasks", "tasks"),
    MONITORS("command_monitors", "monitors"),
}

/** Shared with EXPLAIN tests so they inspect the actual production query. */
internal fun workerCommandInventorySql(kind: WorkerCommandInventoryKind): String = """
    SELECT entry.payload::text AS item_json
    FROM conversation_runtime_records AS records
    CROSS JOIN LATERAL jsonb_array_elements(
        records.${kind.fieldName}
    ) WITH ORDINALITY AS entry(payload, ordinal)
    WHERE jsonb_path_query_array(records.${kind.fieldName}, '$[*].workerId') @> CAST(? AS jsonb)
      AND entry.payload -> 'workerId' = CAST(? AS jsonb)
    ORDER BY records.conversation_id, entry.ordinal
""".trimIndent()
