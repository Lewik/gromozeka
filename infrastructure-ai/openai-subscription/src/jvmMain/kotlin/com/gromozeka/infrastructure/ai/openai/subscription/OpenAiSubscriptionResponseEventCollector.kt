package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

            "response.completed" -> {
                completed = envelope.response?.let(responseMapper::parseCompletedResponse)
                responseId = completed?.id ?: responseId
            }

            "error", "response.error", "response.failed" -> {
                throw streamFailure(payload, response = envelope.response)
            }

            "response.incomplete" -> {
                throw OpenAiSubscriptionRequestException(
                    statusCode = 400,
                    message = "OpenAI subscription stream returned an incomplete response: " +
                        incompleteResponseSummary(envelope.response, payload),
                )
            }
        }
    }

    fun toParsedResponse(): OpenAiSubscriptionParsedResponse {
        if (outputItems.isEmpty()) {
            completed?.output?.takeIf { it.isNotEmpty() }?.let(outputItems::addAll)
        }

        return OpenAiSubscriptionParsedResponse(
            outputItems = outputItems.toList(),
            completed = completed?.copy(id = responseId ?: completed!!.id),
        )
    }

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

    private fun incompleteResponseSummary(
        response: JsonObject?,
        payload: String,
    ): String {
        val parts = buildList {
            response?.stringField("status")?.let { add("status=$it") }
            response?.objectField("incomplete_details")?.stringField("reason")?.let { add("reason=$it") }
            response?.objectField("error")?.stringField("code")?.let { add("errorCode=$it") }
            response?.objectField("error")?.stringField("message")?.let { add("errorMessage=$it") }
            add("payload=${payload.oneLineForOpenAiSubscriptionLog(1_000)}")
        }
        return parts.joinToString(" ")
    }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.objectField(name: String): JsonObject? =
        this[name]?.let { element -> runCatching { element.jsonObject }.getOrNull() }

    private fun String.oneLineForOpenAiSubscriptionLog(limit: Int): String =
        trim()
            .replace(Regex("\\s+"), " ")
            .let { text -> if (text.length <= limit) text else text.take(limit - 3) + "..." }
}
