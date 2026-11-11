package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.AiAdmissionRepository
import com.gromozeka.domain.service.AiQuotaFeedbackException
import com.gromozeka.domain.service.AiAdmissionException
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.AiConfigurationProvider
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class ServerAiCallAdmission(
    private val configuration: AiConfigurationProvider,
    private val repository: AiAdmissionRepository,
    @Qualifier("applicationScope") private val scope: CoroutineScope,
    private val quotaPolicies: AiQuotaPolicyEvaluator,
) : AiCallAdmission() {
    private val log = KLoggers.logger(this)
    private lateinit var queue: AiAdmissionQueue
    private var catalogUpdates: Job? = null
    private val lifecycle = Mutex()
    @Volatile private var storageFailure: Throwable? = null
    val snapshot: AiAdmissionSnapshot get() = queue.snapshot.value

    @PostConstruct
    fun initialize() = runBlocking {
        val initial = configuration.snapshot
        queue = AiAdmissionQueue(scope, initial.catalog, repository.restoreRemoteLeases(), initial.revision, repository.loadTpmStates())
        catalogUpdates = scope.launch {
            configuration.snapshotFlow.filterNotNull().collect { queue.updateCatalog(it.catalog, it.revision) }
        }
    }

    @PreDestroy
    fun stop() = runBlocking {
        catalogUpdates?.cancelAndJoin()
        if (::queue.isInitialized) queue.close()
    }

    override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease = acquirePersisted(operation, null, null, null)
    override suspend fun acquireHttp(operation: AiAdmissionOperation, request: JsonElement): AiAdmissionLease =
        acquirePersisted(operation, null, null, request)

    suspend fun acquireRemote(operation: AiAdmissionOperation, workerId: String, workerRequestId: String, request: JsonElement? = null): AiAdmissionLease =
        acquirePersisted(operation, workerId, workerRequestId, request)

    private suspend fun acquirePersisted(operation: AiAdmissionOperation, workerId: String?, workerRequestId: String?, request: JsonElement?): AiAdmissionLease {
        check(storageFailure == null) { "AI admission persistence requires recovery" }
        require(operation.tpm == null) { "TPM reservations are owned by Server" }
        val current = configuration.snapshot
        val target = operation.target as? AiAdmissionTarget.ModelConfiguration
        val model = target?.let { selected -> requireNotNull(current.catalog.modelConfigurations.find { it.id == selected.id }).also {
            require(it.connectionId == selected.connectionId) { "AI admission connection differs from model configuration" }
        } }
        val reservation = model?.tpm?.let { policy ->
            requireNotNull(request) { "TPM is unavailable for this non-JSON-HTTP operation" }
            quotaPolicies.prepare(policy, request, current.catalog)
        }
        queue.updateCatalog(current.catalog, current.revision)
        val lease = queue.acquire(operation.copy(tpm = reservation), workerId, workerRequestId)
        try {
            // The quota debit and lease are durable before the executor is allowed to send.
            withContext(NonCancellable) { lifecycle.withLock {
                check(storageFailure == null) { "AI admission persistence requires recovery" }
                repository.create(lease, snapshot.tpmGroups)
            } }
            currentCoroutineContext().ensureActive()
            log.debug { "AI_ADMISSION operation=${operation.id} phase=admitted group=${lease.groupId.value}" }
            return lease
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                lifecycle.withLock {
                    queue.release(lease.operation.id, refund = true)
                    persist { repository.finish(lease.operation.id, workerId, snapshot.tpmGroups) }
                }
            }
            throw error
        }
    }

    override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) = finishOperation(lease.operation.id, null, outcome)
    override suspend fun finishHttp(lease: AiAdmissionLease, outcome: AiAdmissionOutcome, feedback: AiHttpQuotaFeedback?) =
        finishOperation(lease.operation.id, null, outcome, feedback)

    suspend fun acceptOperation(id: String, workerId: String) = lifecycle.withLock {
        persist { repository.accept(id, workerId) }
        queue.accept(id)
    }

    suspend fun finishOperation(id: String, workerId: String?, outcome: AiAdmissionOutcome, raw: AiHttpQuotaFeedback? = null) {
        repository.findCompletion(id, workerId)?.let { completed ->
            completed.feedbackError?.let { throw AiQuotaFeedbackException(it) }
            return
        }
        val lease = snapshot.active.find { it.operation.id == id }
        if (lease == null && raw != null) throw AiQuotaFeedbackException("AI quota completion is no longer retained")
        lease?.let { require(it.workerId == workerId) { "AI admission lease belongs to another executor" } }
        var feedback: AiTpmFeedback? = null
        var feedbackFailure: Exception? = null
        try {
            lease?.operation?.tpm?.let { reservation ->
                if (raw != null) {
                    val group = requireNotNull(configuration.snapshot.catalog.tpmGroups.find { it.id == reservation.groupId })
                    feedback = quotaPolicies.feedback(reservation.feedback, raw, group)
                } else if (outcome == AiAdmissionOutcome.SUCCEEDED) {
                    throw AiAdmissionException("Successful AI operation has no required TPM response feedback")
                }
            }
        } catch (error: Exception) {
            feedbackFailure = error
        }
        var completionError = feedbackFailure?.let { "Invalid AI quota feedback (${it::class.simpleName})" }
        withContext(NonCancellable) {
            lifecycle.withLock {
                val previous = repository.findCompletion(id, workerId)
                if (previous != null) completionError = previous.feedbackError else {
                    queue.release(id, feedback)
                    persist { repository.finish(id, workerId, snapshot.tpmGroups, completionError) }
                }
            }
        }
        completionError?.let { throw AiQuotaFeedbackException(it) }
        log.debug { "AI_ADMISSION operation=$id phase=finished outcome=$outcome" }
    }

    /** Only an actual Worker response proves that its execution scope ended, not a Server wait timeout. */
    suspend fun workerRequestFinished(workerId: String, requestId: String): Unit = lifecycle.withLock {
        persist { repository.finishWorkerRequest(workerId, requestId) }
        queue.finishWorkerRequest(workerId, requestId)
    }

    private suspend fun <T> persist(block: suspend () -> T): T = try { block() }
    catch (error: Exception) {
        if (error !is IllegalArgumentException) storageFailure = error
        throw error
    }
}
