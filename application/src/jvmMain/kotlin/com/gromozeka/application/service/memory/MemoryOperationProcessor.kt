package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.MemoryRunLifecycleEvent
import com.gromozeka.domain.service.MemoryRunLifecycleEventPublisher
import java.util.concurrent.atomic.AtomicReference
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MemoryOperationProcessor(
    private val executor: MemoryOperationExecutor,
    private val operationQueue: MemoryOperationQueue,
    private val memoryStore: MemoryStore,
    runtimeExecutorDescriptor: ConversationRuntimeExecutorDescriptor,
    private val lifecycleEventPublisher: MemoryRunLifecycleEventPublisher,
) {
    private val log = KLoggers.logger(this)
    private val operationJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "requestType"
    }
    private val runtimeExecutor = runtimeExecutorDescriptor.identity
    private val runtimeCapabilities = runtimeExecutorDescriptor.capabilities

    init {
        require(runtimeExecutor is ConversationRuntimeExecutorIdentity.Server) {
            "Memory operation orchestration must run on Server"
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (ConversationRuntimeCapability.MEMORY_PIPELINE !in runtimeCapabilities) {
            return
        }
        operationQueue.start(
            jobSource = { discoverJobs(runtimeExecutor) },
            processor = { job -> process(job, runtimeExecutor) },
        )
    }

    private suspend fun discoverJobs(
        runtimeExecutor: ConversationRuntimeExecutorIdentity,
    ): List<MemoryOperationJob> {
        val now = Clock.System.now()
        val unfinishedRuns = memoryStore.findRunsByStatuses(
            statuses = setOf(MemoryRun.Status.QUEUED, MemoryRun.Status.RUNNING),
            runTypes = MEMORY_OPERATION_RUN_TYPES,
        )
        return unfinishedRuns.mapNotNull { run ->
            if (run.status == MemoryRun.Status.RUNNING) {
                val lease = run.executionLease
                val previousSessionWasReplaced =
                    lease?.ownerId == runtimeExecutor.ownerId() &&
                        lease.ownerSessionId != runtimeExecutor.sessionId()
                val leaseExpired = lease == null || lease.expiresAt <= now
                if (previousSessionWasReplaced || leaseExpired) {
                    if (failUnrecoverableRun(
                            run = run,
                            summary = "Memory operation was interrupted by Server shutdown",
                            reason = "The owning Server stopped before completion; the operation was not retried to avoid duplicate memory writes.",
                        )
                    ) {
                        log.warn {
                            "Failed interrupted memory operation without retry: " +
                                "run=${run.id.value} type=${run.runType} owner=${lease?.ownerId}/${lease?.ownerSessionId}"
                        }
                    }
                }
                return@mapNotNull null
            }
            val encodedRequest = run.metadata[MEMORY_OPERATION_REQUEST_METADATA_KEY]
            if (encodedRequest == null) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation cannot be resumed",
                    reason = "Persisted memory operation request is missing.",
                )
                log.warn { "Failed queued memory operation without a request: run=${run.id.value} type=${run.runType}" }
                return@mapNotNull null
            }
            val request = try {
                operationJson.decodeFromJsonElement<MemoryOperationRequest>(encodedRequest)
            } catch (error: Throwable) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation cannot be resumed",
                    reason = error.message ?: "Persisted memory operation request is invalid.",
                )
                log.warn(error) { "Failed queued memory operation with an invalid request: run=${run.id.value}" }
                return@mapNotNull null
            }
            val requestValidationError = runCatching { request.validate() }
                .exceptionOrNull()
                ?.message
            val validationError = when {
                requestValidationError != null -> requestValidationError
                request.namespace != run.namespace ->
                    "Persisted operation namespace ${request.namespace.value} does not match run namespace ${run.namespace.value}."
                request.runType != run.runType ->
                    "Persisted operation type ${request.runType} does not match run type ${run.runType}."
                else -> null
            }
            if (validationError != null) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation cannot be resumed",
                    reason = validationError,
                )
                log.warn { "Failed inconsistent queued memory operation: run=${run.id.value} reason=$validationError" }
                return@mapNotNull null
            }
            MemoryOperationJob(run.id, request.kind, run.namespace)
        }
    }

    private suspend fun failUnrecoverableRun(
        run: MemoryRun,
        summary: String,
        reason: String,
    ): Boolean {
        val completedAt = Clock.System.now()
        val failedRun = run.failed(
            summary = summary,
            reason = reason,
            completedAt = completedAt,
        )
        val replaced = memoryStore.replaceRunIfUnchanged(
            expected = run,
            replacement = failedRun,
        )
        if (replaced) publishLifecycleEvent(failedRun)
        return replaced
    }

    private suspend fun process(
        job: MemoryOperationJob,
        runtimeExecutor: ConversationRuntimeExecutorIdentity,
    ) = coroutineScope {
        val storedRun = memoryStore.findRunById(job.runId)
            ?: throw IllegalStateException("Queued memory operation run not found: ${job.runId.value}")
        if (storedRun.status != MemoryRun.Status.QUEUED) return@coroutineScope
        val encodedRequest = storedRun.metadata[MEMORY_OPERATION_REQUEST_METADATA_KEY]
            ?: throw IllegalStateException("Memory operation request is missing: ${job.runId.value}")
        val request = operationJson.decodeFromJsonElement<MemoryOperationRequest>(encodedRequest)
        request.validate()
        require(request.kind == job.operation) {
            "Memory operation kind mismatch: queued=${job.operation.wireName} persisted=${request.kind.wireName}"
        }
        require(request.namespace == storedRun.namespace) {
            "Memory operation namespace mismatch: request=${request.namespace.value} run=${storedRun.namespace.value}"
        }
        require(request.runType == storedRun.runType) {
            "Memory operation type mismatch: request=${request.runType} run=${storedRun.runType}"
        }

        val startedAt = Clock.System.now()
        val claimedRun = storedRun.copy(
            status = MemoryRun.Status.RUNNING,
            summary = "${request.kind.displayName()} running",
            executionLease = runtimeExecutor.memoryExecutionLease(startedAt),
            startedAt = startedAt,
            completedAt = null,
            errorText = null,
        )
        if (!memoryStore.replaceRunIfUnchanged(storedRun, claimedRun)) {
            return@coroutineScope
        }
        publishLifecycleEvent(claimedRun)

        val currentRun = AtomicReference(claimedRun)
        val runUpdateMutex = Mutex()
        suspend fun updateOwnedRun(transform: (MemoryRun) -> MemoryRun): MemoryRun =
            runUpdateMutex.withLock {
                val expected = currentRun.get()
                val replacement = transform(expected)
                check(memoryStore.replaceRunIfUnchanged(expected, replacement)) {
                    "Memory operation execution ownership was lost: run=${job.runId.value}"
                }
                currentRun.set(replacement)
                replacement
            }

        val leaseHeartbeat = launch {
            while (currentCoroutineContext().isActive) {
                delay(MEMORY_OPERATION_LEASE_RENEW_INTERVAL_MILLIS)
                updateOwnedRun { current ->
                    current.copy(executionLease = runtimeExecutor.memoryExecutionLease(Clock.System.now()))
                }
            }
        }
        try {
            val execution = executor.execute(claimedRun, request) { update ->
                val updatedRun = updateOwnedRun { current ->
                    current.copy(
                        summary = update.summary,
                        sourceIds = (current.sourceIds + update.sourceIds).distinct(),
                        childRunIds = (current.childRunIds + update.childRunIds).distinct(),
                        progress = update.progress,
                        inputHash = update.inputHash ?: current.inputHash,
                        output = update.output ?: current.output,
                        errorText = update.errorText ?: current.errorText,
                    )
                }
                publishLifecycleEvent(updatedRun)
            }
            leaseHeartbeat.cancelAndJoin()
            val completedAt = Clock.System.now()
            val expected = currentRun.get()
            val completedRun = expected.complete(execution, startedAt, completedAt)
                .copy(executionLease = null)
            check(memoryStore.replaceRunIfUnchanged(expected, completedRun)) {
                "Memory operation execution ownership was lost before completion: run=${job.runId.value}"
            }
            publishLifecycleEvent(completedRun)
            log.info {
                "Memory operation completed: run=${job.runId.value} operation=${request.kind.wireName} " +
                    "status=${execution.status} latencyMs=${completedRun.latencyMs}"
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            leaseHeartbeat.cancelAndJoin()
            val completedAt = Clock.System.now()
            val expected = currentRun.get()
            val failedRun = expected.failed(
                summary = "${job.operation.displayName()} failed",
                reason = error.message ?: "Memory operation failed.",
                completedAt = completedAt,
            )
            val replaced = memoryStore.replaceRunIfUnchanged(
                expected = expected,
                replacement = failedRun,
            )
            if (replaced) publishLifecycleEvent(failedRun)
            throw error
        }
    }

    private suspend fun publishLifecycleEvent(run: MemoryRun) {
        runCatching {
            lifecycleEventPublisher.publish(
                MemoryRunLifecycleEvent(
                    runId = run.id,
                    status = run.status,
                    occurredAt = Clock.System.now(),
                )
            )
        }.onFailure { error ->
            log.warn(error) {
                "Memory lifecycle event publish failed; server reconciliation will recover it: " +
                    "run=${run.id.value} status=${run.status} error=${error.message}"
            }
        }
    }

    private fun MemoryRun.failed(
        summary: String,
        reason: String,
        completedAt: Instant,
    ): MemoryRun {
        val currentProgress = progress ?: MemoryRun.Progress(totalUnits = 1)
        return copy(
            status = MemoryRun.Status.FAILED,
            summary = summary,
            executionLease = null,
            progress = currentProgress.copy(
                totalUnits = maxOf(currentProgress.totalUnits, 1),
                completedUnits = maxOf(currentProgress.completedUnits, 1),
                failedUnits = maxOf(currentProgress.failedUnits, 1),
            ),
            errorText = reason,
            output = operationJson.parseToJsonElement(MemoryToolResultRenderer.failureJsonString(reason)),
            latencyMs = startedAt?.let { started ->
                completedAt.toEpochMilliseconds() - started.toEpochMilliseconds()
            },
            completedAt = completedAt,
        )
    }

    private fun MemoryOperationKind.displayName(): String =
        when (this) {
            MemoryOperationKind.REMEMBER -> "Memory remember"
            MemoryOperationKind.FORGET_SOURCE -> "Memory source forget"
            MemoryOperationKind.ENRICH_CONTEXT -> "Memory context enrichment"
            MemoryOperationKind.ANSWER_QUESTION -> "Memory question answering"
            MemoryOperationKind.MAINTENANCE -> "Memory maintenance"
        }

    private fun ConversationRuntimeExecutorIdentity.memoryExecutionLease(now: Instant): MemoryRun.ExecutionLease =
        MemoryRun.ExecutionLease(
            ownerId = ownerId(),
            ownerSessionId = sessionId(),
            expiresAt = Instant.fromEpochMilliseconds(
                now.toEpochMilliseconds() + MEMORY_OPERATION_LEASE_DURATION_MILLIS
            ),
        )

    private fun ConversationRuntimeExecutorIdentity.ownerId(): String =
        when (this) {
            is ConversationRuntimeExecutorIdentity.Server -> "server"
            is ConversationRuntimeExecutorIdentity.Worker -> identity.workerId.value
        }

    private fun ConversationRuntimeExecutorIdentity.sessionId(): String =
        when (this) {
            is ConversationRuntimeExecutorIdentity.Server -> sessionId.value
            is ConversationRuntimeExecutorIdentity.Worker -> identity.sessionId.value
        }

    private companion object {
        const val MEMORY_OPERATION_LEASE_DURATION_MILLIS = 30 * 60 * 1_000L
        const val MEMORY_OPERATION_LEASE_RENEW_INTERVAL_MILLIS = 60 * 1_000L
    }
}
