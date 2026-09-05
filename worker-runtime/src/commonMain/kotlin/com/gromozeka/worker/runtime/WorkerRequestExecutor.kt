package com.gromozeka.worker.runtime

import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.shared.utils.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkerRequestExecutor(
    private val journal: WorkerRequestJournal,
    private val scope: CoroutineScope,
    private val handler: WorkerRequestHandler,
    private val publish: suspend (WorkerGatewayMessage.Response) -> Unit,
) {
    private val mutex = Mutex()
    private val receipts = mutableMapOf<String, WorkerRequestReceipt>()
    private val jobs = mutableMapOf<String, Job>()
    private val concurrency = Semaphore(64)

    suspend fun initialize() = mutex.withLock {
        check(receipts.isEmpty()) { "Worker request executor is already initialized" }
        for (receipt in journal.load()) {
            val recovered = if (receipt.state == WorkerRequestReceipt.State.RUNNING) {
                receipt.copy(state = WorkerRequestReceipt.State.COMPLETED, response = failure(receipt.id, "OUTCOME_UNKNOWN", "Worker process stopped after execution started; the action will not be repeated"))
            } else receipt
            if (recovered != receipt) journal.save(recovered)
            receipts[recovered.id] = recovered
        }
        pruneAcknowledged()
    }

    suspend fun accept(request: WorkerGatewayMessage.Request) {
        val delivery = requireNotNull(request.delivery) { "Server requests require delivery metadata" }
        val fingerprint = WorkerGatewayCodec.encode(request.copy(delivery = delivery.copy(cancelRequested = false))).sha256()
        val response = mutex.withLock {
            val previous = receipts[request.id]
            require(previous == null || previous.fingerprint == fingerprint) { "Worker request ID was reused with different content" }
            when {
                previous?.state == WorkerRequestReceipt.State.COMPLETED -> previous.response
                previous?.state == WorkerRequestReceipt.State.ACKNOWLEDGED -> failure(request.id, "RESULT_ACKNOWLEDGED", "Server already acknowledged the result; action will not be repeated")
                jobs.containsKey(request.id) -> {
                    if (delivery.cancelRequested) jobs[request.id]?.cancel()
                    null
                }
                else -> {
                    pruneAcknowledged()
                    check(previous != null || receipts.size < 4_096) { "Worker request journal is full" }
                    val receipt = previous ?: WorkerRequestReceipt(request.id, fingerprint, delivery.startDeadline, WorkerRequestReceipt.State.RECEIVED)
                    val failure = when {
                        delivery.cancelRequested -> failure(request.id, "CANCELLED", "Request cancelled before execution")
                        Clock.System.now() >= delivery.startDeadline -> failure(request.id, "EXPIRED", "Delivery TTL expired before execution")
                        else -> null
                    }
                    if (failure != null) {
                        save(receipt.copy(state = WorkerRequestReceipt.State.COMPLETED, response = failure))
                        failure
                    } else {
                        save(receipt)
                        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(request) }
                        jobs[request.id] = job
                        null
                    }
                }
            }
        }
        response?.let { publish(it) }
    }

    suspend fun cancel(id: String) = mutex.withLock { jobs[id]?.cancel() }

    suspend fun acknowledge(id: String) = mutex.withLock {
        val receipt = receipts[id] ?: return@withLock
        if (receipt.state == WorkerRequestReceipt.State.COMPLETED) {
            save(receipt.copy(state = WorkerRequestReceipt.State.ACKNOWLEDGED, response = null))
            pruneAcknowledged()
        }
    }

    suspend fun pendingResponses(): List<WorkerGatewayMessage.Response> = mutex.withLock {
        receipts.values.mapNotNull { it.response }
    }

    suspend fun stop() {
        val active = mutex.withLock { jobs.values.toList() }
        active.forEach { it.cancel() }
        active.joinAll()
    }

    private suspend fun execute(request: WorkerGatewayMessage.Request) {
        val delivery = requireNotNull(request.delivery)
        val response = try {
            concurrency.withPermit {
                currentCoroutineContext().ensureActive()
                val expired = mutex.withLock {
                    val receipt = receipts.getValue(request.id)
                    if (Clock.System.now() >= delivery.startDeadline) true
                    else { save(receipt.copy(state = WorkerRequestReceipt.State.RUNNING)); false }
                }
                if (expired || Clock.System.now() >= delivery.startDeadline) failure(request.id, "EXPIRED", "Delivery TTL expired while waiting to execute")
                else withTimeout(delivery.executionTimeoutMillis.milliseconds) {
                    handler.execute(request).also { require(it.requestId == request.id) { "Handler returned another request's response" } }
                }
            }
        } catch (error: TimeoutCancellationException) {
            failure(request.id, "EXECUTION_TIMEOUT", "Execution timed out; partial effects are possible and the action will not be repeated")
        } catch (error: CancellationException) {
            failure(request.id, "CANCELLED", "Execution was cancelled; partial effects are possible and the action will not be repeated")
        } catch (error: Exception) {
            failure(request.id, error::class.simpleName ?: "WORKER_ERROR", error.message ?: "Worker request failed")
        }
        withContext(NonCancellable) {
            mutex.withLock {
                save(receipts.getValue(request.id).copy(state = WorkerRequestReceipt.State.COMPLETED, response = response))
                jobs.remove(request.id)
            }
            publish(response)
        }
    }

    private suspend fun save(receipt: WorkerRequestReceipt) {
        journal.save(receipt)
        receipts[receipt.id] = receipt
    }

    private suspend fun pruneAcknowledged() {
        val expired = receipts.values.filter { it.state == WorkerRequestReceipt.State.ACKNOWLEDGED && Clock.System.now() >= it.startDeadline }
        for (receipt in expired) {
            journal.delete(receipt.id)
            receipts.remove(receipt.id)
        }
    }

    private fun failure(id: String, code: String, message: String) = WorkerGatewayMessage.Response(
        requestId = id,
        status = WorkerGatewayMessage.Response.Status.FAILED,
        errorCode = code,
        errorMessage = message,
    )
}
