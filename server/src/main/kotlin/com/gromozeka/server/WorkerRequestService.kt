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

class WorkerRequestPendingException(val requestId: String) : IllegalStateException(
    "Worker request $requestId is still pending. Waiting ended, but the request was not cancelled. Query grz_worker_request_get or cancel it explicitly.",
)

@Service
class WorkerRequestService(
    private val repository: WorkerRequestRepository,
    private val authorization: WorkerRequestAuthorization,
) {
    suspend fun execute(
        workerId: ConversationRuntimeWorkerId,
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        policy: WorkerRequestPolicy,
        actorUserId: User.Id? = null,
        projectId: Project.Id? = null,
    ): ByteArray {
        val id = submit(workerId, operation, payload, policy, actorUserId, projectId)
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
    ): String {
        require(payload.size <= 64 * 1024 * 1024) { "Worker request exceeds 64 MiB" }
        val createdAt = Clock.System.now()
        val delivery = WorkerRequestDelivery(createdAt + policy.deliveryTtlMillis.milliseconds, policy.executionTimeoutMillis)
        val request = WorkerGatewayMessage.Request(uuid7(), operation, payload, delivery)
        val record = StoredWorkerRequest(
            id = request.id,
            workerId = workerId,
            request = WorkerGatewayCodec.encode(request),
            createdAt = createdAt,
            startDeadline = delivery.startDeadline,
            actorUserId = actorUserId,
            projectId = projectId,
        )
        authorization.requireAccess(record)
        repository.create(record)
        return request.id
    }

    suspend fun find(id: String): StoredWorkerRequest? {
        val record = repository.find(id) ?: return null
        if (record.response == null && record.dispatchedAt == null) {
            val failure = when {
                record.cancelRequestedAt != null -> failure(id, "CANCELLED", "Request cancelled before dispatch")
                Clock.System.now() >= record.startDeadline -> failure(id, "EXPIRED", "Delivery TTL expired before dispatch")
                else -> null
            }
            if (failure != null) {
                repository.complete(record.workerId, id, WorkerGatewayCodec.encode(failure), Clock.System.now(), onlyIfUndispatched = true)
                return repository.find(id)
            }
        }
        return record
    }

    suspend fun await(id: String, timeoutMillis: Long): WorkerGatewayMessage.Response {
        require(timeoutMillis > 0) { "Worker response wait must be positive" }
        val initial = requireNotNull(find(id)) { "Unknown Worker request $id" }
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
        while (true) {
            val progress = requireNotNull(repository.progress(id)) { "Unknown Worker request $id" }
            if (progress.completedAt != null) {
                return WorkerGatewayCodec.decode(requireNotNull(repository.find(id)?.response)) as WorkerGatewayMessage.Response
            }
            if (progress.dispatchedAt == null && (progress.cancelRequestedAt != null || Clock.System.now() >= initial.startDeadline)) {
                find(id)
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
                delivered[entry.id] = cancelled
            }
            delay(250)
        }
    }

    suspend fun accept(workerId: ConversationRuntimeWorkerId, response: WorkerGatewayMessage.Response): Boolean {
        val record = repository.find(response.requestId) ?: return false
        require(record.workerId == workerId) { "Worker returned another Worker's result" }
        require(record.dispatchedAt != null) { "Worker returned an undispatched request result" }
        if (record.response == null) {
            repository.complete(workerId, response.requestId, WorkerGatewayCodec.encode(response), Clock.System.now())
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
