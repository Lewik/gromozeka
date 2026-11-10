package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.AiAdmissionRepository
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.AiConfigurationProvider
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class ServerAiCallAdmission(
    private val configuration: AiConfigurationProvider,
    private val repository: AiAdmissionRepository,
    @Qualifier("applicationScope") private val scope: CoroutineScope,
) : AiCallAdmission() {
    private val log = KLoggers.logger(this)
    private lateinit var queue: AiAdmissionQueue
    private var catalogUpdates: Job? = null
    private val lifecycle = Mutex()
    val snapshot: AiAdmissionSnapshot get() = queue.snapshot.value

    @PostConstruct
    fun initialize() = runBlocking {
        val initial = configuration.snapshot
        queue = AiAdmissionQueue(scope, initial.catalog, repository.restoreRemoteLeases(), initial.revision)
        catalogUpdates = scope.launch {
            configuration.snapshotFlow.filterNotNull().collect { queue.updateCatalog(it.catalog, it.revision) }
        }
    }

    @PreDestroy
    fun stop() = runBlocking {
        catalogUpdates?.cancel()
        if (::queue.isInitialized) queue.close()
    }

    override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease = acquirePersisted(operation, null, null)

    suspend fun acquireRemote(operation: AiAdmissionOperation, workerId: String, workerRequestId: String): AiAdmissionLease =
        acquirePersisted(operation, workerId, workerRequestId)

    private suspend fun acquirePersisted(operation: AiAdmissionOperation, workerId: String?, workerRequestId: String?): AiAdmissionLease {
        log.debug { "AI_ADMISSION operation=${operation.id} phase=waiting purpose=${operation.purpose}" }
        val current = configuration.snapshot
        queue.updateCatalog(current.catalog, current.revision)
        val lease = queue.acquire(operation, workerId, workerRequestId)
        try {
            // A grant is durable before a Worker can start, including a lost Gateway response.
            withContext(NonCancellable) { lifecycle.withLock { repository.create(lease) } }
            currentCoroutineContext().ensureActive()
            log.debug { "AI_ADMISSION operation=${operation.id} phase=admitted group=${lease.groupId.value}" }
            return lease
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                lifecycle.withLock {
                    repository.finish(lease.operation.id, workerId)
                    queue.release(lease.operation.id)
                }
            }
            throw error
        }
    }

    override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) =
        finishOperation(lease.operation.id, null, outcome)

    suspend fun acceptOperation(id: String, workerId: String) = lifecycle.withLock {
        repository.accept(id, workerId)
        queue.accept(id)
    }

    suspend fun finishOperation(id: String, workerId: String?, outcome: AiAdmissionOutcome): Unit = lifecycle.withLock {
        snapshot.active.find { it.operation.id == id }?.let {
            require(it.workerId == workerId) { "AI admission lease belongs to another executor" }
        }
        repository.finish(id, workerId)
        queue.release(id)
        log.debug { "AI_ADMISSION operation=$id phase=finished outcome=$outcome" }
    }

    /** Only an actual Worker response proves that its execution scope ended, not a Server wait timeout. */
    suspend fun workerRequestFinished(workerId: String, requestId: String): Unit = lifecycle.withLock {
        repository.finishWorkerRequest(workerId, requestId)
        queue.finishWorkerRequest(workerId, requestId)
    }
}
