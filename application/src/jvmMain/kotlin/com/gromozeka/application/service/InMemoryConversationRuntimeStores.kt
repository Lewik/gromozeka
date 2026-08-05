package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorSyncResult
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeActiveTaskAssignment
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeEventSubscription
import com.gromozeka.domain.service.ConversationRuntimeMemoryOperation
import com.gromozeka.domain.service.ConversationRuntimeSchedulingState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSignal
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.tool.AiToolDescriptor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

class InMemoryConversationRuntimeCoordinator : ConversationRuntimeCoordinator {
    private val mutex = Mutex()
    private val schedulingByConversation = mutableMapOf<Conversation.Id, ConversationRuntimeSchedulingState>()
    private val toolExecutionsByConversation =
        mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeToolExecution>>()
    private val memoryOperationsByConversation =
        mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeMemoryOperation>>()
    private val commandTasksByConversation = mutableMapOf<Conversation.Id, MutableList<CommandTask>>()
    private val commandMonitorsByConversation = mutableMapOf<Conversation.Id, MutableList<CommandMonitor>>()
    private val commandMonitorEventsByConversation =
        mutableMapOf<Conversation.Id, MutableList<CommandMonitorEvent>>()
    private val traceByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeTraceEntry>>()
    private val eventLogByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeEventLogEntry>>()
    private val readyWorkByConversation = mutableMapOf<Conversation.Id, ConversationRuntimeWorkItem>()
    private val schedulingSignalChannel = Channel<ConversationRuntimeSchedulingSignal.Changed>(Channel.CONFLATED)
    private val revisionsByConversation = mutableMapOf<Conversation.Id, Long>()
    private val traceSequencesByConversation = mutableMapOf<Conversation.Id, Long>()
    private val eventSequencesByConversation = mutableMapOf<Conversation.Id, Long>()

    override val schedulingSignals: Flow<ConversationRuntimeSchedulingSignal> = flow {
        emit(ConversationRuntimeSchedulingSignal.ListenerReady)
        emitAll(schedulingSignalChannel.receiveAsFlow())
    }

    override suspend fun submit(task: ConversationRuntimeTask): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[task.conversationId]
                ?: ConversationRuntimeSchedulingState(task.conversationId)
            val transition = current.submit(task, Clock.System.now())
            if (!transition.result) return@withLock false
            schedulingByConversation[task.conversationId] = transition.state
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

