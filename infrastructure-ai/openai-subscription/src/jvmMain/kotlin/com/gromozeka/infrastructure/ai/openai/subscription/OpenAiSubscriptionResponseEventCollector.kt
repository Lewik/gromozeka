package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class OpenAiSubscriptionResponseEventCollector(
    private val json: Json,
    private val responseMapper: OpenAiSubscriptionResponseMapper,
    private val log: KLogger,
) {
    private val outputItems = mutableListOf<JsonObject>()
    private var completed: OpenAiSubscriptionCompletedResponse? = null
    private var responseId: String? = null
    val isCompleted: Boolean
        get() = completed != null

    fun accept(
        payload: String,
        eventName: String? = null,
    ) {
        if (payload.isBlank() || payload == "[DONE]") return

        val payloadObject = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        val eventType = eventName ?: payloadObject
            ?.get("type")
            ?.jsonPrimitive
            ?.contentOrNull

        if (eventType == null && payloadObject?.looksLikeError() == true) {
            throw streamFailure(payload, response = payloadObject["response"] as? JsonObject)
        }

        val envelope = runCatching {
            json.decodeFromString<OpenAiSubscriptionSseEnvelope>(payload)
        }.getOrElse { error ->
            log.debug("Skipping unparseable OpenAI subscription event for event $eventName: ${error.message}")
            return
        }

        when (eventType) {
            "response.created" -> {
                responseId = envelope.response
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }

            "response.output_item.done" -> {
                envelope.item?.let { item ->
                    val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                    if (itemType == "compaction" || itemType == "compaction_summary") {
                        log.info(
                            "OpenAI subscription auto-compaction item received: " +
                                "type=$itemType, responseEvent=$eventType"
                        )
                    }
                    outputItems += item
                }
            }

            "response.completed", "response.incomplete" -> {
                completed = envelope.response?.let(responseMapper::parseCompletedResponse)
                completed?.inputTransformations.orEmpty().let { transformations ->
                    val removedReasoning = transformations.removedReasoningLogSummary()
                    when {
                        removedReasoning != null -> log.warn(
                            "OpenAI subscription retained reasoning removed: $removedReasoning"
                        )
                        transformations.isNotEmpty() -> log.debug(
                            "OpenAI subscription input transformations received: count=${transformations.size}"
                        )
                    }
                }
                responseId = completed?.id ?: responseId
            }

            "error", "response.error", "response.failed" -> {
                throw streamFailure(payload, response = envelope.response)
            }

        }
    }

    fun toParsedResponse(): OpenAiSubscriptionParsedResponse {
        val finalOutputItems = completed?.output?.takeIf { it.isNotEmpty() }
        if (finalOutputItems != null && outputItems.isNotEmpty() && outputItems != finalOutputItems) {
            log.warn(
                "OpenAI subscription streamed output differs from final output; using final output: " +
                    "streamedItems=${outputItems.size}, finalItems=${finalOutputItems.size}, " +
                    "streamedToolCalls=${outputItems.toolCallCount()}, finalToolCalls=${finalOutputItems.toolCallCount()}"
            )
        }

        return OpenAiSubscriptionParsedResponse(
            outputItems = finalOutputItems ?: outputItems.toList(),
            completed = completed?.copy(id = responseId ?: completed!!.id),
        )
    }

    private fun List<JsonObject>.toolCallCount(): Int = count { it["type"] == JsonPrimitive("function_call") }

    private fun streamFailure(
        payload: String,
        response: JsonObject?,
    ): OpenAiSubscriptionRequestException {
        val body = response?.toString()?.takeIf { it.isNotBlank() } ?: payload
        return OpenAiSubscriptionRequestException(
            statusCode = 400,
            message = "OpenAI subscription stream failed: ${responseMapper.extractErrorMessage(body)}",
        )
    }

    private fun JsonObject.looksLikeError(): Boolean =
        containsKey("error") ||
            containsKey("detail") ||
            containsKey("message")

}

internal fun List<OpenAiSubscriptionInputTransformation>.removedReasoningLogSummary(): String? {
    val removals = filter { it.action == "removed" && it.type == "reasoning" }
    if (removals.isEmpty()) return null
    val reasons = removals.groupingBy { transformation ->
        transformation.reason?.takeIf { SAFE_TRANSFORMATION_REASON.matches(it) } ?: "unknown"
    }.eachCount().toSortedMap()
    return "count=${removals.size} reasons=${reasons.entries.joinToString(",") { (reason, count) -> "$reason:$count" }}"
}

private val SAFE_TRANSFORMATION_REASON = Regex("[a-zA-Z0-9_.-]{1,64}")
