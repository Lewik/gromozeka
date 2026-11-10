package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/** The Worker request is an execution owner, not a permission supplied by model input. */
class AiAdmissionWorkerContext(val requestId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiAdmissionWorkerContext>
}

private class HeldAiAdmission : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeldAiAdmission>
}

class AiAdmissionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** For adapters returning a live session. Close the transport before finishing its admission. */
class AiAdmittedSession internal constructor(private val release: suspend (AiAdmissionOutcome) -> Unit) {
    private val mutex = Mutex()
    private var finished = false
    suspend fun finish(outcome: AiAdmissionOutcome = AiAdmissionOutcome.SUCCEEDED) = withContext(NonCancellable) {
        mutex.withLock {
            if (!finished) { release(outcome); finished = true }
        }
    }
}

abstract class AiCallAdmission {
    protected abstract suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease
    protected abstract suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome)

    suspend fun openSession(connection: AiConnection, purpose: String, backgroundSessionId: String? = null): AiAdmittedSession {
        if (currentCoroutineContext()[HeldAiAdmission] != null) throw AiAdmissionException("Nested AI admission is not allowed")
        val operation = AiAdmissionOperation(uuid7().toString(), AiAdmissionTarget.Connection(connection.id), purpose,
            backgroundSessionId = backgroundSessionId)
        val lease = acquireChecked(operation)
        return AiAdmittedSession { finishChecked(lease, it) }
    }

    private suspend fun acquireChecked(operation: AiAdmissionOperation): AiAdmissionLease = try {
        acquire(operation)
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { throw AiAdmissionException("AI admission failed (${error::class.simpleName})", error) }

    private suspend fun finishChecked(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) {
        try { finish(lease, outcome) }
        catch (error: Exception) { throw AiAdmissionException("AI admission completion failed (${error::class.simpleName})", error) }
    }

    suspend fun <T> execute(
        connection: AiConnection,
        purpose: String = "INFERENCE",
        deadline: Instant? = null,
        block: suspend () -> T,
    ): T = execute(AiAdmissionTarget.Connection(connection.id), purpose, deadline, block)

    suspend fun <T> execute(
        target: AiAdmissionTarget,
        purpose: String,
        deadline: Instant? = null,
        block: suspend () -> T,
    ): T {
        if (currentCoroutineContext()[HeldAiAdmission] != null) throw AiAdmissionException(
            "Nested AI admission is not allowed; finish the parent operation before starting another AI call")
        val operation = AiAdmissionOperation(uuid7().toString(), target, purpose, deadline)
        suspend fun run(): T {
            val lease = acquireChecked(operation)
            var outcome = AiAdmissionOutcome.FAILED
            try {
                currentCoroutineContext().ensureActive()
                return withContext(HeldAiAdmission()) { block() }.also { outcome = AiAdmissionOutcome.SUCCEEDED }
            } catch (error: CancellationException) {
                outcome = AiAdmissionOutcome.CANCELLED
                throw error
            } finally {
                withContext(NonCancellable) { finishChecked(lease, outcome) }
            }
        }
        if (deadline == null) return run()
        val remaining = (deadline - Clock.System.now()).inWholeMilliseconds
        if (remaining <= 0) throw deadlineFailure()
        return try {
            withTimeout(remaining) { run() }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    private fun deadlineFailure() = AiCallException(AiCallFailure(
        AiCallFailure.Kind.DEADLINE_EXCEEDED, AiCallFailure.Outcome.NOT_STARTED, 0,
    ))
}
