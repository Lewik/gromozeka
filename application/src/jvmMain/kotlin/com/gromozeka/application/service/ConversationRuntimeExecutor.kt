package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSignal
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.WorkspaceDomainService
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ConversationRuntimeExecutor(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val workspaceService: WorkspaceDomainService,
    private val aiConfigurationService: AiConfigurationService,
    private val taskRunnerProvider: ObjectProvider<ConversationRuntimeTaskRunner>,
    descriptor: ConversationRuntimeExecutorDescriptor,
    @Qualifier("applicationScope") private val parentScope: CoroutineScope,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val executor = descriptor.identity
    private val capabilities = descriptor.capabilities
    private val schedulingMutex = Mutex()
    private val scheduledItems = ConcurrentHashMap.newKeySet<ScheduledWork>()
    private val activeExecutions = ConcurrentHashMap<Conversation.Id, ActiveExecution>()
    private val lifecycleLock = Any()

    @Volatile
    private var running = false
    private var runtimeJob: Job? = null
    private var schedulingCollectionJob: Job? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            val parentJob = parentScope.coroutineContext[Job]
            val executorJob = SupervisorJob(parentJob)
            val executorScope = CoroutineScope(parentScope.coroutineContext + executorJob)
            runtimeJob = executorJob
            running = true
            try {
                runBlocking {
                    recoverAbandonedServerAssignments()
                }
                schedulingCollectionJob = executorScope.launch {
                    runtimeCoordinator.schedulingSignals.conflate().collect { signal ->
                        when (signal) {
                            ConversationRuntimeSchedulingSignal.ListenerReady -> {
                                cancelInterruptedExecutions()
                                scheduleReadyWork(executorScope)
                            }

                            is ConversationRuntimeSchedulingSignal.Changed -> {
                                cancelInterruptedExecutions()
                                scheduleReadyWork(executorScope)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                running = false
                runtimeJob = null
                runBlocking {
                    executorJob.cancelAndJoin()
                }
                throw error
            }
            log.info {
                "Conversation runtime executor started: executor=$executor capabilities=${capabilities.joinToString()}"
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            if (!running && runtimeJob == null) return
            running = false
            runBlocking {
                schedulingCollectionJob?.cancelAndJoin()
                runtimeJob?.cancelAndJoin()
            }
            schedulingCollectionJob = null
            runtimeJob = null
            scheduledItems.clear()
            activeExecutions.clear()
            log.info { "Conversation runtime executor stopped: executor=$executor" }
        }
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 300

    private suspend fun scheduleReadyWork(executorScope: CoroutineScope) {
        schedulingMutex.withLock {
            runtimeCoordinator.listReadyWorkItems(READY_WORK_BATCH_SIZE).forEach { item ->
                val scheduledWork = ScheduledWork(item.conversationId, item.taskId)
                if (!scheduledItems.add(scheduledWork)) {
                    return@forEach
                }
                executorScope.launch {
                    try {
                        processRuntimeWorkItem(item)
                    } finally {
                        scheduledItems.remove(scheduledWork)
                        if (running) {
                            scheduleReadyWork(executorScope)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processRuntimeWorkItem(item: ConversationRuntimeWorkItem) {
        log.info {
            "Conversation runtime work item ready: conversation=${item.conversationId.value} " +
                "reason=${item.reason} task=${item.taskId.value} executor=$executor"
        }
        val task = try {
            when (item.reason) {
                ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED -> prepareSubmittedTask(item)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                settlePreparationFailure(item, error)
            }
            throw error
        } catch (error: Throwable) {
            log.error(error) {
                "Conversation runtime work preparation failed: conversation=${item.conversationId.value} " +
                    "reason=${item.reason} task=${item.taskId.value} executor=$executor error=${error.message}"
            }
            settlePreparationFailure(item, error)
            return
        } ?: return

        val executionStarted = try {
            runtimeCoordinator.markActiveTaskStarted(
                conversationId = task.conversationId,
                taskId = task.id,
                executor = executor,
                startedAt = Clock.System.now(),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                recordClaimedTaskDeliveryFailure(
                    task = task,
                    message = "Failed to record runtime task execution start",
                    errorType = error::class.simpleName,
                )
            }
            throw error
        }
        if (!executionStarted) {
            log.warn {
                "Conversation runtime task ownership was lost before execution start: " +
                    "conversation=${task.conversationId.value} task=${task.id.value} executor=$executor"
            }
            return
        }
        publishRuntimeSnapshot(item.conversationId)
        runClaimedTask(task)
    }

    private suspend fun settlePreparationFailure(
        item: ConversationRuntimeWorkItem,
        error: Throwable,
    ) {
        val snapshot = runtimeCoordinator.snapshot(item.conversationId)
        val claimedTask = snapshot.activeTask
            ?.takeIf { it.id == item.taskId && snapshot.state?.activeExecutor == executor }
        if (claimedTask != null) {
            if (snapshot.state?.activeTaskStartedAt == null) {
                recordClaimedTaskDeliveryFailure(
                    task = claimedTask,
                    message = "Executor stopped after durable task claim but before execution started",
                    errorType = error::class.simpleName,
                )
            } else {
                recordClaimedTaskIncident(
                    task = claimedTask,
                    message = "Executor stopped while task execution was active; the task outcome is unknown",
                    errorType = error::class.simpleName,
                )
            }
            return
        }
        if (error is CancellationException) {
            return
        }
        recordPendingTaskFailure(
            item = item,
            message = error.message ?: "Unknown conversation runtime preparation error",
            errorType = error::class.simpleName,
        )
    }

    private suspend fun prepareSubmittedTask(item: ConversationRuntimeWorkItem): ConversationRuntimeTask? {
        when (awaitExecutionReadiness(item.conversationId)) {
            ExecutionReadiness.CONTINUE -> Unit
            ExecutionReadiness.WAIT -> {
                publishRuntimeSnapshot(item.conversationId)
                return null
            }
            ExecutionReadiness.STOP -> {
                finishRuntimeIfIdle(item.conversationId)
                publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(item.conversationId))
                return null
            }
        }

        val task = runtimeCoordinator.claimDeliveredTask(
            conversationId = item.conversationId,
            taskId = item.taskId,
            executor = executor,
            executorCapabilities = capabilities,
            workerWorkspaceMountIds = executor.workspaceMountIds(),
        )
        if (task != null) {
            aiConfigurationService.refreshIfChanged()
            return task
        }

        val state = runtimeCoordinator.find(item.conversationId)
        if (state?.activeTaskId == item.taskId) {
            return null
        }
        val taskStillPending = runtimeCoordinator.listPending(item.conversationId).any { it.id == item.taskId }
        if (!taskStillPending) {
            if (finishRuntimeIfIdle(item.conversationId)) {
                publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(item.conversationId))
            }
            return null
        }
        if (state != null && state.controlState != ConversationExecutionState.ControlState.RUNNING) {
            publishRuntimeSnapshot(item.conversationId)
            return null
        }
        recordPendingTaskFailure(
            item = item,
            message = "Runtime task requirements are not satisfied by the configured Server executor",
            errorType = "WorkItemNotClaimable",
        )
        return null
    }

    private suspend fun runClaimedTask(task: ConversationRuntimeTask) {
        val taskJob = currentCoroutineContext()[Job]
            ?: throw IllegalStateException("Conversation runtime task coroutine has no Job")
        val activeExecution = ActiveExecution(task.id, taskJob)
        check(activeExecutions.putIfAbsent(task.conversationId, activeExecution) == null) {
            "Conversation already has an active local execution: ${task.conversationId.value}"
        }
        try {
            cancelInterruptedExecution(task.conversationId)
            currentCoroutineContext().ensureActive()
            val outcome = taskRunnerProvider.getObject().runRuntimeTask(task, executor) { message ->
                publishRuntimeEvent(
                    ConversationRuntimeEvent.MessageEmitted(
                        conversationId = task.conversationId,
                        taskId = task.id,
                        message = message,
                    )
                )
            }
            activeExecutions.remove(task.conversationId, activeExecution)
            if (!runtimeCoordinator.completeActiveTask(task.conversationId, task.id, executor, outcome)) {
                throw IllegalStateException(
                    "Conversation runtime task ownership was lost before completion: " +
                        "conversation=${task.conversationId.value} task=${task.id.value} executor=$executor"
                )
            }
            publishRuntimeSnapshot(task.conversationId)

            if (finishRuntimeIfIdle(task.conversationId)) {
                publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(task.conversationId))
            }
        } catch (error: CancellationException) {
            val interrupted = withContext(NonCancellable) {
                val state = runtimeCoordinator.find(task.conversationId)
                if (state?.activeTaskId == task.id &&
                    state.activeExecutor == executor &&
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
                ) {
                    runtimeCoordinator.abort(task.conversationId)
                    publishRuntimeSnapshot(task.conversationId)
                    publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(task.conversationId))
                    true
                } else {
                    recordClaimedTaskIncident(
                        task = task,
                        message = "Executor stopped while task execution was active; the task outcome is unknown",
                        errorType = "ExecutorExecutionCancelled",
                    )
                    false
                }
            }
            if (!interrupted) throw error
        } catch (error: Throwable) {
            recordClaimedTaskIncident(
                task = task,
                message = error.message ?: "Unknown conversation runtime error",
                errorType = error::class.simpleName,
            )
        } finally {
            activeExecutions.remove(task.conversationId, activeExecution)
        }
    }

    private suspend fun cancelInterruptedExecution(conversationId: Conversation.Id) {
        val activeExecution = activeExecutions[conversationId] ?: return
        val state = runtimeCoordinator.find(conversationId) ?: return
        if (state.activeTaskId == activeExecution.taskId &&
            state.activeExecutor == executor &&
            state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            activeExecution.job.cancel(
                CancellationException("Conversation runtime interrupted: ${conversationId.value}")
            )
        }
    }

    private suspend fun cancelInterruptedExecutions() {
        activeExecutions.keys.toList().forEach { conversationId ->
            cancelInterruptedExecution(conversationId)
        }
    }

    private suspend fun recoverAbandonedServerAssignments() {
        val incidents = runtimeCoordinator.listActiveTaskAssignments().mapNotNull { assignment ->
            val previousServer = assignment.executor as? ConversationRuntimeExecutorIdentity.Server
                ?: return@mapNotNull null
            if (previousServer == executor) {
                return@mapNotNull null
            }
            if (assignment.startedAt == null) {
                runtimeCoordinator.recordClaimedTaskDeliveryFailure(
                    conversationId = assignment.conversationId,
                    taskId = assignment.task.id,
                    executor = assignment.executor,
                    message = "Previous Server session stopped before task execution started; the task was not executed",
                    errorType = "ServerSessionLost",
                )
            } else {
                runtimeCoordinator.markActiveTaskInDoubt(
                    conversationId = assignment.conversationId,
                    taskId = assignment.task.id,
                    executor = assignment.executor,
                    message = "Previous Server session stopped while task execution was active; the task outcome is unknown",
                    errorType = "ServerSessionLost",
                )
            }
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
        if (incidents.isNotEmpty()) {
            log.warn {
                "Recovered abandoned Server runtime assignments: " +
                    incidents.joinToString {
                        "${it.task.conversationId.value}/${it.task.id.value}/${it.kind}"
                    }
            }
        }
    }

    private suspend fun recordPendingTaskFailure(
        item: ConversationRuntimeWorkItem,
        message: String,
        errorType: String?,
    ) {
        val incident = runtimeCoordinator.recordPendingTaskDeliveryFailure(
            conversationId = item.conversationId,
            taskId = item.taskId,
            executor = executor,
            message = message,
            errorType = errorType,
        ) ?: return
        publishRuntimeSnapshot(item.conversationId)
        publishRuntimeEvent(
            ConversationRuntimeEvent.ExecutionFailed(
                conversationId = item.conversationId,
                message = incident.message,
                failureType = incident.kind.name,
            )
        )
    }

    private suspend fun recordClaimedTaskIncident(
        task: ConversationRuntimeTask,
        message: String,
        errorType: String?,
    ) {
        val incident = runtimeCoordinator.markActiveTaskInDoubt(
            conversationId = task.conversationId,
            taskId = task.id,
            executor = executor,
            message = message,
            errorType = errorType,
        ) ?: return
        publishRuntimeSnapshot(task.conversationId)
        publishRuntimeEvent(
            ConversationRuntimeEvent.ExecutionFailed(
                conversationId = task.conversationId,
                message = incident.message,
                failureType = incident.kind.name,
            )
        )
    }

    private suspend fun recordClaimedTaskDeliveryFailure(
        task: ConversationRuntimeTask,
        message: String,
        errorType: String?,
    ) {
        val incident = runtimeCoordinator.recordClaimedTaskDeliveryFailure(
            conversationId = task.conversationId,
            taskId = task.id,
            executor = executor,
            message = message,
            errorType = errorType,
        ) ?: return
        publishRuntimeSnapshot(task.conversationId)
        publishRuntimeEvent(
            ConversationRuntimeEvent.ExecutionFailed(
                conversationId = task.conversationId,
                message = incident.message,
                failureType = incident.kind.name,
            )
        )
    }

    private suspend fun finishRuntimeIfIdle(conversationId: Conversation.Id): Boolean {
        val finished = runtimeCoordinator.finishIfIdle(conversationId)
        if (finished) publishRuntimeSnapshot(conversationId)
        return finished
    }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        publishLiveRuntimeEvent(
            ConversationRuntimeEvent.SnapshotUpdated(
                conversationId = conversationId,
                snapshot = runtimeCoordinator.snapshot(conversationId),
            )
        )
    }

    private suspend fun publishRuntimeEvent(event: ConversationRuntimeEvent) {
        val logEntry = runtimeCoordinator.recordEvent(event)
        publishLiveRuntimeEvent(event.withCursorSequence(logEntry.sequence))
    }

    private suspend fun publishLiveRuntimeEvent(event: ConversationRuntimeEvent) {
        try {
            runtimeEventBus.publish(event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to publish live runtime event; durable cursor replay will recover it: " +
                    "conversation=${event.conversationId.value} event=${event::class.simpleName} error=${error.message}"
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

    private suspend fun awaitExecutionReadiness(conversationId: Conversation.Id): ExecutionReadiness {
        val state = runtimeCoordinator.find(conversationId) ?: return ExecutionReadiness.CONTINUE
        return when (state.controlState) {
            ConversationExecutionState.ControlState.STOPPING,
            ConversationExecutionState.ControlState.INTERRUPTING -> ExecutionReadiness.STOP
            ConversationExecutionState.ControlState.PAUSED -> ExecutionReadiness.WAIT
            ConversationExecutionState.ControlState.PAUSE_REQUESTED -> {
                if (runtimeCoordinator.markPaused(conversationId)) {
                    publishRuntimeSnapshot(conversationId)
                }
                ExecutionReadiness.WAIT
            }
            ConversationExecutionState.ControlState.RUNNING -> ExecutionReadiness.CONTINUE
        }
    }

    private suspend fun ConversationRuntimeExecutorIdentity.workspaceMountIds() =
        when (this) {
            is ConversationRuntimeExecutorIdentity.Server -> emptySet()
            is ConversationRuntimeExecutorIdentity.Worker ->
                workspaceService.findMountsByWorker(identity.workerId.value).mapTo(mutableSetOf()) { it.id }
        }

    private enum class ExecutionReadiness {
        CONTINUE,
        WAIT,
        STOP,
    }

    private data class ScheduledWork(
        val conversationId: Conversation.Id,
        val taskId: ConversationRuntimeTask.Id,
    )

    private data class ActiveExecution(
        val taskId: ConversationRuntimeTask.Id,
        val job: Job,
    )

    private companion object {
        const val READY_WORK_BATCH_SIZE = 1_000
    }
}
