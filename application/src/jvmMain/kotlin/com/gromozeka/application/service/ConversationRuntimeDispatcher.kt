package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkQueue
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class ConversationRuntimeDispatcher(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val runtimeWorkQueue: ConversationRuntimeWorkQueue,
    private val taskRunnerProvider: ObjectProvider<ConversationRuntimeTaskRunner>,
    runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
    @Qualifier("supervisorScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val runtimeWorkerId = runtimeWorkerDescriptor.id?.takeIf { it.isNotBlank() } ?: "conversation-runtime-${uuid7()}"
    private val runtimeWorkerCapabilities = runtimeWorkerDescriptor.capabilities
    private val runtimeWorkerAffinities = runtimeWorkerDescriptor.affinities
    private val activeDeliveryJobsLock = Any()
    private val activeDeliveryJobsByConversation = mutableMapOf<Conversation.Id, Job>()

    init {
        log.info {
            "Conversation runtime worker started: id=$runtimeWorkerId " +
                "capabilities=${runtimeWorkerCapabilities.joinToString()} affinities=${runtimeWorkerAffinities.joinToString()}"
        }
        coroutineScope.launch {
            runtimeWorkQueue.deliveries.collect { delivery ->
                launch {
                    processRuntimeWorkDelivery(delivery)
                }
            }
        }
        launchRuntimeLoop("work-outbox-publish", ConversationRuntimeTiming.workOutboxScanIntervalMillis) {
            publishRuntimeWorkOutbox()
        }
        launchRuntimeLoop("event-outbox-publish", ConversationRuntimeTiming.eventOutboxScanIntervalMillis) {
            publishRuntimeOutbox()
        }
    }

    suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): Boolean {
        val state = runtimeCoordinator.find(conversationId)
        val pendingTasks = runtimeCoordinator.listPending(conversationId)
        val effectivePlacement = if (placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && state?.activeTaskId == null) {
            QueuedMessagePlacement.END_OF_TURN
        } else {
            placement
        }
        val canAcceptQueuedTask = state == null ||
            state.controlState == ConversationExecutionState.ControlState.RUNNING ||
            state.controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED ||
            state.controlState == ConversationExecutionState.ControlState.PAUSED
        val canQueue = when (effectivePlacement) {
            QueuedMessagePlacement.AFTER_TOOL_RESULT -> canAcceptQueuedTask && state?.activeTaskId != null
            QueuedMessagePlacement.END_OF_TURN ->
                canAcceptQueuedTask &&
                    (
                        placement == QueuedMessagePlacement.AFTER_TOOL_RESULT ||
                            state != null ||
                            pendingTasks.any { it.placement == QueuedMessagePlacement.END_OF_TURN }
                    )
        }
        if (!canQueue) {
            log.info {
                "Rejected queued message without active runtime: conversation=${conversationId.value} " +
                    "message=${userMessage.id.value} placement=$placement"
            }
            return false
        }

        val task = queuedRuntimeTask(conversationId, userMessage, agent, effectivePlacement)
        val accepted = submitRuntimeTask(task)
        if (accepted) {
            log.info {
                "Queued runtime message: conversation=${conversationId.value} message=${userMessage.id.value} " +
                    "placement=$effectivePlacement requestedPlacement=$placement"
            }
        }
        return accepted
    }

    suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean {
        val removed = runtimeCoordinator.cancelByMessageId(conversationId, messageId)
        if (removed) {
            publishRuntimeSnapshot(conversationId)
            log.info { "Cancelled runtime queued message: conversation=${conversationId.value} message=${messageId.value}" }
        }
        return removed
    }

    suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean {
        val accepted = when (action) {
            ConversationRuntimeControlAction.PAUSE -> runtimeCoordinator.requestPause(conversationId)
            ConversationRuntimeControlAction.RESUME -> runtimeCoordinator.requestResume(conversationId)
            ConversationRuntimeControlAction.STOP -> runtimeCoordinator.requestStop(conversationId)
            ConversationRuntimeControlAction.INTERRUPT -> {
                val requested = runtimeCoordinator.requestInterrupt(conversationId)
                val cancelled = cancelActiveDeliveryJob(conversationId)
                cancelled || requested
            }
        }
        if (accepted) {
            publishRuntimeSnapshot(conversationId)
            publishRuntimeWorkOutbox()
            log.info { "Runtime execution control accepted: conversation=${conversationId.value} action=$action" }
        } else {
            log.info { "Runtime execution control ignored without active turn: conversation=${conversationId.value} action=$action" }
        }
        return accepted
    }

    suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Boolean {
        val task = queuedRuntimeTask(conversationId, userMessage, agent, QueuedMessagePlacement.END_OF_TURN)
        return submitRuntimeTask(task)
    }

    fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long? = null,
    ): Flow<ConversationRuntimeEvent> = flow {
        val subscription = runtimeEventBus.subscribe(conversationId)
        try {
            var emittedEventSequence = afterEventSequence ?: 0L
            replayRuntimeEvents(conversationId, afterEventSequence) { event ->
                event.cursorSequence?.let { emittedEventSequence = maxOf(emittedEventSequence, it) }
                emit(event)
            }
            emit(runtimeSnapshotEvent(conversationId))
            subscription.events.collect { event ->
                val cursorSequence = event.cursorSequence
                if (cursorSequence != null && cursorSequence <= emittedEventSequence && event !is ConversationRuntimeEvent.SnapshotUpdated) {
                    return@collect
                }
                cursorSequence?.let { emittedEventSequence = maxOf(emittedEventSequence, it) }
                emit(event)
            }
        } finally {
            subscription.close()
        }
    }

    suspend fun submitContinuationTask(task: ConversationRuntimeTask) {
        val accepted = submitRuntimeTask(task)
        if (!accepted) {
            throw IllegalStateException("Conversation runtime rejected continuation task: ${task.id.value}")
        }
    }

    private suspend fun submitRuntimeTask(task: ConversationRuntimeTask): Boolean {
        val accepted = runtimeCoordinator.submit(task)
        if (accepted) {
            publishRuntimeSnapshot(task.conversationId)
            publishRuntimeWorkOutbox()
        }
        return accepted
    }

    private fun launchRuntimeLoop(
        name: String,
        intervalMillis: Long,
        block: suspend () -> Unit,
    ) {
        coroutineScope.launch {
            while (true) {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.error(error) { "Conversation runtime background loop failed: name=$name error=${error.message}" }
                }
                delay(intervalMillis)
            }
        }
    }

    private suspend fun processRuntimeWorkDelivery(delivery: ConversationRuntimeWorkDelivery) {
        val item = delivery.item
        log.info {
            "Conversation runtime work item received: conversation=${item.conversationId.value} " +
                "reason=${item.reason} task=${item.taskId.value}"
        }
        runCatching {
            when (item.reason) {
                ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED -> handleTaskSubmitted(item)
            }
        }.onSuccess { ack ->
            if (ack) {
                delivery.ack()
            } else {
                retryOrFailDelivery(
                    delivery = delivery,
                    message = "Runtime work item was not accepted by this worker",
                    type = "WorkItemNotAccepted",
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                delivery.ack()
                return
            }
            log.error(error) {
                "Conversation runtime work item failed: conversation=${item.conversationId.value} " +
                    "reason=${item.reason} task=${item.taskId.value} error=${error.message}"
            }
            retryOrFailDelivery(
                delivery = delivery,
                message = error.message ?: "Unknown conversation runtime delivery error",
                type = error::class.simpleName,
            )
        }
    }

    private suspend fun retryOrFailDelivery(
        delivery: ConversationRuntimeWorkDelivery,
        message: String,
        type: String?,
    ) {
        if (!delivery.isFinalRetry) {
            delivery.retry()
            return
        }

        val item = delivery.item
        val failureRecorded = runtimeCoordinator.failPendingTask(
            conversationId = item.conversationId,
            taskId = item.taskId,
            workerId = runtimeWorkerId,
            message = message,
            type = type,
        )
        if (failureRecorded) {
            publishRuntimeSnapshot(item.conversationId)
            publishRuntimeEvent(
                ConversationRuntimeEvent.ExecutionFailed(
                    conversationId = item.conversationId,
                    message = message,
                    failureType = type,
                )
            )
        }
        delivery.fail()
    }

    private suspend fun handleTaskSubmitted(item: ConversationRuntimeWorkItem): Boolean {
        val taskId = item.taskId
        val job = currentCoroutineContext()[Job]
            ?: throw IllegalStateException("Conversation runtime delivery coroutine has no Job")
        if (!registerActiveDeliveryJob(item.conversationId, job)) {
            return false
        }

        try {
            when (awaitExecutionReadiness(item.conversationId)) {
                ExecutionReadiness.CONTINUE -> Unit
                ExecutionReadiness.RELEASE_FOR_LATER -> {
                    runtimeCoordinator.releasePublishedWorkItem(item.conversationId, item.taskId)
                    publishRuntimeSnapshot(item.conversationId)
                    return true
                }
                ExecutionReadiness.STOP -> {
                    finishRuntimeIfIdle(item.conversationId)
                    publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(item.conversationId))
                    return true
                }
            }

            val task = runtimeCoordinator.claimDeliveredTask(
                conversationId = item.conversationId,
                taskId = taskId,
                workerId = runtimeWorkerId,
                workerCapabilities = runtimeWorkerCapabilities,
                workerAffinities = runtimeWorkerAffinities,
            )
            publishRuntimeSnapshot(item.conversationId)

            if (task == null) {
                val taskStillPending = runtimeCoordinator.listPending(item.conversationId).any { it.id == taskId }
                if (!taskStillPending) {
                    if (finishRuntimeIfIdle(item.conversationId)) {
                        publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(item.conversationId))
                    }
                    return true
                }
                val state = runtimeCoordinator.find(item.conversationId)
                if (state != null && state.controlState != ConversationExecutionState.ControlState.RUNNING) {
                    runtimeCoordinator.releasePublishedWorkItem(item.conversationId, item.taskId)
                    publishRuntimeSnapshot(item.conversationId)
                    return true
                }
                return false
            }

            runClaimedTask(task)
            return true
        } finally {
            unregisterActiveDeliveryJob(item.conversationId, job)
        }
    }

    private suspend fun runClaimedTask(task: ConversationRuntimeTask) = coroutineScope {
        val taskJob = currentCoroutineContext()[Job]
            ?: throw IllegalStateException("Conversation runtime task coroutine has no Job")
        val controlMonitor = launch {
            monitorActiveTaskControl(task, taskJob)
        }
        try {
            taskRunnerProvider.getObject().runRuntimeTask(task, runtimeWorkerId).collect { message ->
                publishRuntimeEvent(
                    ConversationRuntimeEvent.MessageEmitted(
                        conversationId = task.conversationId,
                        taskId = task.id,
                        message = message,
                    )
                )
            }
            if (!runtimeCoordinator.completeActiveTask(task.conversationId, task.id, runtimeWorkerId)) {
                throw IllegalStateException(
                    "Conversation runtime task ownership was lost before completion: " +
                        "conversation=${task.conversationId.value} task=${task.id.value} worker=$runtimeWorkerId"
                )
            }
            publishRuntimeSnapshot(task.conversationId)
            publishRuntimeWorkOutbox()

            if (finishRuntimeIfIdle(task.conversationId)) {
                publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(task.conversationId))
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runtimeCoordinator.abort(task.conversationId)
                publishRuntimeSnapshot(task.conversationId)
                publishRuntimeEvent(ConversationRuntimeEvent.ExecutionCompleted(task.conversationId))
            }
        } catch (error: Throwable) {
            runtimeCoordinator.failActiveTask(
                conversationId = task.conversationId,
                taskId = task.id,
                workerId = runtimeWorkerId,
                message = error.message ?: "Unknown conversation runtime error",
                type = error::class.simpleName,
            )
            publishRuntimeSnapshot(task.conversationId)
            publishRuntimeEvent(
                ConversationRuntimeEvent.ExecutionFailed(
                    conversationId = task.conversationId,
                    message = error.message ?: "Unknown conversation runtime error",
                    failureType = error::class.simpleName,
                )
            )
        }
        finally {
            controlMonitor.cancel()
        }
    }

    private suspend fun monitorActiveTaskControl(
        task: ConversationRuntimeTask,
        taskJob: Job,
    ) {
        while (taskJob.isActive) {
            delay(ConversationRuntimeTiming.controlPollIntervalMillis)
            val state = runtimeCoordinator.find(task.conversationId) ?: return
            if (state.activeTaskId == task.id &&
                state.activeWorkerId == runtimeWorkerId &&
                state.controlState == ConversationExecutionState.ControlState.INTERRUPTING
            ) {
                taskJob.cancel(CancellationException("Conversation runtime interrupted: ${task.conversationId.value}"))
                return
            }
        }
    }

    private fun registerActiveDeliveryJob(conversationId: Conversation.Id, job: Job): Boolean =
        synchronized(activeDeliveryJobsLock) {
            val existingJob = activeDeliveryJobsByConversation[conversationId]
            if (existingJob != null && existingJob.isActive && existingJob != job) {
                return@synchronized false
            }
            activeDeliveryJobsByConversation[conversationId] = job
            true
        }

    private fun unregisterActiveDeliveryJob(conversationId: Conversation.Id, job: Job) {
        synchronized(activeDeliveryJobsLock) {
            if (activeDeliveryJobsByConversation[conversationId] == job) {
                activeDeliveryJobsByConversation.remove(conversationId)
            }
        }
    }

    private fun cancelActiveDeliveryJob(conversationId: Conversation.Id): Boolean {
        val job = synchronized(activeDeliveryJobsLock) {
            activeDeliveryJobsByConversation[conversationId]
        } ?: return false
        if (!job.isActive) {
            return false
        }
        job.cancel(CancellationException("Conversation runtime interrupted: ${conversationId.value}"))
        return true
    }

    private suspend fun finishRuntimeIfIdle(conversationId: Conversation.Id): Boolean {
        val finished = runtimeCoordinator.finishIfIdle(conversationId)
        if (finished) {
            publishRuntimeSnapshot(conversationId)
        }
        return finished
    }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        publishLiveRuntimeEvent(runtimeSnapshotEvent(conversationId))
    }

    private suspend fun publishRuntimeEvent(event: ConversationRuntimeEvent) {
        val logEntry = runtimeCoordinator.recordEvent(event)
        if (publishLiveRuntimeEvent(event.withCursorSequence(logEntry.sequence))) {
            runtimeCoordinator.markEventLogEntryPublished(
                conversationId = logEntry.conversationId,
                sequence = logEntry.sequence,
                workerId = runtimeWorkerId,
                publishedAt = Clock.System.now(),
            )
        }
    }

    private suspend fun publishRuntimeOutbox() {
        val now = Clock.System.now()
        val entries = runtimeCoordinator.claimUnpublishedEventLogEntries(
            workerId = runtimeWorkerId,
            now = now,
            leaseUntil = now + ConversationRuntimeTiming.eventPublishLeaseDuration,
            limit = EVENT_OUTBOX_BATCH_SIZE,
        )
        entries.forEach { entry ->
            if (publishLiveRuntimeEvent(entry.event.withCursorSequence(entry.sequence))) {
                runtimeCoordinator.markEventLogEntryPublished(
                    conversationId = entry.conversationId,
                    sequence = entry.sequence,
                    workerId = runtimeWorkerId,
                    publishedAt = Clock.System.now(),
                )
            }
        }
    }

    private suspend fun publishRuntimeWorkOutbox() {
        val now = Clock.System.now()
        val entries = runtimeCoordinator.claimUnpublishedWorkItems(
            workerId = runtimeWorkerId,
            now = now,
            leaseUntil = now + ConversationRuntimeTiming.workPublishLeaseDuration,
            limit = WORK_OUTBOX_BATCH_SIZE,
        )
        entries.forEach { entry ->
            try {
                runtimeWorkQueue.submit(entry.item)
                runtimeCoordinator.markWorkItemPublished(
                    conversationId = entry.item.conversationId,
                    sequence = entry.sequence,
                    workerId = runtimeWorkerId,
                    publishedAt = Clock.System.now(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn(error) {
                    "Failed to publish runtime work item; durable outbox will retry after lease expires: " +
                        "conversation=${entry.item.conversationId.value} task=${entry.item.taskId.value} " +
                        "sequence=${entry.sequence} error=${error.message}"
                }
            }
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

    private suspend fun runtimeSnapshotEvent(conversationId: Conversation.Id): ConversationRuntimeEvent.SnapshotUpdated =
        ConversationRuntimeEvent.SnapshotUpdated(
            conversationId = conversationId,
            snapshot = runtimeCoordinator.snapshot(conversationId),
        )

    private suspend fun replayRuntimeEvents(
        conversationId: Conversation.Id,
        afterEventSequence: Long?,
        emitEvent: suspend (ConversationRuntimeEvent) -> Unit,
    ) {
        var cursor = afterEventSequence
        while (true) {
            val entries = runtimeCoordinator.listEventLogEntries(
                conversationId = conversationId,
                afterSequence = cursor,
                limit = EVENT_REPLAY_BATCH_SIZE,
            )
            if (entries.isEmpty()) {
                return
            }
            entries.forEach { entry ->
                emitEvent(entry.event.withCursorSequence(entry.sequence))
            }
            cursor = entries.last().sequence
            if (entries.size < EVENT_REPLAY_BATCH_SIZE) {
                return
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

    private fun queuedRuntimeTask(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(userMessage.id.value),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.UserTurn(
                userMessage = userMessage,
                agent = agent,
            ),
            placement = placement,
            idempotencyKey = "conversation:${conversationId.value}:message:${userMessage.id.value}",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )

    private companion object {
        const val EVENT_REPLAY_BATCH_SIZE = 1_000
        const val EVENT_OUTBOX_BATCH_SIZE = 1_000
        const val WORK_OUTBOX_BATCH_SIZE = 1_000
    }

    private enum class ExecutionReadiness {
        CONTINUE,
        RELEASE_FOR_LATER,
        STOP,
    }
}
