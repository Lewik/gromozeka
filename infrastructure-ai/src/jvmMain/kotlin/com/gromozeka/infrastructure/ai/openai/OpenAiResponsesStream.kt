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

/** Collect internally; no partial text or tool call escapes before a terminal response. */
internal fun collectOpenAiResponsesStream(client: OpenAIClient, params: ResponseCreateParams, timeout: Duration): CompletableFuture<Response> {
    val accumulator = ResponseAccumulator.create()
    val completedOutput = ConcurrentSkipListMap<Long, ResponseOutputItem>()
    var terminal = false
    val stream = client.async().responses().createStreaming(params, RequestOptions.builder().timeout(timeout).build())
    return try {
        stream.subscribe { event ->
            check(!event.isError()) { "Responses stream returned an error event" }
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
