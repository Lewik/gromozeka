package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.model.memory.MemoryRun
import java.util.Collections
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

internal class MemoryRunTimingCollector {
    private val llmCalls = Collections.synchronizedList(mutableListOf<MemoryRun.LlmCallTiming>())

    fun record(call: MemoryRun.LlmCallTiming) {
        llmCalls += call
    }

    fun snapshot(): List<MemoryRun.LlmCallTiming> =
        synchronized(llmCalls) { llmCalls.toList() }
}

private val currentMemoryRunTimingCollector = ThreadLocal<MemoryRunTimingCollector?>()

internal suspend fun <T> collectMemoryRunTimings(
    block: suspend (MemoryRunTimingCollector) -> T,
): T {
    val collector = MemoryRunTimingCollector()
    return withContext(currentMemoryRunTimingCollector.asContextElement(collector)) {
        block(collector)
    }
}

internal fun currentMemoryRunLlmCalls(): List<MemoryRun.LlmCallTiming> =
    currentMemoryRunTimingCollector.get()?.snapshot().orEmpty()

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