    override suspend fun updatePendingUserTurn(task: ConversationRuntimeTask): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[task.conversationId] ?: return@withLock false
            val transition = current.updatePendingUserTurn(task)
            if (!transition.result) return@withLock false
            schedulingByConversation[task.conversationId] = transition.state
            appendTrace(
                conversationId = task.conversationId,
                taskId = task.id,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.UPDATED,
                message = "Queued user turn updated: placement=${task.placement}",
            )
            scheduleNextRunnableTaskIfReady(task.conversationId)
            bumpRevision(task.conversationId)
            true
        }

    override suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        executorCapabilities: Set<ConversationRuntimeCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): ConversationRuntimeTask? =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock null
            val transition = current.claim(
                taskId = taskId,
                executor = executor,
                executorCapabilities = executorCapabilities,
                workerWorkspaceMountIds = workerWorkspaceMountIds,
                now = Clock.System.now(),
            )
            val task = transition.result ?: return@withLock null
            if (!transition.changed) return@withLock task
            schedulingByConversation[conversationId] = transition.state
            removeScheduledWorkItems(conversationId, task.id)
            appendTrace(
                conversationId = conversationId,
                taskId = task.id,
                executor = executor,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Runtime task claimed by $executor",
            )
            bumpRevision(conversationId)
            task
        }

    override suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        outcome: ConversationRuntimeTaskOutcome,
    ): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.completeActiveTask(
                taskId = taskId,
                executor = executor,
                outcome = outcome,
                now = Clock.System.now(),
            )
            if (!transition.result) return@withLock false
            schedulingByConversation[conversationId] = transition.state
            appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                executor = executor,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_COMPLETED,
                status = ConversationRuntimeTraceEntry.Status.COMPLETED,
                message = "Runtime task completed",
            )
            bumpRevision(conversationId)
            val completedState = transition.state.executionState
            if (completedState?.controlState != ConversationExecutionState.ControlState.STOPPING &&
                completedState?.controlState != ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                scheduleNextRunnableTaskIfReady(conversationId)
            }
            true
        }

    override suspend fun markActiveTaskStarted(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        startedAt: Instant,
    ): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.markActiveTaskStarted(taskId, executor, startedAt)
            if (!transition.result) return@withLock false
            if (!transition.changed) return@withLock true
            schedulingByConversation[conversationId] = transition.state
            appendTrace(
                conversationId = conversationId,
                taskId = taskId,
                executor = executor,
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
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean =
        mutex.withLock {
            schedulingByConversation[conversationId]?.confirmActiveTaskOwner(taskId, executor) == true
        }

    override suspend fun markActiveTaskInDoubt(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock null
            val transition = current.recordActiveTaskIncident(
                taskId = taskId,
                executor = executor,
                kind = ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
                message = message,
                errorType = errorType,
                occurredAt = Clock.System.now(),
            )
            val incident = transition.result ?: return@withLock null
            schedulingByConversation[conversationId] = transition.state
            recordIncidentTrace(incident)
            toolExecutionsByConversation.remove(conversationId)
            scheduleNextRunnableTaskIfReady(conversationId)
            bumpRevision(conversationId)
            incident
        }

    override suspend fun recordClaimedTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock null
            val transition = current.recordActiveTaskIncident(
                taskId = taskId,
                executor = executor,
                kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                message = message,
                errorType = errorType,
                occurredAt = Clock.System.now(),
            )
            val incident = transition.result ?: return@withLock null
            schedulingByConversation[conversationId] = transition.state
            recordIncidentTrace(incident)
            toolExecutionsByConversation.remove(conversationId)
            scheduleNextRunnableTaskIfReady(conversationId)
            bumpRevision(conversationId)
            incident
        }

    override suspend fun recordPendingTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock null
            val transition = current.recordPendingTaskDeliveryFailure(
                taskId = taskId,
                executor = executor,
                message = message,
                errorType = errorType,
                occurredAt = Clock.System.now(),
            )
            val incident = transition.result ?: return@withLock null
            schedulingByConversation[conversationId] = transition.state
            removeScheduledWorkItems(conversationId, taskId)
            recordIncidentTrace(incident)
            scheduleNextRunnableTaskIfReady(conversationId)
            bumpRevision(conversationId)
            incident
        }

    override suspend fun listActiveTaskAssignments(): List<ConversationRuntimeActiveTaskAssignment> =
        mutex.withLock {
            schedulingByConversation.mapNotNull { (conversationId, scheduling) ->
                val task = scheduling.activeTask ?: return@mapNotNull null
                val state = scheduling.executionState ?: return@mapNotNull null
                val executor = state.activeExecutor ?: return@mapNotNull null
                ConversationRuntimeActiveTaskAssignment(
                    conversationId = conversationId,
                    task = task,
                    executor = executor,
                    startedAt = state.activeTaskStartedAt,
                )
            }
        }

    override suspend fun findTaskIncident(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeTaskIncident? =
        mutex.withLock {
            schedulingByConversation[conversationId]?.findIncident(taskId)
        }

    override suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.finishIfIdle()
            if (!transition.result) return@withLock false
            schedulingByConversation[conversationId] = transition.state
            readyWorkByConversation.remove(conversationId)
            bumpRevision(conversationId)
            signalSchedulingChanged(conversationId)
            true
        }

    override suspend fun upsertToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ): Boolean =
        mutex.withLock {
            val state = schedulingByConversation[conversationId]?.executionState ?: return@withLock false
            if (state.activeTaskId != execution.runtimeTaskId || state.activeExecutor != execution.executor) {
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
                executor = execution.executor,
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
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean =
        mutex.withLock {
            val state = schedulingByConversation[conversationId]?.executionState ?: return@withLock false
            if (state.activeTaskId != taskId || state.activeExecutor != executor) {
                return@withLock false
            }
            if (toolExecutionsByConversation.remove(conversationId) != null) {
                bumpRevision(conversationId)
            }
            true
        }

    override suspend fun upsertMemoryOperation(
        conversationId: Conversation.Id,
        operation: ConversationRuntimeMemoryOperation,
    ): Boolean =
        mutex.withLock {
            val operations = memoryOperationsByConversation.getOrPut(conversationId) { mutableListOf() }
            val existingIndex = operations.indexOfFirst { it.runId == operation.runId }
            if (existingIndex >= 0 && operations[existingIndex] == operation) {
                return@withLock false
            }
            if (existingIndex >= 0) {
                operations[existingIndex] = operation
            } else {
                operations.add(operation)
            }
            memoryOperationsByConversation[conversationId] = operations.retainedMemoryOperations().toMutableList()
            bumpRevision(conversationId)
            true
        }

    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult =
        mutex.withLock {
            val tasks = commandTasksByConversation.getOrPut(task.conversationId) { mutableListOf() }
            val existingIndex = tasks.indexOfFirst { it.id == task.id }
            val existing = tasks.getOrNull(existingIndex)
            val previousStatus = existing?.status
            val storedTask = task.mergeCoordinatorState(existing)
            if (existingIndex >= 0) {
                tasks[existingIndex] = storedTask
            } else {
                tasks.add(storedTask)
            }
            val monitoredTaskIds = commandMonitorsByConversation[task.conversationId]
                .orEmpty()
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
            tasks.clear()
            tasks.addAll(retainedTasks)
            if (previousStatus != storedTask.status) {
                appendTrace(
                    conversationId = storedTask.conversationId,
                    kind = ConversationRuntimeTraceEntry.Kind.COMMAND_TASK,
                    status = storedTask.status.toTraceStatus(),
                    message = "${storedTask.id.value}: ${storedTask.status}",
                )
            }
            bumpRevision(storedTask.conversationId)
            CommandTaskUpsertResult(storedTask, evictedTasks)
        }

    override suspend fun findCommandTasks(): List<CommandTask> = mutex.withLock {
        commandTasksByConversation.values.flatten()
    }

    override suspend fun findCommandTasks(conversationId: Conversation.Id): List<CommandTask> =
        mutex.withLock {
            commandTasksByConversation[conversationId].orEmpty().toList()
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

    override suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ): CommandMonitorSyncResult =
        mutex.withLock {
            require(events.all { it.conversationId == monitor.conversationId && it.monitorId == monitor.id }) {
                "Command monitor events must belong to the synchronized monitor"
            }
            val storedEvents = commandMonitorEventsByConversation
                .getOrPut(monitor.conversationId) { mutableListOf() }
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

            val monitors = commandMonitorsByConversation
                .getOrPut(monitor.conversationId) { mutableListOf() }
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
            monitors.clear()
            monitors += retainedMonitors

            val retainedEvents = storedEvents
                .filter { it.monitorId in retainedIds }
                .partition { it.deliveryRequested && it.deliveredAt == null }
                .let { (pending, delivered) ->
                    pending + delivered.sortedBy { it.occurredAt }
                        .takeLast(COMMAND_MONITOR_DELIVERED_EVENT_RETENTION_LIMIT)
                }
                .sortedBy { it.occurredAt }
            storedEvents.clear()
            storedEvents += retainedEvents

            if (previousStatus != storedMonitor.status) {
                appendTrace(
                    conversationId = storedMonitor.conversationId,
                    kind = ConversationRuntimeTraceEntry.Kind.COMMAND_MONITOR,
                    status = storedMonitor.status.toTraceStatus(),
                    message = "${storedMonitor.id.value}: ${storedMonitor.status}",
                )
            }
            bumpRevision(storedMonitor.conversationId)
            CommandMonitorSyncResult(storedMonitor, evictedMonitors)
        }

    override suspend fun findCommandMonitors(): List<CommandMonitor> =
        mutex.withLock {
            commandMonitorsByConversation.values.flatten()
        }

    override suspend fun findCommandMonitors(conversationId: Conversation.Id): List<CommandMonitor> =
        mutex.withLock {
            commandMonitorsByConversation[conversationId].orEmpty().toList()
        }

    override suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor? =
        mutex.withLock {
            commandMonitorsByConversation[conversationId]?.firstOrNull { it.id == monitorId }
        }

    override suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id?,
    ): List<CommandMonitorEvent> =
        mutex.withLock {
            commandMonitorEventsByConversation[conversationId]
                .orEmpty()
                .filter { monitorId == null || it.monitorId == monitorId }
        }

    override suspend fun markCommandMonitorEventsDelivered(
        conversationId: Conversation.Id,
        eventIds: Set<CommandMonitorEvent.Id>,
        deliveredAt: Instant,
    ): Boolean =
        mutex.withLock {
            if (eventIds.isEmpty()) return@withLock false
            val events = commandMonitorEventsByConversation[conversationId] ?: return@withLock false
            var changed = false
            events.replaceAll { event ->
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
            if (changed) bumpRevision(conversationId)
            changed
        }

    override suspend fun markCommandMonitorTerminalNotificationDelivered(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        deliveredAt: Instant,
    ): Boolean =
        mutex.withLock {
            val monitors = commandMonitorsByConversation[conversationId] ?: return@withLock false
            val index = monitors.indexOfFirst { it.id == monitorId }
            if (index < 0) return@withLock false
            val monitor = monitors[index]
            if (!monitor.isTerminal ||
                monitor.terminalNotificationRequestedAt == null ||
                monitor.terminalNotificationDeliveredAt != null
            ) {
                return@withLock false
            }
            monitors[index] = monitor.copy(
                terminalNotificationDeliveredAt = deliveredAt,
                updatedAt = maxOf(monitor.updatedAt, deliveredAt),
            )
            bumpRevision(conversationId)
            true
        }

    override suspend fun requestCommandMonitorCancellation(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        requestedAt: Instant,
    ): Boolean =
        mutex.withLock {
            requestCommandMonitorCancellationLocked(conversationId, monitorId, requestedAt)
        }

    override suspend fun requestPause(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.requestPause(Clock.System.now())
            if (!transition.result) return@withLock false
            if (!transition.changed) return@withLock true
            schedulingByConversation[conversationId] = transition.state
            readyWorkByConversation.remove(conversationId)
            appendControlTrace(
                conversationId,
                checkNotNull(transition.state.executionState).controlState,
            )
            bumpRevision(conversationId)
            signalSchedulingChanged(conversationId)
            true
        }

    override suspend fun markPaused(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.markPaused(Clock.System.now())
            if (!transition.result) return@withLock false
            schedulingByConversation[conversationId] = transition.state
            appendControlTrace(conversationId, ConversationExecutionState.ControlState.PAUSED)
            bumpRevision(conversationId)
            signalSchedulingChanged(conversationId)
            true
        }

    override suspend fun requestResume(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.requestResume(Clock.System.now())
            if (!transition.result) return@withLock false
            schedulingByConversation[conversationId] = transition.state
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
            val current = schedulingByConversation[conversationId]
                ?: ConversationRuntimeSchedulingState(conversationId)
            schedulingByConversation[conversationId] = current.abort().state
            toolExecutionsByConversation.remove(conversationId)
            readyWorkByConversation.remove(conversationId)
            bumpRevision(conversationId)
            signalSchedulingChanged(conversationId)
        }
    }

    override suspend fun find(conversationId: Conversation.Id): ConversationExecutionState? =
        mutex.withLock {
            schedulingByConversation[conversationId]?.executionState
        }

    override suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock false
            val transition = current.cancelByMessageId(messageId)
            if (transition.result) {
                schedulingByConversation[conversationId] = transition.state
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
            transition.result
        }

    override suspend fun claimActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> =
        mutex.withLock {
            val current = schedulingByConversation[conversationId] ?: return@withLock emptyList()
            val transition = current.claimActiveInsertions(taskId, executor, placement)
            if (transition.changed) {
                schedulingByConversation[conversationId] = transition.state
                bumpRevision(conversationId)
            }
            transition.result
        }

    override suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeTask> =
        mutex.withLock {
            schedulingByConversation[conversationId]?.listPending().orEmpty()
        }

    override suspend fun snapshot(conversationId: Conversation.Id): ConversationRuntimeSnapshot =
        mutex.withLock {
            val scheduling = schedulingByConversation[conversationId]
            ConversationRuntimeSnapshot(
                revision = revisionsByConversation[conversationId] ?: 0L,
                conversationId = conversationId,
                state = scheduling?.executionState,
                activeTask = scheduling?.activeTask,
                activeInsertions = scheduling?.activeInsertions.orEmpty(),
                continuationTask = scheduling?.continuationTask,
                pendingTasks = scheduling?.pendingTasks.orEmpty(),
                toolExecutions = toolExecutionsByConversation[conversationId]?.toList().orEmpty(),
                memoryOperations = memoryOperationsByConversation[conversationId]?.toList().orEmpty(),
                commandTasks = commandTasksByConversation[conversationId]?.toList().orEmpty(),
                commandMonitors = commandMonitorsByConversation[conversationId]?.toList().orEmpty(),
                incidents = scheduling?.incidents.orEmpty(),
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

    override suspend fun listReadyWorkItems(limit: Int): List<ConversationRuntimeWorkItem> =
        mutex.withLock {
            require(limit > 0) { "Conversation runtime ready-work limit must be positive" }
            readyWorkByConversation.values
                .sortedWith(
                    compareBy<ConversationRuntimeWorkItem> { it.createdAt }
                        .thenBy { it.conversationId.value }
                )
                .take(limit)
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

    private fun requestCommandMonitorCancellationLocked(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        requestedAt: Instant,
    ): Boolean {
        val monitors = commandMonitorsByConversation[conversationId] ?: return false
        val index = monitors.indexOfFirst { it.id == monitorId }
        if (index < 0) return false
        val monitor = monitors[index]
        if (monitor.isTerminal) return false
        if (monitor.cancellationRequestedAt != null) return true
        monitors[index] = monitor.copy(
            cancellationRequestedAt = requestedAt,
            statusMessage = "Cancellation requested",
            updatedAt = requestedAt,
        )
        appendTrace(
            conversationId = conversationId,
            kind = ConversationRuntimeTraceEntry.Kind.COMMAND_MONITOR,
            status = ConversationRuntimeTraceEntry.Status.UPDATED,
            message = "${monitor.id.value}: cancellation requested",
        )
        bumpRevision(conversationId)
        return true
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

    private fun removeScheduledWorkItems(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ) {
        val removed = readyWorkByConversation[conversationId]
            ?.takeIf { it.taskId == taskId }
            ?.let {
                readyWorkByConversation.remove(conversationId)
                true
            } == true
        if (removed) {
            signalSchedulingChanged(conversationId)
        }
    }

    private fun requestTerminalStatus(
        conversationId: Conversation.Id,
        controlState: ConversationExecutionState.ControlState,
    ): Boolean {
        val current = schedulingByConversation[conversationId] ?: return false
        val transition = current.requestTerminalState(controlState, Clock.System.now())
        if (!transition.result) return false
        schedulingByConversation[conversationId] = transition.state
        readyWorkByConversation.remove(conversationId)
        appendControlTrace(
            conversationId,
            transition.state.executionState?.controlState ?: controlState,
        )
        bumpRevision(conversationId)
        signalSchedulingChanged(conversationId)
        return true
    }

    private fun bumpRevision(conversationId: Conversation.Id): Long {
        val next = (revisionsByConversation[conversationId] ?: 0L) + 1L
        revisionsByConversation[conversationId] = next
        return next
    }

    private fun scheduleNextRunnableTaskIfReady(conversationId: Conversation.Id) {
        val item = schedulingByConversation[conversationId]?.readyWorkItem()
        if (item == null) {
            if (readyWorkByConversation.remove(conversationId) != null) {
                signalSchedulingChanged(conversationId)
            }
            return
        }
        if (readyWorkByConversation[conversationId] == item) {
            return
        }
        readyWorkByConversation[conversationId] = item
        signalSchedulingChanged(conversationId)
    }

    private fun signalSchedulingChanged(conversationId: Conversation.Id) {
        check(
            schedulingSignalChannel.trySend(
                ConversationRuntimeSchedulingSignal.Changed(conversationId)
            ).isSuccess
        ) {
            "Conversation runtime scheduling signal channel is closed"
        }
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
        executor: ConversationRuntimeExecutorIdentity? = null,
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
            executor = executor,
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

    private fun CommandMonitor.Status.toTraceStatus(): ConversationRuntimeTraceEntry.Status = when (this) {
        CommandMonitor.Status.WORKING -> ConversationRuntimeTraceEntry.Status.STARTED
        CommandMonitor.Status.COMPLETED -> ConversationRuntimeTraceEntry.Status.COMPLETED
        CommandMonitor.Status.FAILED -> ConversationRuntimeTraceEntry.Status.FAILED
        CommandMonitor.Status.CANCELLED -> ConversationRuntimeTraceEntry.Status.CANCELLED
    }

    private fun recordIncidentTrace(incident: ConversationRuntimeTaskIncident) {
        appendTrace(
            conversationId = incident.task.conversationId,
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
                formatIncidentMessage(incident.errorType, incident.message),
        )
        if (incident.task.payload !is ConversationRuntimeTask.Payload.ExecutionIncident) {
            appendTrace(
                conversationId = incident.task.conversationId,
                taskId = ConversationRuntimeTask.Id("${incident.task.id.value}:incident"),
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED,
                status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "Execution incident handling task submitted",
            )
        }
    }

    private fun formatIncidentMessage(errorType: String?, message: String): String =
        if (errorType.isNullOrBlank()) message else "$errorType: $message"

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

    override suspend fun updateTools(
        identity: ConversationRuntimeWorkerIdentity,
        tools: List<AiToolDescriptor>,
        at: Instant,
    ): Boolean =
        mutex.withLock {
            val existing = registrations[identity.workerId] ?: return@withLock false
            if (existing.identity != identity || existing.stoppedAt != null || at < existing.lastHeartbeatAt) {
                return@withLock false
            }
            registrations[identity.workerId] = existing.copy(
                tools = tools,
                lastHeartbeatAt = at,
            )
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

@Service
@Primary
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
