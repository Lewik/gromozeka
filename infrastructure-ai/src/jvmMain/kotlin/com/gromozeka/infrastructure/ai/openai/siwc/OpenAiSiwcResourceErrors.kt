package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesStreamException
import com.openai.errors.OpenAIServiceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val resourceErrorJson = Json { ignoreUnknownKeys = true }

internal fun openAiSiwcResourceError(
    status: Int,
    body: ByteArray,
    requestId: String?,
): OpenAiSiwcResourceError {
    val payload = try {
        resourceErrorJson.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (_: Exception) {
        null
    }
    val error = payload?.get("error")
    val nested = error as? JsonObject
    return OpenAiSiwcResourceError(
        status = status,
        code = (nested?.string("code") ?: (error as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: payload?.string("code"))
            .safeSiwcErrorCode(),
        parameter = (nested?.string("param") ?: payload?.string("param")).safeSiwcErrorParameter(),
        requestId = requestId.safeSiwcRequestId(),
    )
}

internal fun Throwable.openAiSiwcResourceError(
    fallbackStatus: Int?,
    fallbackRequestId: String? = null,
): OpenAiSiwcResourceError? {
    val causes = generateSequence(this) { it.cause }.take(16).toList()
    val service = causes.filterIsInstance<OpenAIServiceException>().firstOrNull()
    val stream = causes.filterIsInstance<OpenAiResponsesStreamException>().firstOrNull()
    val status = service?.statusCode() ?: fallbackStatus ?: return null
    return OpenAiSiwcResourceError(
        status = status,
        code = (service?.code()?.orElse(null) ?: stream?.code).safeSiwcErrorCode(),
        parameter = (service?.param()?.orElse(null) ?: stream?.parameter).safeSiwcErrorParameter(),
        requestId = service?.headers()?.values("x-request-id")
            ?.firstNotNullOfOrNull(String::safeSiwcRequestId)
            ?: fallbackRequestId.safeSiwcRequestId(),
    )
}

internal fun OpenAiSiwcResourceError.toException(
    fallbackCode: String,
    message: String,
    retryable: Boolean,
): OpenAiSiwcException = OpenAiSiwcException(
    code = code ?: fallbackCode,
    message = message,
    retryable = retryable,
    status = status,
    requestId = requestId,
    providerCode = code,
    providerParameter = parameter,
    outcome = if (status in 400..499 && status !in setOf(408, 409)) {
        AiCallFailure.Outcome.REJECTED
    } else {
        AiCallFailure.Outcome.UNKNOWN
    },
    attempts = 1,
)

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
