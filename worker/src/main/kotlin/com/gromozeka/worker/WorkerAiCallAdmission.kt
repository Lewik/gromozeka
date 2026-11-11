package com.gromozeka.worker

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiAdmissionWorkerContext
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.ControlPlaneUnavailableException
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.remote.protocol.AiAdmissionCommand
import com.gromozeka.remote.protocol.AiAdmissionGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import com.gromozeka.domain.service.AiAdmissionException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Service
class WorkerAiCallAdmission(
    private val outbound: WorkerGatewayOutbound,
    private val identity: ConversationRuntimeWorkerIdentity,
    private val journal: JvmWorkerRequestJournal,
    @Qualifier("applicationScope") private val scope: CoroutineScope,
) : AiCallAdmission() {
    private data class Completion(val lease: AiAdmissionLease, val outcome: AiAdmissionOutcome, val feedback: AiHttpQuotaFeedback? = null,
        val result: CompletableDeferred<String?> = CompletableDeferred())
    private val log = KLoggers.logger(this)
    private val pending = linkedMapOf<String, Completion>()
    private val pendingMutex = Mutex()
    private val flushMutex = Mutex()
    private val retrySignals = Channel<Unit>(Channel.CONFLATED)
    private var publisher: Job? = null
    private var connections: Job? = null
    @Volatile private var recoveryFailure: Throwable? = null

    @PostConstruct
    fun initialize() = runBlocking {
        // The exclusively locked journal belongs to this process; its previous execution owners are gone.
        val completed = journal.loadAiAdmissionCompletions().associateBy { it.lease.operation.id }
        val leases = journal.loadAiAdmissions().associateBy { it.operation.id }
        (leases + completed.mapValues { it.value.lease }).values.forEach { lease ->
            require(lease.workerId == identity.workerId.value) { "AI admission journal belongs to another Worker" }
            val saved = completed[lease.operation.id]
            pending[lease.operation.id] = Completion(lease, saved?.outcome ?: AiAdmissionOutcome.OWNER_STOPPED, saved?.feedback)
        }
        publisher = scope.launch {
            for (signal in retrySignals) {
                var backoff = 1_000L
                while (outbound.connected.value && pendingMutex.withLock { pending.isNotEmpty() }) {
                    try {
                        flushPending()
                    } catch (error: CancellationException) { throw error }
                    catch (error: ControlPlaneUnavailableException) {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(30_000)
                    } catch (error: Exception) {
                        recoveryFailure = error
                        log.error { "AI admission completion recovery stopped (${error::class.simpleName})" }
                        throw error
                    }
                }
            }
        }
        connections = scope.launch { outbound.connected.collect { if (it) retrySignals.trySend(Unit) } }
    }

    @PreDestroy
    fun stop() = runBlocking {
        connections?.cancelAndJoin()
        publisher?.cancelAndJoin()
        retrySignals.close()
    }

    override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease = acquireOwned(operation, null)
    override suspend fun acquireHttp(operation: AiAdmissionOperation, request: JsonElement): AiAdmissionLease = acquireOwned(operation, request)

    private suspend fun acquireOwned(operation: AiAdmissionOperation, request: JsonElement?): AiAdmissionLease {
        check(recoveryFailure == null) { "AI admission recovery requires attention" }
        flushPending()
        val owner = requireNotNull(currentCoroutineContext()[AiAdmissionWorkerContext]) {
            "Worker AI execution has no owning Server request"
        }
        val wait = operation.deadline?.let { (it - Clock.System.now()).inWholeMilliseconds.milliseconds } ?: 30.minutes
        require(wait.isPositive()) { "AI admission deadline has passed" }
        val lease = AiAdmissionGatewayCodec.decodeLease(outbound.execute(
            WorkerGatewayOperation.AI_ADMISSION,
            AiAdmissionGatewayCodec.encode(AiAdmissionCommand.Acquire(operation, owner.requestId, request)),
            wait,
        ))
        check(lease.operation.id == operation.id && lease.operation.target == operation.target &&
            lease.operation.backgroundSessionId == operation.backgroundSessionId &&
            lease.workerId == identity.workerId.value && lease.workerRequestId == owner.requestId) {
            "Server returned an AI admission lease for a different operation"
        }
        try {
            withContext(NonCancellable) { journal.saveAiAdmission(lease) }
            if (operation.backgroundSessionId != null) {
                // Persist ownership before accepting a grant that may outlive the start RPC.
                outbound.execute(WorkerGatewayOperation.AI_ADMISSION,
                    AiAdmissionGatewayCodec.encode(AiAdmissionCommand.Accept(operation.id)))
            }
            currentCoroutineContext().ensureActive()
            return lease
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                pendingMutex.withLock { pending[operation.id] = Completion(lease, AiAdmissionOutcome.CANCELLED) }
                retrySignals.trySend(Unit)
            }
            throw error
        }
    }

    override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) = finishOwned(lease, outcome, null)
    override suspend fun finishHttp(lease: AiAdmissionLease, outcome: AiAdmissionOutcome, feedback: AiHttpQuotaFeedback?) = finishOwned(lease, outcome, feedback)

    private suspend fun finishOwned(lease: AiAdmissionLease, outcome: AiAdmissionOutcome, feedback: AiHttpQuotaFeedback?) {
        val completion = pendingMutex.withLock {
            pending[lease.operation.id]?.also {
                check(it.lease == lease && it.outcome == outcome && it.feedback == feedback) { "Conflicting AI admission completion" }
            } ?: Completion(lease, outcome, feedback).also {
                journal.saveAiAdmissionCompletion(AiAdmissionCompletion(lease, outcome, feedback))
                pending[lease.operation.id] = it
            }
        }
        try {
            flushPending()
            completion.result.await()?.let { throw AiAdmissionException(it) }
        } catch (error: ControlPlaneUnavailableException) {
            retrySignals.trySend(Unit)
            if (lease.operation.tpm != null) throw AiAdmissionException("TPM feedback could not be confirmed; inference must not be repeated", error)
            log.debug { "AI admission completion deferred: operation=${lease.operation.id}" }
        }
    }

    private suspend fun flushPending() = flushMutex.withLock {
        val completions = pendingMutex.withLock { pending.values.toList() }
        for (completion in completions) {
            val payload = outbound.execute(WorkerGatewayOperation.AI_ADMISSION,
                AiAdmissionGatewayCodec.encode(AiAdmissionCommand.Finish(completion.lease.operation.id, completion.outcome, completion.feedback)),
                ((completion.lease.operation.tpm?.feedback as? AiTpmFeedbackPolicy.Command)?.command?.timeoutSeconds?.plus(30) ?: 30).seconds)
            val feedbackError = if (completion.lease.operation.tpm == null) null else AiAdmissionGatewayCodec.decodeCompletion(payload).feedbackError
            journal.deleteAiAdmission(completion.lease.operation.id)
            pendingMutex.withLock { pending.remove(completion.lease.operation.id) }
            completion.result.complete(feedbackError)
        }
    }
}
