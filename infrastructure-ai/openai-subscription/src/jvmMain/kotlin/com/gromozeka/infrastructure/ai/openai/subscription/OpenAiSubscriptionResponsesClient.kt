package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Component
class OpenAiSubscriptionResponsesClient(
    private val responseMapper: OpenAiSubscriptionResponseMapper,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val httpClient = HttpClient.newBuilder().build()

    suspend fun create(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
    ): OpenAiSubscriptionParsedResponse = withContext(Dispatchers.IO) {
        val requestJson = json.encodeToString(requestBody)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(CODEX_RESPONSES_URL))
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "gromozeka")
            .header("session_id", conversationKey)
            .header("conversation_id", conversationKey)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))

        session.accountId?.let { requestBuilder.header("ChatGPT-Account-Id", it) }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 401) {
            val body = response.body().use(InputStream::readAllBytes).decodeToString()
            throw OpenAiSubscriptionUnauthorizedException(
                "OpenAI subscription request is unauthorized: ${responseMapper.extractErrorMessage(body)}"
            )
        }

        if (response.statusCode() !in 200..299) {
            val body = response.body().use(InputStream::readAllBytes).decodeToString()
            throw OpenAiSubscriptionRequestException(
                statusCode = response.statusCode(),
                message = "OpenAI subscription request failed: ${responseMapper.extractErrorMessage(body)}",
            )
        }

        response.body().use(::parseEventStream)
    }

    private fun parseEventStream(inputStream: InputStream): OpenAiSubscriptionParsedResponse {
        val outputItems = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var completed: OpenAiSubscriptionCompletedResponse? = null
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        fun dispatch() {
            if (currentEvent == null && dataLines.isEmpty()) return

            val payload = dataLines.joinToString("\n").trim()
            val eventName = currentEvent
            currentEvent = null
            dataLines.clear()

            if (payload.isBlank() || payload == "[DONE]") return

            val envelope = runCatching {
                json.decodeFromString<OpenAiSubscriptionSseEnvelope>(payload)
            }.getOrElse { error ->
                log.debug("Skipping unparseable SSE payload for event $eventName: ${error.message}")
                return
            }

            when (eventName ?: envelope.type) {
                "response.output_item.done" -> {
                    envelope.item?.let(outputItems::add)
                }

                "response.completed" -> {
                    completed = envelope.response?.let(responseMapper::parseCompletedResponse)
                }

                "response.failed" -> {
                    val body = envelope.response?.toString().orEmpty()
                    throw OpenAiSubscriptionRequestException(
                        statusCode = 400,
                        message = "OpenAI subscription stream failed: ${responseMapper.extractErrorMessage(body)}",
                    )
                }

                "response.incomplete" -> {
                    throw OpenAiSubscriptionRequestException(
                        statusCode = 400,
                        message = "OpenAI subscription stream returned an incomplete response",
                    )
                }
            }
        }

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                when {
                    rawLine.isBlank() -> dispatch()
                    rawLine.startsWith("event:") -> currentEvent = rawLine.removePrefix("event:").trim()
                    rawLine.startsWith("data:") -> dataLines += rawLine.removePrefix("data:").trimStart()
                }
            }
        }

        dispatch()

        if (outputItems.isEmpty()) {
            completed?.output?.takeIf { it.isNotEmpty() }?.let(outputItems::addAll)
        }

        return OpenAiSubscriptionParsedResponse(
            outputItems = outputItems,
            completed = completed,
        )
    }

    private companion object {
        const val CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
    }
}

data class OpenAiSubscriptionParsedResponse(
    val outputItems: List<kotlinx.serialization.json.JsonObject>,
    val completed: OpenAiSubscriptionCompletedResponse?,
)
