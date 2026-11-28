package com.gromozeka.infrastructure.ai.openai

import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.helpers.ResponseAccumulator
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseOutputItem
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.jvm.optionals.getOrNull

internal class OpenAiResponsesStreamException internal constructor(
    code: String?,
    parameter: String?,
) : RuntimeException("Responses stream returned a provider error") {
    val code = code.safeErrorCode()
    val parameter = parameter.safeErrorParameter()
}

/** Collect internally; no partial text or tool call escapes before a terminal response. */
internal fun collectOpenAiResponsesStream(client: OpenAIClient, params: ResponseCreateParams, timeout: Duration): CompletableFuture<Response> {
    val accumulator = ResponseAccumulator.create()
    val completedOutput = ConcurrentSkipListMap<Long, ResponseOutputItem>()
    var terminal = false
    val stream = client.async().responses().createStreaming(params, RequestOptions.builder().timeout(timeout).build())
    return try {
        stream.subscribe { event ->
            if (event.isError()) {
                val error = event.asError()
                throw OpenAiResponsesStreamException(
                    code = error.code().getOrNull(),
                    parameter = error.param().getOrNull(),
                )
            }
            event.outputItemDone().getOrNull()?.let { completedOutput[it.outputIndex()] = it.item() }
            accumulator.accumulate(event)
            if (event.isCompleted() || event.isIncomplete() || event.isFailed()) {
                check(!terminal) { "Responses stream returned multiple terminal events" }
                terminal = true
                stream.close()
            }
        }
        stream.onCompleteFuture().thenApply {
            check(terminal) { "Responses stream ended without a terminal response" }
            val response = accumulator.response()
            if (response.output().isEmpty() && completedOutput.isNotEmpty()) {
                response.toBuilder().output(completedOutput.values.toList()).build()
            } else {
                response
            }
        }.whenComplete { _, _ -> stream.close() }
    } catch (error: Throwable) {
        stream.close()
        throw error
    }
}

private fun String?.safeErrorCode(): String? = this?.takeIf { it.matches(SAFE_ERROR_CODE) }
private fun String?.safeErrorParameter(): String? = this?.takeIf { it.matches(SAFE_ERROR_PARAMETER) }

private val SAFE_ERROR_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val SAFE_ERROR_PARAMETER = Regex("[A-Za-z0-9][A-Za-z0-9._\\[\\]-]{0,255}")
