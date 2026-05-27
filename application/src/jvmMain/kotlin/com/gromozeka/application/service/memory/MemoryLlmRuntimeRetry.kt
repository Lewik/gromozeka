package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiRuntime
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private val memoryLlmRetryLog = KLoggers.logger("MemoryLlmRuntimeRetry")

internal class MemoryLlmOutputTruncatedException(
    stageName: String,
    finishReason: String?,
    logContext: String,
    usage: AiUsage?,
) : IllegalStateException(
    "Memory LLM stage output was truncated: stage=$stageName finishReason=${finishReason ?: "unknown"} " +
        "${usage?.memoryUsageSummary().orEmpty()} $logContext".trim()
)

internal suspend fun AiRuntime.callMemoryStageWithRetry(
    request: AiRuntimeRequest,
    stageName: String,
    logContext: String,
    maxAttempts: Int = memoryLlmMaxAttempts(),
    timeoutMs: Long = memoryLlmTimeoutMs(),
): AiRuntimeResponse {
    var attempt = 1
    var delayMs = 750L

    while (true) {
        val attemptStartedAt = System.nanoTime()
        try {
            val response = withTimeout(timeoutMs) {
                call(request)
            }
            memoryLlmRetryLog.info {
                response.memoryStageUsageLogLine(
                    stageName = stageName,
                    attempt = attempt,
                    elapsedMs = attemptStartedAt.elapsedMs(),
                    logContext = logContext,
                )
            }
            response.throwIfOutputTruncated(stageName, logContext)
            return response
        } catch (error: Throwable) {
            if (error is CancellationException && error !is TimeoutCancellationException) {
                throw error
            }

            val retryable = error.isRetryableMemoryLlmFailure()
            if (!retryable || attempt >= maxAttempts) {
                memoryLlmRetryLog.warn(error) {
                    "Memory LLM stage failed permanently: stage=$stageName attempt=$attempt maxAttempts=$maxAttempts " +
                        "elapsedMs=${attemptStartedAt.elapsedMs()} timeoutMs=$timeoutMs $logContext " +
                        "retryable=$retryable error=${error.message}"
                }
                throw error
            }

            memoryLlmRetryLog.warn(error) {
                "Memory LLM stage failed, retrying: stage=$stageName attempt=$attempt nextAttempt=${attempt + 1} " +
                    "elapsedMs=${attemptStartedAt.elapsedMs()} delayMs=$delayMs timeoutMs=$timeoutMs " +
                    "$logContext error=${error.message}"
            }
            delay(delayMs)
            attempt += 1
            delayMs = (delayMs * 2).coerceAtMost(4_000L)
        }
    }
}

private fun memoryLlmMaxAttempts(): Int =
    System.getProperty("gromozeka.memory.llm.maxAttempts")
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 3

private fun memoryLlmTimeoutMs(): Long =
    System.getProperty("gromozeka.memory.llm.timeoutMs")
        ?.trim()
        ?.toLongOrNull()
        ?.coerceAtLeast(1_000L)
        ?: 90_000L

private fun AiRuntimeResponse.memoryStageUsageLogLine(
    stageName: String,
    attempt: Int,
    elapsedMs: Long,
    logContext: String,
): String {
    val responseUsage = this.usage
        ?: return "Memory LLM stage tokens unavailable: stage=$stageName attempt=$attempt elapsedMs=$elapsedMs " +
            "finishReason=${finishReason ?: "unknown"} $logContext"

    return "Memory LLM stage tokens: stage=$stageName attempt=$attempt elapsedMs=$elapsedMs " +
        responseUsage.memoryUsageSummary() +
        " finishReason=${finishReason ?: "unknown"}" +
        " $logContext"
}

private fun AiRuntimeResponse.throwIfOutputTruncated(stageName: String, logContext: String) {
    if (!finishReason.isMemoryOutputTruncationReason()) return
    throw MemoryLlmOutputTruncatedException(
        stageName = stageName,
        finishReason = finishReason,
        logContext = logContext,
        usage = usage,
    )
}

private fun String?.isMemoryOutputTruncationReason(): Boolean {
    val normalized = this
        ?.trim()
        ?.lowercase()
        ?.replace("-", "_")
        ?: return false

    return normalized == "length" ||
        normalized == "max_tokens" ||
        normalized == "max_output_tokens" ||
        normalized == "output_token_limit" ||
        normalized == "model_context_window_exceeded" ||
        normalized.contains("max_tokens") ||
        normalized.contains("output_token") ||
        normalized.contains("truncated")
}

internal fun AiUsage.memoryUsageSummary(): String =
    "prompt=$promptTokens cache_creation=$cacheCreationTokens cache_read=$cacheReadTokens " +
        "total_input=$totalInputTokens completion=$completionTokens thinking=$thinkingTokens " +
        "total_output=$totalOutputTokens total=$totalTokens"

private fun Long.elapsedMs(): Long =
    (System.nanoTime() - this) / 1_000_000

private fun Throwable.isRetryableMemoryLlmFailure(): Boolean {
    if (this is MemoryLlmOutputTruncatedException) {
        return false
    }

    if (this is TimeoutCancellationException) {
        return true
    }

    val chainText = generateSequence(this) { it.cause }
        .joinToString(" | ") { error ->
            "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}"
        }
        .lowercase()

    return listOf(
        "server_error",
        "rate_limit",
        "temporarily",
        "try again",
        "timeout",
        "timed out",
        "transport",
        "connection reset",
        "connection closed",
        "stream failed",
        "429",
        "500",
        "502",
        "503",
        "504",
    ).any { marker -> chainText.contains(marker) }
}
