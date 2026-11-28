package com.gromozeka.infrastructure.ai.openai.siwc

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Clock
import kotlin.time.Instant

internal fun siwcDeadlineNanos(deadline: Instant): Long {
    val budget = (deadline - Clock.System.now()).inWholeNanoseconds
    if (budget <= 0) throw siwcDeadlineExceeded()
    return try {
        Math.addExact(System.nanoTime(), budget)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal fun HttpClient.sendSiwc(
    request: HttpRequest,
    deadlineNanos: Long,
    maxBodyBytes: Int,
): HttpResponse<ByteArray> {
    val initialBudget = remainingDuration(deadlineNanos)
    val configuredTimeout = request.timeout().orElse(null)
    val deadlineLimited = configuredTimeout == null || configuredTimeout >= initialBudget
    val effectiveTimeout = configuredTimeout?.takeIf { it < initialBudget } ?: initialBudget
    val boundedRequest = HttpRequest.newBuilder(request) { _, _ -> true }
        .timeout(effectiveTimeout)
        .build()
    val pending = try {
        sendAsync(boundedRequest, HttpResponse.BodyHandler { BoundedSiwcBodySubscriber(maxBodyBytes) })
    } catch (error: Exception) {
        throw networkFailure(error)
    }
    return try {
        pending.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)
    } catch (error: TimeoutException) {
        pending.cancel(true)
        throw siwcDeadlineExceeded(error)
    } catch (error: InterruptedException) {
        pending.cancel(true)
        Thread.currentThread().interrupt()
        throw networkFailure(error)
    } catch (error: ExecutionException) {
        val cause = error.cause ?: error
        when {
            cause.causedBy<SiwcBodyTooLargeException>() -> throw OpenAiSiwcException(
                "response_too_large",
                "ChatGPT returned an unexpectedly large response.",
                retryable = true,
                cause = cause,
            )

            cause.causedBy<HttpTimeoutException>() && deadlineLimited -> throw siwcDeadlineExceeded(cause)
            else -> throw networkFailure(cause)
        }
    }
}

internal fun remainingNanos(deadlineNanos: Long): Long {
    val remaining = if (deadlineNanos == Long.MAX_VALUE) Long.MAX_VALUE else deadlineNanos - System.nanoTime()
    if (remaining <= 0) throw siwcDeadlineExceeded()
    return remaining
}

private fun remainingDuration(deadlineNanos: Long): Duration = Duration.ofNanos(remainingNanos(deadlineNanos))

internal fun siwcDeadlineExceeded(cause: Throwable? = null) = OpenAiSiwcException(
    code = "deadline_exceeded",
    message = "The ChatGPT request deadline elapsed.",
    cause = cause,
)

private fun networkFailure(cause: Throwable) = OpenAiSiwcException(
    code = "network_error",
    message = "Could not reach ChatGPT.",
    retryable = true,
    cause = cause,
)

private inline fun <reified T : Throwable> Throwable.causedBy(): Boolean =
    generateSequence(this) { it.cause }.take(16).any { it is T }

private class SiwcBodyTooLargeException : RuntimeException()

private class BoundedSiwcBodySubscriber(private val maxBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var received = 0L
    private var terminated = false

    override fun getBody(): CompletionStage<ByteArray> = delegate.body

    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        delegate.onSubscribe(value)
    }

    override fun onNext(items: List<ByteBuffer>) {
        if (terminated) return
        received += items.sumOf { it.remaining().toLong() }
        if (received > maxBytes) {
            terminated = true
            subscription.cancel()
            delegate.onError(SiwcBodyTooLargeException())
        } else {
            delegate.onNext(items)
        }
    }

    override fun onError(error: Throwable) {
        if (!terminated) {
            terminated = true
            delegate.onError(error)
        }
    }

    override fun onComplete() {
        if (!terminated) {
            terminated = true
            delegate.onComplete()
        }
    }
}
