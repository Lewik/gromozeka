package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import com.gromozeka.domain.repository.ConversationHistoryRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import klog.KLoggers
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Protocol maintenance uses the existing application lifecycle; Worker execution stays in WorkerRequest. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class AsyncToolOperationService(
    private val operations: AsyncToolOperationRepository,
    private val admission: AsyncToolAdmissionService,
    private val outcomes: AsyncToolOutcomeService,
    private val executor: ParallelToolExecutor,
    private val workers: AsyncWorkerToolExecutionClient,
    private val history: ConversationHistoryRepository,
    private val conversations: ConversationDomainService,
    private val secrets: ToolSecretResolutionService,
    private val server: ConversationRuntimeExecutorDescriptor,
) {
    private val log = KLoggers.logger(this)
    private val started = AtomicBoolean(false)
    private data class ExecutionJob(val claim: AsyncToolDispatchReference, val job: Job)
    private val jobs = ConcurrentHashMap<AsyncToolOperation.Id, ExecutionJob>()
    private val owner get() = (server.identity as? ConversationRuntimeExecutorIdentity.Server)?.sessionId
        ?: error("Async orchestration must remain Server-owned")

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val handler = CoroutineExceptionHandler { _, error ->
            log.error { "Async tool maintenance stopped: ${error::class.simpleName}; durable operations retained" }
        }
        scope.launch(handler) {
            while (currentCoroutineContext().isActive) {
                try { reconcile(scope) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (database: java.sql.SQLException) {
                    log.error { "Async metadata maintenance could not reach storage; retained for retry (${database::class.simpleName})" }
                }
                delay(250)
            }
        }
    }

    suspend fun acknowledge(operationId: AsyncToolOperation.Id): Boolean {
        val operation = checkNotNull(operations.find(operationId)) { "Async source operation is missing" }
        if (operation.acknowledgedAt != null) return true
        val origin = operation.invocation.origin
        val message = history.message(origin.threadId, origin.acknowledgementMessageId)?.message ?: return false
        check(message.role == Conversation.Message.Role.USER && message.author == null && message.instructions.isEmpty()) {
            "Async source ACK must be a runtime-owned tool result, not a client submission"
        }
        val result = message.content.filterIsInstance<ContentItem.ToolResult>().singleOrNull { it.toolUseId == origin.sourceToolCallId }
            ?: error("Async source ACK message does not close its tool call")
        check(result.toolName == operation.invocation.modelToolName) { "Async source acknowledgement differs from its invocation" }
        if (result.isError) return false
        val receipt = result.result.filterIsInstance<ContentItem.ToolResult.Data.Text>().mapNotNull { data ->
            val value = runCatching { Json.parseToJsonElement(data.content) }.getOrNull() as? JsonObject
            value?.takeIf { it["status"] == JsonPrimitive("ACCEPTED") && it["operation_id"] == JsonPrimitive(operationId.value) }
        }
        check(receipt.size == 1) { "Async source tool result does not contain its durable receipt" }
        return operations.acknowledge(operationId, Clock.System.now())
    }

    internal suspend fun reconcile(scope: CoroutineScope) {
        val now = Clock.System.now()
        for (operation in operations.pending(Phase.ACCEPTED, 256)) {
            if (!acknowledge(operation.id)) {
                val conversation = conversations.findById(operation.invocation.origin.conversationId)
                if (conversation == null || conversation.currentThread != operation.invocation.origin.threadId) {
                    outcomes.finish(operation, Phase.CANCELLED, failureOutcome("SOURCE_ACK_UNAVAILABLE", "The originating branch is unavailable; execution never started."))
                }
            }
        }
        for (operation in operations.pending(Phase.ACKNOWLEDGED, 256)) {
            if (operation.cancelRequestedAt != null) {
                outcomes.finish(operation, Phase.CANCELLED, failureOutcome("CANCELLED_BEFORE_START", "Cancelled before execution; no effects were started."))
            } else if (jobs.size < 32) {
                operations.claim(operation.id, owner, now, now + 30.seconds)?.let { launch(scope, it) }
            }
        }
        for (operation in operations.pending(Phase.CLAIMED, 256) + operations.pending(Phase.RUNNING, 256)) {
            if (operation.invocation.target is ConversationRuntimeTaskTarget.Worker && operation.workerRequestCreatedAt != null) {
                if (operation.cancelRequestedAt != null) workers.cancel(operation)
                workers.reconcile(operation)
                continue
            }
            val execution = jobs[operation.id]
            if (execution != null) {
                if (execution.claim != operation.claimReference) {
                    execution.job.cancel(CancellationException("Async execution claim changed"))
                    continue
                }
                if (operation.cancelRequestedAt != null) execution.job.cancel(CancellationException("Async cancellation requested"))
                else operations.renewLease(operation, now + 30.seconds)
            } else if (operation.cancelRequestedAt != null && operation.startedAt == null) {
                outcomes.finish(operation, Phase.CANCELLED, failureOutcome("CANCELLED_BEFORE_START", "Cancelled before execution; no effects were started."))
            } else if (checkNotNull(operation.leaseDeadline) <= now) {
                if (operation.phase == Phase.CLAIMED && jobs.size < 32) {
                    operations.claim(operation.id, owner, now, now + 30.seconds)?.let { launch(scope, it) }
                } else if (operation.phase == Phase.RUNNING) {
                    outcomes.finish(operation, Phase.OUTCOME_UNKNOWN, failureOutcome("OUTCOME_UNKNOWN", "The running callback lost its executor. Its effects are unknown; it is not executed again."))
                }
            }
        }
    }

    val completionChanges get() = outcomes.changes

    suspend fun pendingDeliveryConversations(): Set<Conversation.Id> = operations.pendingDeliveryConversations()

    suspend fun pendingDelivery(conversationId: Conversation.Id): List<AsyncToolOperation> {
        val conversation = conversations.findById(conversationId) ?: return emptyList()
        return operations.pendingDelivery(conversationId, conversation.currentThread, 256).filter { outcomes.canDeliver(it) }
    }

    fun completionMessages(operation: AsyncToolOperation): List<Conversation.Message> = outcomes.completionMessages(operation)

    private fun launch(scope: CoroutineScope, operation: AsyncToolOperation) {
        val claim = checkNotNull(operation.claimReference)
        check(operation.phase == Phase.CLAIMED && claim.executorSessionId == owner) { "Async launch requires its own atomic claim grant" }
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(operation) }
        val execution = ExecutionJob(claim, job)
        val previous = jobs.putIfAbsent(operation.id, execution)
        if (previous != null) { job.cancel(); return }
        job.invokeOnCompletion { jobs.remove(operation.id, execution) }
        job.start()
    }

    private suspend fun execute(claimed: AsyncToolOperation) {
        try {
            val prepared = admission.prepare(claimed)
            val resolved = secrets.resolve(claimed.invocation.origin.actorUserId, listOf(prepared.call))
            when (claimed.invocation.target) {
                is ConversationRuntimeTaskTarget.Worker -> workers.submit(claimed, resolved)
                ConversationRuntimeTaskTarget.Server -> {
                    if (!operations.markStarted(claimed, Clock.System.now())) return
                    val running = checkNotNull(operations.find(claimed.id))
                    if (running.claimReference != claimed.claimReference || running.phase != Phase.RUNNING) return
                    val result = executor.executeParallel(listOf(prepared.call), prepared.context, null, server.identity,
                        ConversationRuntimeTaskTarget.Server, resolved,
                        preapprovedFingerprints = mapOf(prepared.call.id.value to running.invocation.contractFingerprint))
                    val item = result.results.single()
                    check(item.toolUseId == running.invocation.origin.sourceToolCallId) { "Async callback returned another invocation's outcome" }
                    outcomes.finish(running, if (item.isError) Phase.FAILED else Phase.SUCCEEDED,
                        AsyncToolOutcome(item.result, result.structuredContentByCallId[item.toolUseId.value], item.isError,
                            "TOOL_ERROR".takeIf { item.isError }, state = item.state, executionToolName = item.executionToolName))
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { failedExecution(claimed, true, "CANCELLATION_REQUESTED") }
            throw cancelled
        } catch (failure: Exception) {
            log.error { "Async tool dispatch failed: ${claimed.id.value} (${failure::class.simpleName}); no effect replay" }
            failedExecution(claimed, false,
                (failure as? AsyncToolScopeDeniedException)?.code ?: "DISPATCH_FAILED")
        }
    }

    private suspend fun failedExecution(claimed: AsyncToolOperation, cancelled: Boolean, code: String) {
        val operation = checkNotNull(operations.find(claimed.id)) { "Async operation disappeared during execution" }
        if (operation.phase.isTerminal || operation.claimReference != claimed.claimReference) return
        if (operation.invocation.target is ConversationRuntimeTaskTarget.Worker && operation.workerRequestCreatedAt != null) {
            if (cancelled) workers.cancel(operation)
            workers.reconcile(operation)
            return
        }
        val phase = when {
            operation.startedAt != null -> Phase.OUTCOME_UNKNOWN
            cancelled || operation.cancelRequestedAt != null -> Phase.CANCELLED
            else -> Phase.FAILED
        }
        outcomes.finish(operation, phase, failureOutcome(code, if (phase == Phase.OUTCOME_UNKNOWN)
            "The invocation may have produced effects. Its outcome is unknown; do not repeat it automatically."
        else "The invocation did not start: $code"))
    }

    private fun failureOutcome(code: String, message: String) = AsyncToolOutcome(
        content = listOf(ContentItem.ToolResult.Data.Text(message)), isError = true, failureCode = code)
}
