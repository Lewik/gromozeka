package com.gromozeka.server

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.WorkerRequestDelivery
import com.gromozeka.domain.service.WorkerRequestPolicy
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours
import klog.KLoggers

class WorkerRequestPendingException(val requestId: String) : IllegalStateException(
    "Worker request $requestId is still pending. Waiting ended, but the request was not cancelled. Query grz_worker_request_get or cancel it explicitly.",
)

class WorkerRequestResultUnavailableException(val requestId: String) : IllegalStateException(
    "Worker request $requestId is unknown or its result is no longer retained. Do not resubmit the action to retrieve it.",
)

/** Delivery is at least once and always uses the durable canonical response. Implementations must be idempotent. */
fun interface WorkerRequestCompletionObserver {
    suspend fun completed(record: StoredWorkerRequest, response: WorkerGatewayMessage.Response)
}

@Service
class WorkerRequestService(
    private val repository: WorkerRequestRepository,
    private val authorization: WorkerRequestAuthorization,
    private val completionObservers: List<WorkerRequestCompletionObserver> = emptyList(),
) {
    private val log = KLoggers.logger(this)
    suspend fun execute(
        workerId: ConversationRuntimeWorkerId,
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        policy: WorkerRequestPolicy,
        actorUserId: User.Id? = null,
        projectId: Project.Id? = null,
        diagnosticId: String? = null,
        slotOrigin: com.gromozeka.domain.slot.SlotCommandOrigin? = null,
    ): ByteArray {
        val id = submit(workerId, operation, payload, policy, actorUserId, projectId, slotOrigin)
        log.debug { "WORKER_REQUEST_TRACE request=$id call=${diagnosticId ?: "none"} phase=awaiting" }
        return try {
            val response = await(id, policy.waitTimeoutMillis)
            check(response.status == WorkerGatewayMessage.Response.Status.SUCCEEDED) {
                "Worker request $id failed [${response.errorCode}]: ${response.errorMessage}"
            }
            requireNotNull(response.payload)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cancel(id) }
            throw error
        }
    }

    suspend fun submit(
        workerId: ConversationRuntimeWorkerId,
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        policy: WorkerRequestPolicy,
        actorUserId: User.Id? = null,
        projectId: Project.Id? = null,
        slotOrigin: com.gromozeka.domain.slot.SlotCommandOrigin? = null,
    ): String {
        require(payload.size <= 64 * 1024 * 1024) { "Worker request exceeds 64 MiB" }
        val createdAt = Clock.System.now()
        val delivery = WorkerRequestDelivery(createdAt + policy.deliveryTtlMillis.milliseconds, policy.executionTimeoutMillis)
        val request = WorkerGatewayMessage.Request(uuid7(), operation, payload, delivery)
        val record = StoredWorkerRequest(
            id = request.id,
            workerId = workerId,
            request = WorkerGatewayCodec.encode(request),
            slotOrigin = slotOrigin,
            createdAt = createdAt,
            startDeadline = delivery.startDeadline,
            executionDeadline = delivery.startDeadline + delivery.executionTimeoutMillis.milliseconds,
            actorUserId = actorUserId,
            projectId = projectId,
        )
        authorization.requireAccess(record)
        repository.create(record)
        log.debug {
            "WORKER_REQUEST_TRACE request=${request.id} phase=persisted worker=${workerId.value} " +
                "operation=$operation bytes=${payload.size}"
        }
        return request.id
    }

    suspend fun find(id: String): StoredWorkerRequest? = repository.find(id)?.let { expire(it, Clock.System.now()) }

    private suspend fun expire(record: StoredWorkerRequest, at: kotlin.time.Instant): StoredWorkerRequest? {
        if (record.response != null) return record
        val executionDeadline = record.executionDeadline ?: run {
            val request = WorkerGatewayCodec.decode(record.request) as WorkerGatewayMessage.Request
            val delivery = requireNotNull(request.delivery) { "Stored Worker request has no delivery policy" }
            check(delivery.startDeadline.toEpochMilliseconds() == record.startDeadline.toEpochMilliseconds()) { "Stored Worker request deadline mismatch" }
            (delivery.startDeadline + delivery.executionTimeoutMillis.milliseconds).also {
                repository.setExecutionDeadlineIfMissing(record.id, it)
            }
        }
        val failure = when {
            record.dispatchedAt == null && record.cancelRequestedAt != null ->
                failure(record.id, "CANCELLED", "Request cancelled before dispatch")
            record.dispatchedAt == null && at >= record.startDeadline ->
                failure(record.id, "EXPIRED", "Delivery TTL expired before dispatch")
            at >= executionDeadline -> failure(record.id, "OUTCOME_UNKNOWN",
                "Worker response deadline passed. The action may have run; inspect its state instead of automatically repeating it.")
            else -> return record.copy(executionDeadline = executionDeadline)
        }
        repository.complete(record.workerId, record.id, WorkerGatewayCodec.encode(failure), at,
            onlyIfUndispatched = record.dispatchedAt == null)
        return repository.find(record.id)
    }

    /** Bounded maintenance independent of connected Workers, callers, and managed process lifetimes. */
    suspend fun maintain(at: kotlin.time.Instant = Clock.System.now()) {
        for (id in repository.maintenanceCandidates(at, 32)) {
            repository.find(id)?.let { expire(it, at) }
        }
        for (id in repository.unprojectedCompletions(32)) {
            val record = repository.find(id) ?: continue
            check(record.completedAt != null) { "Uncompleted Worker request selected for projection" }
            val response = WorkerGatewayCodec.decode(requireNotNull(record.response)) as WorkerGatewayMessage.Response
            try {
                completionObservers.forEach { it.completed(record, response) }
                repository.markCompletionProjected(id, at)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The durable pending marker survives this failure and prevents retention GC.
                // Decoder exceptions can embed private tool output in their messages.
                log.error { "Worker request completion projection failed: $id (${error::class.simpleName}); retained for retry" }
            }
        }
        repository.deleteCompletedBefore(at - 1.hours, 256)
    }

    suspend fun await(id: String, timeoutMillis: Long): WorkerGatewayMessage.Response {
        require(timeoutMillis > 0) { "Worker response wait must be positive" }
        val initial = find(id) ?: throw WorkerRequestResultUnavailableException(id)
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
        while (true) {
            val progress = repository.progress(id) ?: throw WorkerRequestResultUnavailableException(id)
            if (progress.completedAt != null) {
                log.debug { "WORKER_REQUEST_TRACE request=$id phase=result_observed" }
                val completed = repository.find(id) ?: throw WorkerRequestResultUnavailableException(id)
                return WorkerGatewayCodec.decode(requireNotNull(completed.response)) as WorkerGatewayMessage.Response
            }
            val now = Clock.System.now()
            if ((progress.dispatchedAt == null && (progress.cancelRequestedAt != null || now >= initial.startDeadline)) ||
                initial.executionDeadline?.let { now >= it } == true) {
                find(id) ?: throw WorkerRequestResultUnavailableException(id)
                continue
            }
            if (deadline.hasPassedNow()) throw WorkerRequestPendingException(id)
            delay(100)
        }
    }

    suspend fun cancel(id: String) {
        repository.cancel(id, Clock.System.now())
        find(id)
    }

    suspend fun deliver(session: WorkerGatewaySession) {
        val delivered = mutableMapOf<String, Boolean>()
        while (currentCoroutineContext().isActive) {
            val pending = repository.pending(session.identity.workerId, 256)
            delivered.keys.retainAll(pending.map { it.id }.toSet())
            for (entry in pending) {
                if (delivered[entry.id] == entry.cancelRequested) continue
                val record = find(entry.id) ?: continue
                if (record.response != null) continue
                val permitted = try {
                    authorization.requireAccess(record)
                    true
                } catch (error: WorkerAccessDeniedException) {
                    false
                } catch (error: ProjectAccessDeniedException) {
                    false
                }
                if (!permitted) {
                    cancel(record.id)
                    if (record.dispatchedAt == null) continue
                }
                val request = WorkerGatewayCodec.decode(record.request) as WorkerGatewayMessage.Request
                if (!repository.markDispatched(record.id, Clock.System.now())) continue
                val cancelled = entry.cancelRequested || !permitted
                session.send(request.copy(delivery = requireNotNull(request.delivery).copy(cancelRequested = cancelled)))
                log.debug {
                    "WORKER_REQUEST_TRACE request=${request.id} phase=dispatched worker=${session.identity.workerId.value} " +
                        "queueMs=${(Clock.System.now() - record.createdAt).inWholeMilliseconds} cancelled=$cancelled"
                }
                delivered[entry.id] = cancelled
            }
            delay(250)
        }
    }

    suspend fun accept(workerId: ConversationRuntimeWorkerId, response: WorkerGatewayMessage.Response): Boolean {
        val record = repository.find(response.requestId) ?: return false
        require(record.workerId == workerId) { "Worker returned another Worker's result" }
        if (record.response != null) return true
        require(record.dispatchedAt != null) { "Worker returned an undispatched request result" }
        val completed = repository.complete(workerId, response.requestId, WorkerGatewayCodec.encode(response), Clock.System.now())
        // Projection reads the winner of this CAS later. A late success cannot replace a timeout,
        // and a crash between completion and projection cannot lose the notification.
        log.debug {
            "WORKER_REQUEST_TRACE request=${response.requestId} phase=result_persisted " +
                "won=$completed bytes=${response.payload?.size ?: 0}"
        }
        return true
    }

    private fun failure(id: String, code: String, message: String) = WorkerGatewayMessage.Response(
        requestId = id,
        status = WorkerGatewayMessage.Response.Status.FAILED,
        errorCode = code,
        errorMessage = message,
    )
}
