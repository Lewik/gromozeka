package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkConsumer
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class ConversationRuntimeWorker(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val runtimeWorkConsumer: ConversationRuntimeWorkConsumer,
    private val runtimeWorkerRegistry: ConversationRuntimeWorkerRegistry,
    private val taskRunnerProvider: ObjectProvider<ConversationRuntimeTaskRunner>,
    runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
    @Value("\${gromozeka.runtime.worker.version:dev}") private val workerVersion: String,
    @Value("\${gromozeka.runtime.worker.heartbeat-interval-millis:5000}")
    private val heartbeatIntervalMillis: Long = ConversationRuntimeTiming.workerHeartbeatIntervalMillis,
    @Qualifier("applicationScope") private val parentScope: CoroutineScope,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val startedAt = Clock.System.now()
    private val runtimeWorker = ConversationRuntimeWorkerIdentity(
        workerId = runtimeWorkerDescriptor.id,
        sessionId = ConversationRuntimeWorkerSessionId(uuid7()),
    )
    private val runtimeWorkerCapabilities = runtimeWorkerDescriptor.capabilities
    private val runtimeWorkerAffinities = runtimeWorkerDescriptor.affinities +
        ConversationRuntimeWorkerAffinity(
            kind = ConversationRuntimeWorkerAffinity.Kind.WORKER,
            value = runtimeWorker.workerId.value,
        )
    private val eventLeaseOwnerId = "worker:${runtimeWorker.workerId.value}:${runtimeWorker.sessionId.value}"
    private val deliveryMutexes = Array(DELIVERY_MUTEX_STRIPES) { Mutex() }
    private val lifecycleLock = Any()
    private val termination = CompletableDeferred<Throwable?>()

    @Volatile
    private var running = false
    private var runtimeJob: Job? = null
    private var deliveryCollectionJob: Job? = null
    private var heartbeatJob: Job? = null

    val identity: ConversationRuntimeWorkerIdentity
        get() = runtimeWorker

    val capabilities: Set<ConversationRuntimeWorkerCapability>
        get() = runtimeWorkerCapabilities

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) {
                return
            }
            check(!termination.isCompleted) {
                "Conversation runtime worker cannot restart after termination: $runtimeWorker"
            }
            require(heartbeatIntervalMillis > 0) {
                "Conversation runtime worker heartbeat interval must be positive"
            }
            runBlocking {
                val now = Clock.System.now()
                val registered = runtimeWorkerRegistry.register(
                    registration = ConversationRuntimeWorkerRegistration(
                        identity = runtimeWorker,
                        capabilities = runtimeWorkerCapabilities,
                        affinities = runtimeWorkerAffinities,
                        version = workerVersion,
                        startedAt = startedAt,
                        lastHeartbeatAt = now,
                    ),
                    staleBefore = now - ConversationRuntimeTiming.workerRegistrationStaleAfter,
                )
                check(registered) {
                    "Conversation runtime worker id is already owned by a live session: ${runtimeWorker.workerId.value}"
                }
            }

            val parentJob = parentScope.coroutineContext[Job]
            val workerJob = SupervisorJob(parentJob)
            val workerScope = CoroutineScope(parentScope.coroutineContext + workerJob)
            runtimeJob = workerJob
            deliveryCollectionJob = workerScope.launch {
                runtimeWorkConsumer.deliveries.collect { delivery ->
                    launch {
                        processRuntimeWorkDelivery(delivery)
                    }
                }
            }
            heartbeatJob = workerScope.launch {
                runHeartbeatLoop(workerJob)
            }
            running = true
            log.info {
                "Conversation runtime worker started: identity=$runtimeWorker " +
                    "capabilities=${runtimeWorkerCapabilities.joinToString()} " +
                    "affinities=${runtimeWorkerAffinities.joinToString()}"
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            if (!running && runtimeJob == null) {
                return
            }
            running = false
            runBlocking {
                deliveryCollectionJob?.cancelAndJoin()
                heartbeatJob?.cancelAndJoin()
                runtimeJob?.cancelAndJoin()
                runCatching {
                    runtimeWorkerRegistry.unregister(runtimeWorker, Clock.System.now())
                }.onFailure { error ->
                    log.warn(error) { "Failed to unregister conversation runtime worker: identity=$runtimeWorker" }
                }
            }
            termination.complete(null)
            deliveryCollectionJob = null
            heartbeatJob = null
            runtimeJob = null
            log.info { "Conversation runtime worker stopped: identity=$runtimeWorker" }
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

    override fun getPhase(): Int = 100

    suspend fun awaitTermination(): Throwable? = termination.await()

    private suspend fun runHeartbeatLoop(workerJob: Job) {
        try {
            while (workerJob.isActive) {
                delay(heartbeatIntervalMillis)
                val heartbeatAccepted = runtimeWorkerRegistry.heartbeat(runtimeWorker, Clock.System.now())
                if (!heartbeatAccepted) {
                    terminateAfterRegistrationLoss(
                        workerJob,
                        IllegalStateException("Conversation runtime worker session lost registration: $runtimeWorker"),
                    )
                    return
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            terminateAfterRegistrationLoss(workerJob, error)
        }
    }

    private fun terminateAfterRegistrationLoss(workerJob: Job, error: Throwable) {
        log.error(error) { "Conversation runtime worker registration failed: identity=$runtimeWorker" }
        running = false
        termination.complete(error)
        workerJob.cancel(
            CancellationException("Conversation runtime worker registration failed: $runtimeWorker").apply {
                initCause(error)
            }
        )
    }

    private suspend fun processRuntimeWorkDelivery(delivery: ConversationRuntimeWorkDelivery) {
        val item = delivery.item
        log.info {
            "Conversation runtime work item received: conversation=${item.conversationId.value} " +
                "reason=${item.reason} task=${item.taskId.value} worker=$runtimeWorker"
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
                        "reason=${item.reason} task=${item.taskId.value} worker=$runtimeWorker error=${error.message}"
                }
                settlePreparationFailure(delivery, error)
                return
            }

            when (preparation) {
                DeliveryPreparation.Acknowledge -> delivery.acknowledge()
                DeliveryPreparation.Redeliver -> redeliverOrRejectUnclaimedDelivery(
                    delivery = delivery,
                    message = "Runtime work item was not claimable by this worker",
                    errorType = "WorkItemNotClaimable",
                )
                is DeliveryPreparation.Execute -> {
                    try {
                        delivery.acknowledge()
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            recordClaimedTaskDeliveryFailure(
                                task = preparation.task,
                                message = "RabbitMQ acknowledgement failed after durable task claim; execution was not started",
                                errorType = error::class.simpleName,
                            )
                        }
                        throw error
                    }
                    val startedAt = Clock.System.now()
                    val executionStarted = try {
                        runtimeCoordinator.markActiveTaskStarted(
                            conversationId = preparation.task.conversationId,
                            taskId = preparation.task.id,
                            worker = runtimeWorker,
                            startedAt = startedAt,
                        )
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            recordClaimedTaskDeliveryFailure(
                                task = preparation.task,
                                message = "Failed to record runtime task execution start after RabbitMQ acknowledgement",
                                errorType = error::class.simpleName,
                            )
                        }
                        throw error
                    }
                    if (!executionStarted) {
                        log.warn {
                            "Conversation runtime task ownership was lost before execution start: " +
                                "conversation=${preparation.task.conversationId.value} " +
                                "task=${preparation.task.id.value} worker=$runtimeWorker"
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
            ?.takeIf { it.id == item.taskId && snapshot.state?.activeWorker == runtimeWorker }
        if (claimedTask != null) {
            if (snapshot.state?.activeTaskStartedAt == null) {
                recordClaimedTaskDeliveryFailure(
                    task = claimedTask,
                    message = "Worker stopped after durable task claim but before execution started",
                    errorType = error::class.simpleName,
                )
            } else {
                recordClaimedTaskIncident(
                    task = claimedTask,
                    message = "Worker stopped while task execution was active; the task outcome is unknown",
                    errorType = error::class.simpleName,
                )
            }
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
            worker = runtimeWorker,
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
            worker = runtimeWorker,
            workerCapabilities = runtimeWorkerCapabilities,
            workerAffinities = runtimeWorkerAffinities,
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

        return DeliveryPreparation.Execute(task)
    }

    private suspend fun runClaimedTask(task: ConversationRuntimeTask) = coroutineScope {
        val taskJob = currentCoroutineContext()[Job]
            ?: throw IllegalStateException("Conversation runtime task coroutine has no Job")
        val controlMonitor = launch {
            monitorActiveTask(task, taskJob)
        }
        try {
            taskRunnerProvider.getObject().runRuntimeTask(task, runtimeWorker).collect { message ->
                publishRuntimeEvent(
                    ConversationRuntimeEvent.MessageEmitted(
                        conversationId = task.conversationId,
                        taskId = task.id,
                        message = message,
                    )
                )
            }
            if (!runtimeCoordinator.completeActiveTask(task.conversationId, task.id, runtimeWorker)) {
                throw IllegalStateException(
                    "Conversation runtime task ownership was lost before completion: " +
                        "conversation=${task.conversationId.value} task=${task.id.value} worker=$runtimeWorker"
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
                    state.activeWorker == runtimeWorker &&
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
                ) {
                    runtimeCoordinator.abort(task.conversationId)
                    publishRuntimeSnapshot(task.conversationId)
                    publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(task.conversationId))
                    true
                } else {
                    recordClaimedTaskIncident(
                        task = task,
                        message = "Worker stopped while task execution was active; the task outcome is unknown",
                        errorType = "WorkerExecutionCancelled",
                    )
                    false
                }
            }
            if (!interrupted) {
                throw error
            }
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
            if (state.activeTaskId != task.id || state.activeWorker != runtimeWorker) {
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
            worker = runtimeWorker,
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
            worker = runtimeWorker,
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
        if (finished) {
            publishRuntimeSnapshot(conversationId)
        }
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
