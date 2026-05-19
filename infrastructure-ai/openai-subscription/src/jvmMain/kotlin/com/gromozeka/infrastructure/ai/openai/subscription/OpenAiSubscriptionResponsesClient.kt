package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Component
class OpenAiSubscriptionResponsesClient(
    private val responseMapper: OpenAiSubscriptionResponseMapper,
    private val requestMapper: OpenAiSubscriptionRequestMapper,
    @Value("\${gromozeka.ai.openai-subscription.responses-url:https://chatgpt.com/backend-api/codex/responses}")
    private val responsesUrl: String,
    @Value("\${gromozeka.ai.openai-subscription.websocket-idle-ms:300000}")
    private val websocketIdleMs: Long,
    @Value("\${gromozeka.ai.openai-subscription.websocket-response-timeout-ms:300000}")
    private val websocketResponseTimeoutMs: Long,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val httpClient = HttpClient.newBuilder().build()
    private val websocketUrl = responsesUrl.toWebSocketUrl()
    private val webSocketSessions = ConcurrentHashMap<String, WebSocketSessionState>()

    suspend fun create(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
    ): OpenAiSubscriptionParsedResponse = withContext(Dispatchers.IO) {
        evictIdleWebSocketSessions()

        val wsResult = runCatching {
            createViaWebSocket(
                session = session,
                conversationKey = conversationKey,
                requestBody = requestBody,
            )
        }

        wsResult.getOrNull()?.let { return@withContext it }

        val wsFailure = wsResult.exceptionOrNull()
        when (wsFailure) {
            is OpenAiSubscriptionUnauthorizedException -> throw wsFailure
            is OpenAiSubscriptionRequestException -> throw wsFailure
            is OpenAiSubscriptionTransportException -> {
                dropWebSocketSession(conversationKey, "transport_failure")
                log.warn(
                    "OpenAI subscription websocket transport failed, falling back to HTTP: " +
                        "conversationKey=$conversationKey, reason=${wsFailure.message}"
                )
            }
            null -> Unit
            else -> {
                dropWebSocketSession(conversationKey, "transport_failure")
                log.warn(
                    "OpenAI subscription websocket transport failed, falling back to HTTP: " +
                        "conversationKey=$conversationKey, reason=${wsFailure.message}"
                )
            }
        }

        createViaHttp(
            session = session,
            conversationKey = conversationKey,
            requestBody = requestBody,
        )
    }

    private fun createViaHttp(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
    ): OpenAiSubscriptionParsedResponse {
        val requestJson = json.encodeToString(requestBody)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(responsesUrl))
            .timeout(Duration.ofMillis(websocketResponseTimeoutMs.coerceAtLeast(1L)))
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

        return response.body().use(::parseEventStream)
    }

    private suspend fun createViaWebSocket(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
    ): OpenAiSubscriptionParsedResponse {
        val sessionState = webSocketSessions.compute(conversationKey) { _, existing ->
            existing?.takeUnless { it.isExpired(websocketIdleMs) }
                ?: WebSocketSessionState(
                    conversationKey = conversationKey,
                    websocketUrl = websocketUrl,
                    responseTimeoutMs = websocketResponseTimeoutMs,
                )
        }!!

        return sessionState.execute(
            session = session,
            requestBody = requestBody,
            json = json,
            requestMapper = requestMapper,
            responseMapper = responseMapper,
        )
    }

    private fun parseEventStream(inputStream: InputStream): OpenAiSubscriptionParsedResponse {
        val collector = ResponseEventCollector(
            json = json,
            responseMapper = responseMapper,
            log = log,
        )
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        fun dispatch() {
            if (currentEvent == null && dataLines.isEmpty()) return

            val payload = dataLines.joinToString("\n").trim()
            val eventName = currentEvent
            currentEvent = null
            dataLines.clear()

            collector.accept(payload = payload, eventName = eventName)
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
        return collector.toParsedResponse()
    }

    private fun evictIdleWebSocketSessions() {
        val iterator = webSocketSessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isExpired(websocketIdleMs)) continue
            entry.value.close("idle_timeout")
            iterator.remove()
            log.info(
                "OpenAI subscription websocket session evicted: " +
                    "conversationKey=${entry.key}, reason=idle_timeout"
            )
        }
    }

    private fun dropWebSocketSession(
        conversationKey: String,
        reason: String,
    ) {
        webSocketSessions.remove(conversationKey)?.close(reason)
    }

    private fun String.toWebSocketUrl(): String {
        val uri = URI.create(this)
        val scheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            "wss", "ws" -> uri.scheme
            else -> error("Unsupported OpenAI subscription responses URL scheme: ${uri.scheme}")
        }

        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }

    private class ResponseEventCollector(
        private val json: Json,
        private val responseMapper: OpenAiSubscriptionResponseMapper,
        private val log: klog.KLogger,
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

            val envelope = runCatching {
                json.decodeFromString<OpenAiSubscriptionSseEnvelope>(payload)
            }.getOrElse { error ->
                log.debug("Skipping unparseable OpenAI subscription event for event $eventName: ${error.message}")
                return
            }

            when (eventName ?: envelope.type) {
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
                                    "type=$itemType, responseEvent=${eventName ?: envelope.type}"
                            )
                        }
                        outputItems += item
                    }
                }

                "response.completed" -> {
                    completed = envelope.response?.let(responseMapper::parseCompletedResponse)
                    responseId = completed?.id ?: responseId
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

        fun toParsedResponse(): OpenAiSubscriptionParsedResponse {
            if (outputItems.isEmpty()) {
                completed?.output?.takeIf { it.isNotEmpty() }?.let(outputItems::addAll)
            }

            return OpenAiSubscriptionParsedResponse(
                outputItems = outputItems.toList(),
                completed = completed?.copy(id = responseId ?: completed!!.id),
            )
        }
    }

    private sealed interface WebSocketInboundEvent {
        data class Text(val payload: String) : WebSocketInboundEvent
        data class Closed(val statusCode: Int, val reason: String) : WebSocketInboundEvent
        data class Failed(val error: Throwable) : WebSocketInboundEvent
    }

    private class WebSocketSessionState(
        private val conversationKey: String,
        private val websocketUrl: String,
        private val responseTimeoutMs: Long,
    ) {
        private val log = KLoggers.logger(this)
        private val boundedResponseTimeoutMs = responseTimeoutMs.coerceAtLeast(1L)
        private val httpClient = HttpClient.newBuilder().build()
        private val inboundEvents = LinkedBlockingQueue<WebSocketInboundEvent>()
        private val requestMutex = Mutex()
        private val open = AtomicBoolean(false)
        private val textBuffer = StringBuilder()

        @Volatile
        private var webSocket: WebSocket? = null

        @Volatile
        private var lastUsedAt: Long = System.currentTimeMillis()

        @Volatile
        private var lastTransportSignature: String? = null

        @Volatile
        private var lastResponseId: String? = null

        @Volatile
        private var expectedNextInputPrefix: List<JsonObject> = emptyList()

        suspend fun execute(
            session: OpenAiSubscriptionSession,
            requestBody: OpenAiSubscriptionResponsesRequest,
            json: Json,
            requestMapper: OpenAiSubscriptionRequestMapper,
            responseMapper: OpenAiSubscriptionResponseMapper,
        ): OpenAiSubscriptionParsedResponse = requestMutex.withLock {
            lastUsedAt = System.currentTimeMillis()
            ensureOpen(session = session)

            val transportSignature = requestMapper.buildTransportSignature(requestBody)
            val plan = planRequest(
                requestBody = requestBody,
                transportSignature = transportSignature,
            )

            val outboundRequest = when (plan.mode) {
                RequestMode.FULL -> requestBody.copy(previousResponseId = null)
                RequestMode.INCREMENTAL -> requestBody.copy(
                    input = requestBody.input.drop(expectedNextInputPrefix.size),
                    previousResponseId = lastResponseId,
                )
            }

            log.info(
                "OpenAI subscription websocket request: " +
                    "conversationKey=$conversationKey, mode=${plan.mode.name.lowercase()}, " +
                    "reason=${plan.reason}, fullInputItems=${requestBody.input.size}, " +
                    "sentInputItems=${outboundRequest.input.size}, tools=${requestBody.tools.size}, " +
                    "previousResponseId=${outboundRequest.previousResponseId != null}"
            )

            val parsed = try {
                sendAndAwait(
                    requestBody = outboundRequest,
                    json = json,
                    responseMapper = responseMapper,
                )
            } catch (error: Throwable) {
                close("request_failure")
                throw error
            }

            val responseId = parsed.completed?.id
                ?: throw OpenAiSubscriptionTransportException(
                    "OpenAI subscription websocket response completed without response id"
                )

            lastTransportSignature = transportSignature
            lastResponseId = responseId
            expectedNextInputPrefix = buildExpectedNextInputPrefix(
                requestBody = requestBody,
                replayItems = requestMapper.toReplayItems(parsed.outputItems),
            )
            lastUsedAt = System.currentTimeMillis()

            return parsed
        }

        fun isExpired(maxIdleMs: Long): Boolean {
            return System.currentTimeMillis() - lastUsedAt > maxIdleMs
        }

        fun close(reason: String) {
            open.set(false)
            webSocket?.abort()
            webSocket = null
            inboundEvents.clear()
            log.info(
                "OpenAI subscription websocket session closed: " +
                    "conversationKey=$conversationKey, reason=$reason"
            )
        }

        private fun planRequest(
            requestBody: OpenAiSubscriptionResponsesRequest,
            transportSignature: String,
        ): RequestPlan {
            val previousResponseId = lastResponseId
            if (previousResponseId.isNullOrBlank()) {
                return RequestPlan(mode = RequestMode.FULL, reason = "missing_previous_response")
            }

            if (lastTransportSignature != transportSignature) {
                return RequestPlan(mode = RequestMode.FULL, reason = "request_shape_changed")
            }

            if (!requestBody.input.startsWith(expectedNextInputPrefix)) {
                return RequestPlan(
                    mode = RequestMode.FULL,
                    reason = "input_not_strict_extension@" +
                        firstMismatchDescription(
                            expected = expectedNextInputPrefix,
                            actual = requestBody.input,
                        )
                )
            }

            return RequestPlan(mode = RequestMode.INCREMENTAL, reason = "strict_extension")
        }

        private fun buildExpectedNextInputPrefix(
            requestBody: OpenAiSubscriptionResponsesRequest,
            replayItems: List<JsonObject>,
        ): List<JsonObject> {
            val latestCompactionIndex = replayItems.indexOfLast { it.isCompactionItem() }
            val hasFunctionCalls = replayItems.any { it.isFunctionCallItem() }

            if (latestCompactionIndex >= 0 && !hasFunctionCalls) {
                val compactedPrefix = replayItems.drop(latestCompactionIndex)
                    .takeWhile { it.isCompactionItem() }

                log.info(
                    "OpenAI subscription websocket expected prefix collapsed to compacted base: " +
                        "conversationKey=$conversationKey, compactionItems=${compactedPrefix.size}, " +
                        "replayItems=${replayItems.size}"
                )

                return compactedPrefix
            }

            return requestBody.input + replayItems
        }

        private fun firstMismatchDescription(
            expected: List<JsonObject>,
            actual: List<JsonObject>,
        ): String {
            val mismatchIndex = expected.indices.firstOrNull { index -> index >= actual.size || expected[index] != actual[index] }
                ?: expected.size

            val expectedType = expected.getOrNull(mismatchIndex)?.itemType() ?: "eof"
            val actualType = actual.getOrNull(mismatchIndex)?.itemType() ?: "eof"

            return "index=$mismatchIndex,expectedType=$expectedType,actualType=$actualType," +
                "expectedSize=${expected.size},actualSize=${actual.size}"
        }

        private fun ensureOpen(
            session: OpenAiSubscriptionSession,
        ) {
            val existing = webSocket
            if (existing != null && open.get()) return

            inboundEvents.clear()
            val listener = object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    open.set(true)
                    webSocket.request(1)
                }

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletableFuture<*> {
                    synchronized(textBuffer) {
                        textBuffer.append(data)
                        if (last) {
                            inboundEvents.offer(WebSocketInboundEvent.Text(textBuffer.toString()))
                            textBuffer.setLength(0)
                        }
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }

                override fun onClose(
                    webSocket: WebSocket,
                    statusCode: Int,
                    reason: String,
                ): CompletableFuture<*> {
                    open.set(false)
                    inboundEvents.offer(WebSocketInboundEvent.Closed(statusCode, reason))
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(
                    webSocket: WebSocket,
                    error: Throwable,
                ) {
                    open.set(false)
                    inboundEvents.offer(WebSocketInboundEvent.Failed(error))
                }
            }

            val builder = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(boundedResponseTimeoutMs))
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("OpenAI-Beta", "responses=experimental")
                .header("originator", "gromozeka")
                .header("session_id", conversationKey)
                .header("conversation_id", conversationKey)

            session.accountId?.let { builder.header("ChatGPT-Account-Id", it) }

            try {
                webSocket = builder.buildAsync(URI.create(websocketUrl), listener)
                    .awaitTransport("opening OpenAI subscription websocket")
                log.info(
                    "OpenAI subscription websocket session ready: " +
                        "conversationKey=$conversationKey, url=$websocketUrl"
                )
            } catch (error: CompletionException) {
                throw error.toTransportException()
            } catch (error: Throwable) {
                throw OpenAiSubscriptionTransportException(
                    "Failed to open OpenAI subscription websocket session",
                    error,
                )
            }
        }

        private fun sendAndAwait(
            requestBody: OpenAiSubscriptionResponsesRequest,
            json: Json,
            responseMapper: OpenAiSubscriptionResponseMapper,
        ): OpenAiSubscriptionParsedResponse {
            val socket = webSocket
                ?: throw OpenAiSubscriptionTransportException("OpenAI subscription websocket session is not open")

            inboundEvents.clear()
            val collector = ResponseEventCollector(
                json = json,
                responseMapper = responseMapper,
                log = log,
            )

            val payload = json.encodeToString(
                OpenAiSubscriptionResponsesWebSocketRequest.serializer(),
                OpenAiSubscriptionResponsesWebSocketRequest.from(requestBody),
            )

            try {
                socket.sendText(payload, true)
                    .awaitTransport("sending OpenAI subscription websocket request")
            } catch (error: CompletionException) {
                throw error.toTransportException()
            } catch (error: Throwable) {
                throw OpenAiSubscriptionTransportException(
                    "Failed to send OpenAI subscription websocket request",
                    error,
                )
            }

            val responseStartedAt = System.nanoTime()
            while (true) {
                val remainingTimeoutMs = remainingResponseTimeoutMs(responseStartedAt)
                if (remainingTimeoutMs <= 0L) {
                    throw OpenAiSubscriptionTransportException(
                        "Timed out waiting for OpenAI subscription websocket response completion"
                    )
                }

                when (val event = inboundEvents.poll(remainingTimeoutMs, TimeUnit.MILLISECONDS)) {
                    null -> {
                        throw OpenAiSubscriptionTransportException(
                            "Timed out waiting for OpenAI subscription websocket response"
                        )
                    }

                    is WebSocketInboundEvent.Text -> {
                        collector.accept(payload = event.payload)
                        if (collector.isCompleted) {
                            return collector.toParsedResponse()
                        }
                    }

                    is WebSocketInboundEvent.Closed -> {
                        throw OpenAiSubscriptionTransportException(
                            "OpenAI subscription websocket closed: " +
                                "status=${event.statusCode}, reason=${event.reason}"
                        )
                    }

                    is WebSocketInboundEvent.Failed -> {
                        throw OpenAiSubscriptionTransportException(
                            "OpenAI subscription websocket failed: ${event.error.message}",
                            event.error,
                        )
                    }
                }
            }
        }

        private fun remainingResponseTimeoutMs(responseStartedAt: Long): Long {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - responseStartedAt)
            return boundedResponseTimeoutMs - elapsedMs
        }

        private fun <T> CompletableFuture<T>.awaitTransport(action: String): T {
            try {
                return get(boundedResponseTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                cancel(true)
                throw OpenAiSubscriptionTransportException(
                    "Timed out while $action",
                    error,
                )
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OpenAiSubscriptionTransportException(
                    "Interrupted while $action",
                    error,
                )
            } catch (error: ExecutionException) {
                throw CompletionException(error.cause ?: error).toTransportException()
            }
        }

        private fun CompletionException.toTransportException(): OpenAiSubscriptionApiException {
            val cause = cause ?: this
            if (cause is OpenAiSubscriptionApiException) return cause

            if (cause is WebSocketHandshakeException) {
                val statusCode = cause.response.statusCode()
                return if (statusCode == 401) {
                    OpenAiSubscriptionUnauthorizedException(
                        "OpenAI subscription websocket is unauthorized"
                    )
                } else {
                    OpenAiSubscriptionTransportException(
                        "OpenAI subscription websocket handshake failed: HTTP $statusCode",
                        cause,
                    )
                }
            }

            return OpenAiSubscriptionTransportException(
                "OpenAI subscription websocket transport failed: ${cause.message}",
                cause,
            )
        }

        private fun List<JsonObject>.startsWith(prefix: List<JsonObject>): Boolean {
            if (prefix.size > size) return false
            return indices.take(prefix.size).all { index -> this[index] == prefix[index] }
        }

        private fun JsonObject.isCompactionItem(): Boolean {
            return itemType() in setOf("compaction", "compaction_summary")
        }

        private fun JsonObject.isFunctionCallItem(): Boolean {
            return itemType() == "function_call"
        }

        private fun JsonObject.itemType(): String {
            return this["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        }

        private data class RequestPlan(
            val mode: RequestMode,
            val reason: String,
        )

        private enum class RequestMode {
            FULL,
            INCREMENTAL,
        }
    }
}

data class OpenAiSubscriptionParsedResponse(
    val outputItems: List<JsonObject>,
    val completed: OpenAiSubscriptionCompletedResponse?,
)
