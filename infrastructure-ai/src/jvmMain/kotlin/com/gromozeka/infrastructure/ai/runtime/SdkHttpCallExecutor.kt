package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiCallException
import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import kotlinx.coroutines.CoroutineScope
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.model.ai.AiHttpCallBudget
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Clock

internal class SdkInferenceClient<C>(val client: C, val transport: SdkHttpTransport,
    private val closeClient: () -> Unit) : AutoCloseable {
    override fun close() { try { closeClient() } finally { transport.close() } }
}

internal class SdkHttpCallExecutor<C>(
    private val connection: AiConnection,
    private val timeoutSeconds: Int?,
    private val maxAttempts: Int = SdkHttpSupport.settings(connection).maxAttempts,
    private val admission: AiCallAdmission,
    private val modelConfiguration: AiModelConfiguration? = null,
    private val clientFactory: (deadlineNanos: Long) -> SdkInferenceClient<C>,
) {
    init { require(maxAttempts in 1..5) { "Invalid AI HTTP attempt limit" } }

    fun withDeadline(options: AiRuntimeOptions): AiRuntimeOptions = options.copy(
        executionDeadline = AiHttpCallBudget.deadline(timeoutSeconds, options.executionDeadline, Clock.System.now()))

    suspend fun <T> call(options: AiRuntimeOptions, action: (C, Duration) -> CompletableFuture<T>): T {
        val now = Clock.System.now()
        val deadline = AiHttpCallBudget.deadline(timeoutSeconds, options.executionDeadline, now)
        val remainingNanos = (deadline - now).inWholeNanoseconds
        if (remainingNanos <= 0) throw AiCallException(AiCallFailure(AiCallFailure.Kind.DEADLINE_EXCEEDED,
            AiCallFailure.Outcome.NOT_STARTED, 0))
        val end = System.nanoTime() + remainingNanos
        val transportRef = AtomicReference<SdkHttpTransport?>()
        try {
            return withTimeout((remainingNanos / 1_000_000).coerceAtLeast(1)) {
                withContext(Dispatchers.IO) {
                    var session: SdkInferenceClient<C>? = null
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            try {
                                suspend fun attempt(): T {
                                    currentCoroutineContext().ensureActive()
                                    val activeSession = session ?: try {
                                        clientFactory(end).also { session = it; transportRef.set(it.transport) }
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { throw AiCallException(AiCallFailure(AiCallFailure.Kind.CONFIGURATION,
                                        AiCallFailure.Outcome.NOT_STARTED, 0)) }
                                    val left = end - System.nanoTime()
                                    if (left <= 0) throw deadlineFailure(activeSession.transport)
                                    activeSession.transport.beginAttempt()
                                    val tpm = modelConfiguration?.takeIf { it.tpm != null }
                                    if (tpm != null) activeSession.transport.configureTpm(CoroutineScope(currentCoroutineContext()),
                                        admission, tpm, options.usagePurpose ?: "INFERENCE", deadline)
                                    var outcome = AiAdmissionOutcome.FAILED
                                    try {
                                        return await(activeSession.transport) { action(activeSession.client, Duration.ofNanos(left)) }
                                            .also { outcome = AiAdmissionOutcome.SUCCEEDED }
                                    } catch (error: CancellationException) { outcome = AiAdmissionOutcome.CANCELLED; throw error }
                                    finally { if (tpm != null) activeSession.transport.finishTpm(outcome) }
                                }
                                return@withContext when {
                                    modelConfiguration?.tpm != null -> attempt()
                                    modelConfiguration != null -> admission.execute(modelConfiguration, options.usagePurpose ?: "INFERENCE", deadline) { attempt() }
                                    else -> admission.execute(connection, options.usagePurpose ?: "INFERENCE", deadline) { attempt() }
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (error: com.gromozeka.domain.service.AiAdmissionException) { throw error }
                            catch (error: AiCallException) { throw error }
                            catch (error: Exception) {
                                currentCoroutineContext().ensureActive()
                                generateSequence<Throwable>(error) { it.cause }.filterIsInstance<com.gromozeka.domain.service.AiAdmissionException>()
                                    .firstOrNull()?.let { throw it }
                                val transport = requireNotNull(transportRef.get()) { "SDK failure has no transport attempt" }
                                if (end - System.nanoTime() <= 0) throw deadlineFailure(transport)
                                val failure = classify(error, transport)
                                if (failure.kind != AiCallFailure.Kind.RATE_LIMITED ||
                                    failure.outcome != AiCallFailure.Outcome.REJECTED ||
                                    requireNotNull(failure.attempts) >= maxAttempts) throw AiCallException(failure)
                                val backoff = retryDelayMillis(requireNotNull(failure.attempts), failure.retryAfterMillis)
                                val leftMillis = (end - System.nanoTime()) / 1_000_000
                                // Retry-After is a lower bound. Backoff holds no admission lease.
                                if (backoff >= leftMillis) throw AiCallException(failure)
                                delay(backoff)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") error("AI call loop returned without a result")
                    } finally { session?.close() }
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw deadlineFailure(transportRef.get())
        } catch (error: AiCallException) {
            // Resource cleanup must not attach an SDK exception (with private data) as suppressed.
            throw AiCallException(error.failure)
        }
    }

    private suspend fun <T> await(transport: SdkHttpTransport, action: () -> CompletableFuture<T>): T =
        suspendCancellableCoroutine { continuation ->
            val pending = AtomicReference<CompletableFuture<T>?>()
            continuation.invokeOnCancellation {
                transport.close()
                pending.get()?.cancel(true)
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val future = try { action() } catch (error: Exception) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            pending.set(future)
            if (!continuation.isActive) future.cancel(true)
            future.whenComplete { result, error ->
                if (error == null) continuation.resume(result)
                else continuation.resumeWithException(unwrap(error))
            }
        }
}

internal fun retryDelayMillis(attempt: Int, retryAfterMillis: Long?, jitter: Long = Random.nextLong(0, 251)): Long {
    require(attempt in 1..5 && jitter in 0..250) { "Invalid AI retry backoff inputs" }
    val base = retryAfterMillis ?: (250L shl (attempt - 1)).coerceAtMost(4000)
    return if (base > Long.MAX_VALUE - jitter) Long.MAX_VALUE else base + jitter
}

private fun unwrap(error: Throwable): Throwable =
    if (error is CompletionException && error.cause != null) unwrap(error.cause!!) else error

private fun deadlineFailure(transport: SdkHttpTransport?): AiCallException = AiCallException(AiCallFailure(
    AiCallFailure.Kind.DEADLINE_EXCEEDED,
    when {
        transport == null || transport.attempts == 0 -> AiCallFailure.Outcome.NOT_STARTED
        transport.snapshot().let { it.status == 429 && it.bodyComplete } -> AiCallFailure.Outcome.REJECTED
        else -> AiCallFailure.Outcome.UNKNOWN
    }, transport?.attempts ?: 0,
))

private fun classify(error: Throwable, transport: SdkHttpTransport): AiCallFailure {
    val attempt = transport.snapshot()
    val status = attempt.status
    val causes = generateSequence(unwrap(error)) { it.cause }.take(16).toList()
    val kind = when {
        causes.any { it is HttpFollowUpPreventedException } -> AiCallFailure.Kind.HTTP_ERROR
        status == 401 && attempt.bodyComplete -> AiCallFailure.Kind.AUTHENTICATION
        status == 403 && attempt.bodyComplete -> AiCallFailure.Kind.ACCESS_DENIED
        status == 429 && attempt.bodyComplete -> AiCallFailure.Kind.RATE_LIMITED
        status != null && status !in 200..299 && attempt.bodyComplete -> AiCallFailure.Kind.HTTP_ERROR
        status != null && status in 200..299 && attempt.bodyComplete -> AiCallFailure.Kind.INVALID_RESPONSE
        causes.any { it is SSLException } -> AiCallFailure.Kind.TLS
        causes.any { it is IOException } -> AiCallFailure.Kind.TRANSPORT
        else -> AiCallFailure.Kind.INVALID_RESPONSE
    }
    val outcome = when {
        !attempt.started -> AiCallFailure.Outcome.NOT_STARTED
        attempt.bodyComplete && status != null && status in 400..499 && status !in setOf(408, 409) -> AiCallFailure.Outcome.REJECTED
        else -> AiCallFailure.Outcome.UNKNOWN
    }
    return AiCallFailure(kind, outcome, transport.attempts, status, attempt.retryAfterMillis)
}
