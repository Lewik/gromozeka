package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkConsumer
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.WorkspaceDomainService
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service

@Service
class ConversationRuntimeExecutor(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val runtimeWorkConsumer: ConversationRuntimeWorkConsumer,
    private val workspaceService: WorkspaceDomainService,
    private val aiConfigurationService: AiConfigurationService,
    private val taskRunnerProvider: ObjectProvider<ConversationRuntimeTaskRunner>,
    descriptor: ConversationRuntimeExecutorDescriptor,
    @Qualifier("applicationScope") private val parentScope: CoroutineScope,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val executor = descriptor.identity
    private val capabilities = descriptor.capabilities
    private val eventLeaseOwnerId = executor.leaseOwnerId()
    private val deliveryMutexes = Array(DELIVERY_MUTEX_STRIPES) { Mutex() }
    private val lifecycleLock = Any()

    @Volatile
    private var running = false
    private var runtimeJob: Job? = null
    private var deliveryCollectionJob: Job? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            val parentJob = parentScope.coroutineContext[Job]
            val executorJob = SupervisorJob(parentJob)
            val executorScope = CoroutineScope(parentScope.coroutineContext + executorJob)
            runtimeJob = executorJob
            deliveryCollectionJob = executorScope.launch {
                runtimeWorkConsumer.deliveries.collect { delivery ->
                    launch {
                        processRuntimeWorkDelivery(delivery)
                    }
                }
            }
            running = true
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
                deliveryCollectionJob?.cancelAndJoin()
                runtimeJob?.cancelAndJoin()
            }
            deliveryCollectionJob = null
            runtimeJob = null
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

    private suspend fun processRuntimeWorkDelivery(delivery: ConversationRuntimeWorkDelivery) {
        val item = delivery.item
        log.info {
            "Conversation runtime work item received: conversation=${item.conversationId.value} " +
                "reason=${item.reason} task=${item.taskId.value} executor=$executor"
        }
        deliveryMutex(item.conversationId).withLock {
            val preparation = try {
                when (item.reason) {
                    ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED -> prepareSubmittedTask(item)
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    settlePreparationFailure(delivery, error)
                }
                throw error
            } catch (error: Throwable) {
                log.error(error) {
                    "Conversation runtime work item preparation failed: conversation=${item.conversationId.value} " +
                        "reason=${item.reason} task=${item.taskId.value} executor=$executor error=${error.message}"
                }
                settlePreparationFailure(delivery, error)
                return
            }

            when (preparation) {
                DeliveryPreparation.Acknowledge -> delivery.acknowledge()
                DeliveryPreparation.Redeliver -> redeliverOrRejectUnclaimedDelivery(
                    delivery = delivery,
                    message = "Runtime work item was not claimable by its exact executor",
                    errorType = "WorkItemNotClaimable",
                )
                is DeliveryPreparation.Execute -> {
                    try {
                        delivery.acknowledge()
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            recordClaimedTaskDeliveryFailure(
                                task = preparation.task,
                                message =
                                    "RabbitMQ acknowledgement failed after durable task claim; execution was not started",
                                errorType = error::class.simpleName,
                            )
                        }
                        throw error
                    }
                    val executionStarted = try {
                        runtimeCoordinator.markActiveTaskStarted(
                            conversationId = preparation.task.conversationId,
                            taskId = preparation.task.id,
                            executor = executor,
                            startedAt = Clock.System.now(),
                        )
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            recordClaimedTaskDeliveryFailure(
                                task = preparation.task,
                                message =
                                    "Failed to record runtime task execution start after RabbitMQ acknowledgement",
                                errorType = error::class.simpleName,
                            )
                        }
                        throw error
                    }
                    if (!executionStarted) {
                        log.warn {
                            "Conversation runtime task ownership was lost before execution start: " +
                                "conversation=${preparation.task.conversationId.value} " +
                                "task=${preparation.task.id.value} executor=$executor"
                        }
                        return@withLock
                    }
                    publishRuntimeSnapshot(item.conversationId)
                    runClaimedTask(preparation.task)
                }
            }
        }
    }

    private suspend fun settlePreparationFailure(
        delivery: ConversationRuntimeWorkDelivery,
        error: Throwable,
    ) {
        val item = delivery.item
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
            delivery.acknowledge()
            return
        }
        redeliverOrRejectUnclaimedDelivery(
            delivery = delivery,
            message = error.message ?: "Unknown conversation runtime delivery error",
            errorType = error::class.simpleName,
        )
    }

    private suspend fun redeliverOrRejectUnclaimedDelivery(
        delivery: ConversationRuntimeWorkDelivery,
        message: String,
        errorType: String?,
    ) {
        if (!delivery.isFinalRedelivery) {
            delivery.redeliver()
            return
        }

        val item = delivery.item
        val incident = runtimeCoordinator.recordPendingTaskDeliveryFailure(
            conversationId = item.conversationId,
            taskId = item.taskId,
            executor = executor,
            message = message,
            errorType = errorType,
        )
        if (incident != null) {
            publishRuntimeSnapshot(item.conversationId)
            publishRuntimeEvent(
                ConversationRuntimeEvent.ExecutionFailed(
                    conversationId = item.conversationId,
                    message = message,
                    failureType = incident.kind.name,
                )
            )
        }
        delivery.reject()
    }

    private suspend fun prepareSubmittedTask(item: ConversationRuntimeWorkItem): DeliveryPreparation {
        when (awaitExecutionReadiness(item.conversationId)) {
            ExecutionReadiness.CONTINUE -> Unit
            ExecutionReadiness.RELEASE_FOR_LATER -> {
                runtimeCoordinator.releasePublishedWorkItem(item.conversationId, item.taskId)
                publishRuntimeSnapshot(item.conversationId)
                return DeliveryPreparation.Acknowledge
            }
            ExecutionReadiness.STOP -> {
                finishRuntimeIfIdle(item.conversationId)
                publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(item.conversationId))
                return DeliveryPreparation.Acknowledge
            }
        }

        val task = runtimeCoordinator.claimDeliveredTask(
            conversationId = item.conversationId,
            taskId = item.taskId,
            executor = executor,
            executorCapabilities = capabilities,
            workerWorkspaceMountIds = executor.workspaceMountIds(),
        )

        if (task == null) {
            val state = runtimeCoordinator.find(item.conversationId)
            if (state?.activeTaskId == item.taskId) {
                return if (state.activeTaskStartedAt == null) {
                    DeliveryPreparation.Redeliver
                } else {
                    DeliveryPreparation.Acknowledge
                }
            }
            val taskStillPending = runtimeCoordinator.listPending(item.conversationId).any { it.id == item.taskId }
            if (!taskStillPending) {
                if (finishRuntimeIfIdle(item.conversationId)) {
                    publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(item.conversationId))
                }
                return DeliveryPreparation.Acknowledge
            }
            if (state != null && state.controlState != ConversationExecutionState.ControlState.RUNNING) {
                runtimeCoordinator.releasePublishedWorkItem(item.conversationId, item.taskId)
                publishRuntimeSnapshot(item.conversationId)
                return DeliveryPreparation.Acknowledge
            }
            return DeliveryPreparation.Redeliver
        }

        aiConfigurationService.refreshIfChanged()
        return DeliveryPreparation.Execute(task)
    }

    private suspend fun runClaimedTask(task: ConversationRuntimeTask) = coroutineScope {
        val taskJob = currentCoroutineContext()[Job]
            ?: throw IllegalStateException("Conversation runtime task coroutine has no Job")
        val controlMonitor = launch {
            monitorActiveTask(task, taskJob)
        }
        try {
            taskRunnerProvider.getObject().runRuntimeTask(task, executor).collect { message ->
                publishRuntimeEvent(
                    ConversationRuntimeEvent.MessageEmitted(
                        conversationId = task.conversationId,
                        taskId = task.id,
                        message = message,
                    )
                )
            }
            if (!runtimeCoordinator.completeActiveTask(task.conversationId, task.id, executor)) {
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
            controlMonitor.cancel()
        }
    }

    private suspend fun monitorActiveTask(
        task: ConversationRuntimeTask,
        taskJob: Job,
    ) {
        while (taskJob.isActive) {
            delay(ConversationRuntimeTiming.controlPollIntervalMillis)
            val state = runtimeCoordinator.find(task.conversationId) ?: return
            if (state.activeTaskId != task.id || state.activeExecutor != executor) {
                taskJob.cancel(
                    CancellationException("Conversation runtime task ownership was lost: ${task.id.value}")
                )
                return
            }
            if (state.activeTaskStartedAt == null) {
                taskJob.cancel(
                    CancellationException("Conversation runtime task execution start was lost: ${task.id.value}")
                )
                return
            }
            if (state.controlState == ConversationExecutionState.ControlState.INTERRUPTING) {
                taskJob.cancel(CancellationException("Conversation runtime interrupted: ${task.conversationId.value}"))
                return
            }
        }
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

    private fun deliveryMutex(conversationId: Conversation.Id): Mutex {
        val index = (conversationId.value.hashCode().toLong() and Int.MAX_VALUE.toLong()).toInt() %
            deliveryMutexes.size
        return deliveryMutexes[index]
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
        if (publishLiveRuntimeEvent(event.withCursorSequence(logEntry.sequence))) {
            runtimeCoordinator.markEventLogEntryPublished(
                conversationId = logEntry.conversationId,
                sequence = logEntry.sequence,
                leaseOwnerId = eventLeaseOwnerId,
                publishedAt = Clock.System.now(),
            )
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
            ConversationExecutionState.ControlState.PAUSED -> ExecutionReadiness.RELEASE_FOR_LATER
            ConversationExecutionState.ControlState.PAUSE_REQUESTED -> {
                if (runtimeCoordinator.markPaused(conversationId)) {
                    publishRuntimeSnapshot(conversationId)
                }
                ExecutionReadiness.RELEASE_FOR_LATER
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

    private fun ConversationRuntimeExecutorIdentity.leaseOwnerId(): String =
        when (this) {
            is ConversationRuntimeExecutorIdentity.Server -> "server:${sessionId.value}"
            is ConversationRuntimeExecutorIdentity.Worker ->
                "worker:${identity.workerId.value}:${identity.sessionId.value}"
        }

    private enum class ExecutionReadiness {
        CONTINUE,
        RELEASE_FOR_LATER,
        STOP,
    }

    private sealed interface DeliveryPreparation {
        data object Acknowledge : DeliveryPreparation
        data object Redeliver : DeliveryPreparation
        data class Execute(val task: ConversationRuntimeTask) : DeliveryPreparation
    }

    private companion object {
        const val DELIVERY_MUTEX_STRIPES = 256
    }
}
