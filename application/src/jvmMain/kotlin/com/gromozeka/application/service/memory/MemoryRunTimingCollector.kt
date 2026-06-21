package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.model.memory.MemoryRun
import java.util.Collections
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

internal class MemoryRunTimingCollector(
    observers: List<MemoryRunLlmCallObserver> = emptyList(),
) {
    private val llmCalls = Collections.synchronizedList(mutableListOf<MemoryRun.LlmCallTiming>())
    private val observers = Collections.synchronizedList(observers.toMutableList())

    fun record(call: MemoryRun.LlmCallTiming) {
        llmCalls += call
        notifyObservers(MemoryRunLlmCallEvent.completed(call))
    }

    fun recordStart(
        stageName: String,
        attempt: Int,
        startedAt: Instant,
        timeoutMs: Long,
        logContext: String,
    ) {
        notifyObservers(
            MemoryRunLlmCallEvent.Started(
                stageName = stageName,
                attempt = attempt,
                startedAt = startedAt,
                timeoutMs = timeoutMs,
                logContext = logContext,
            )
        )
    }

    fun snapshot(): List<MemoryRun.LlmCallTiming> =
        synchronized(llmCalls) { llmCalls.toList() }

    private fun notifyObservers(event: MemoryRunLlmCallEvent) {
        synchronized(observers) { observers.toList() }.forEach { observer ->
            observer.onMemoryRunLlmCall(event)
        }
    }
}

private val currentMemoryRunTimingCollector = ThreadLocal<MemoryRunTimingCollector?>()

internal suspend fun <T> collectMemoryRunTimings(
    observers: List<MemoryRunLlmCallObserver> = emptyList(),
    block: suspend (MemoryRunTimingCollector) -> T,
): T {
    val collector = MemoryRunTimingCollector(observers)
    return withContext(currentMemoryRunTimingCollector.asContextElement(collector)) {
        block(collector)
    }
}

internal fun currentMemoryRunLlmCalls(): List<MemoryRun.LlmCallTiming> =
    currentMemoryRunTimingCollector.get()?.snapshot().orEmpty()

internal fun recordCurrentMemoryRunLlmCallStart(
    stageName: String,
    attempt: Int,
    startedAt: Instant,
    timeoutMs: Long,
    logContext: String,
) {
    currentMemoryRunTimingCollector.get()?.recordStart(
        stageName = stageName,
        attempt = attempt,
        startedAt = startedAt,
        timeoutMs = timeoutMs,
        logContext = logContext,
    )
}

internal fun recordCurrentMemoryRunLlmCall(
    stageName: String,
    attempt: Int,
    status: MemoryRun.LlmCallStatus,
    startedAt: Instant,
    completedAt: Instant,
    latencyMs: Long,
    timeoutMs: Long,
    finishReason: String?,
    usage: AiUsage?,
    logContext: String,
    errorText: String? = null,
) {
    currentMemoryRunTimingCollector.get()?.record(
        MemoryRun.LlmCallTiming(
            stageName = stageName,
            attempt = attempt,
            status = status,
            startedAt = startedAt,
            completedAt = completedAt,
            latencyMs = latencyMs,
            timeoutMs = timeoutMs,
            finishReason = finishReason,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            thinkingTokens = usage?.thinkingTokens,
            cacheCreationTokens = usage?.cacheCreationTokens,
            cacheReadTokens = usage?.cacheReadTokens,
            totalInputTokens = usage?.totalInputTokens,
            totalOutputTokens = usage?.totalOutputTokens,
            totalTokens = usage?.totalTokens,
            logContext = logContext,
            errorText = errorText?.take(1_000),
        )
    )
}

fun interface MemoryRunLlmCallObserver {
    fun onMemoryRunLlmCall(event: MemoryRunLlmCallEvent)
}

sealed interface MemoryRunLlmCallEvent {
    data class Started(
        val stageName: String,
        val attempt: Int,
        val startedAt: Instant,
        val timeoutMs: Long,
        val logContext: String,
    ) : MemoryRunLlmCallEvent

    data class Completed(
        val timing: MemoryRun.LlmCallTiming,
    ) : MemoryRunLlmCallEvent

    companion object {
        fun completed(call: MemoryRun.LlmCallTiming): MemoryRunLlmCallEvent =
            Completed(call)
    }
}
