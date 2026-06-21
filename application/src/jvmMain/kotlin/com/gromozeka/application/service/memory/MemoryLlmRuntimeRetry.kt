package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.AiRuntime
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock

private val memoryLlmRetryLog = KLoggers.logger("MemoryLlmRuntimeRetry")

internal class MemoryLlmOutputTruncatedException(
    stageName: String,
    val finishReason: String?,
    logContext: String,
    val usage: AiUsage?,
) : IllegalStateException(
    "Memory LLM stage output was truncated: stage=$stageName finishReason=${finishReason ?: "unknown"} " +
        "${usage?.memoryUsageSummary().orEmpty()} $logContext".trim()
)

internal suspend fun AiRuntime.callMemoryStageWithRetry(
    request: AiRuntimeRequest,
    stageName: String,
    logContext: String,
    maxAttempts: Int = memoryLlmMaxAttempts(),
    timeoutMs: Long = memoryLlmTimeoutMs(stageName),
): AiRuntimeResponse {
    var attempt = 1
    var delayMs = 750L

    while (true) {
        val attemptStartedAt = System.nanoTime()
        val attemptWallStartedAt = Clock.System.now()
        try {
            recordCurrentMemoryRunLlmCallStart(
                stageName = stageName,
                attempt = attempt,
                startedAt = attemptWallStartedAt,
                timeoutMs = timeoutMs,
                logContext = logContext,
            )
            memoryLlmRetryLog.info {
                "Memory LLM stage start: stage=$stageName attempt=$attempt timeoutMs=$timeoutMs $logContext"
            }
            val response = withTimeout(timeoutMs) {
                call(request)
            }
            val elapsedMs = attemptStartedAt.elapsedMs()
            val completedAt = Clock.System.now()
            memoryLlmRetryLog.info {
                response.memoryStageUsageLogLine(
                    stageName = stageName,
                    attempt = attempt,
                    elapsedMs = elapsedMs,
                    logContext = logContext,
                )
            }
            response.throwIfOutputTruncated(stageName, logContext)
            recordCurrentMemoryRunLlmCall(
                stageName = stageName,
                attempt = attempt,
                status = MemoryRun.LlmCallStatus.SUCCESS,
                startedAt = attemptWallStartedAt,
                completedAt = completedAt,
                latencyMs = elapsedMs,
                timeoutMs = timeoutMs,
                finishReason = response.finishReason,
                usage = response.usage,
                logContext = logContext,
            )
            return response
        } catch (error: Throwable) {
            val elapsedMs = attemptStartedAt.elapsedMs()
            val completedAt = Clock.System.now()
            if (error is CancellationException && error !is TimeoutCancellationException) {
                recordCurrentMemoryRunLlmCall(
                    stageName = stageName,
                    attempt = attempt,
                    status = MemoryRun.LlmCallStatus.CANCELLED,
                    startedAt = attemptWallStartedAt,
                    completedAt = completedAt,
                    latencyMs = elapsedMs,
                    timeoutMs = timeoutMs,
                    finishReason = null,
                    usage = null,
                    logContext = logContext,
                    errorText = error.message ?: error::class.simpleName.orEmpty(),
                )
                throw error
            }

            val retryable = error.isRetryableMemoryLlmFailure()
            recordCurrentMemoryRunLlmCall(
                stageName = stageName,
                attempt = attempt,
                status = if (!retryable || attempt >= maxAttempts) {
                    MemoryRun.LlmCallStatus.FAILED
                } else {
                    MemoryRun.LlmCallStatus.RETRYING
                },
                startedAt = attemptWallStartedAt,
                completedAt = completedAt,
                latencyMs = elapsedMs,
                timeoutMs = timeoutMs,
                finishReason = (error as? MemoryLlmOutputTruncatedException)?.finishReason,
                usage = (error as? MemoryLlmOutputTruncatedException)?.usage,
                logContext = logContext,
                errorText = error.message ?: error::class.simpleName.orEmpty(),
            )
            if (!retryable || attempt >= maxAttempts) {
                memoryLlmRetryLog.warn(error) {
                    "Memory LLM stage failed permanently: stage=$stageName attempt=$attempt maxAttempts=$maxAttempts " +
                        "elapsedMs=$elapsedMs timeoutMs=$timeoutMs $logContext " +
                        "retryable=$retryable error=${error.message}"
                }
                throw error
            }

            memoryLlmRetryLog.warn(error) {
                "Memory LLM stage failed, retrying: stage=$stageName attempt=$attempt nextAttempt=${attempt + 1} " +
                    "elapsedMs=$elapsedMs delayMs=$delayMs timeoutMs=$timeoutMs " +
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

private fun memoryLlmTimeoutMs(stageName: String): Long =
    System.getProperty("gromozeka.memory.llm.timeoutMs")
        ?.trim()
        ?.toLongOrNull()
        ?.coerceAtLeast(1_000L)
        ?: when (stageName) {
            "claim-extractor" -> 1_200_000L
            else -> 90_000L
        }

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
        "429",
        "500",
        "502",
        "503",
        "504",
    ).any { marker -> chainText.contains(marker) }
}
