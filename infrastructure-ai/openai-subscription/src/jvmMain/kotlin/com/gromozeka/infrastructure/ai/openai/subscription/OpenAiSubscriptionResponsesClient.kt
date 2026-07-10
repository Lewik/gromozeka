package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiModelConfiguration
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.charset.StandardCharsets
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
    @Value("\${gromozeka.ai.openai-subscription.base-url:https://chatgpt.com/backend-api/codex}")
    private val baseUrl: String,
    @Value("\${gromozeka.ai.openai-subscription.client-version:1.4.9}")
    private val clientVersion: String,
    @Value("\${gromozeka.ai.openai-subscription.websocket-idle-ms:300000}")
    private val websocketIdleMs: Long,
    @Value("\${gromozeka.ai.openai-subscription.websocket-response-timeout-ms:1200000}")
    private val websocketResponseTimeoutMs: Long,
    @Value("\${gromozeka.ai.openai-subscription.websocket-transport-timeout-ms:30000}")
    private val websocketTransportTimeoutMs: Long,
    @Value("\${gromozeka.ai.openai-subscription.http-response-timeout-ms:\${gromozeka.memory.llm.timeoutMs:300000}}")
    private val httpResponseTimeoutMs: Long,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val httpClient = HttpClient.newBuilder().build()
    private val responsesUrl = "${baseUrl.trimEnd('/')}/responses"
    private val websocketUrl = responsesUrl.toWebSocketUrl()
    private val webSocketSessions = ConcurrentHashMap<String, WebSocketSessionState>()

    suspend fun create(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
        modelProfile: OpenAiSubscriptionModelProfile,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): OpenAiSubscriptionParsedResponse = withContext(Dispatchers.IO) {
        evictIdleWebSocketSessions()

        val wsResult = runCatching {
            createViaWebSocket(
                session = session,
                conversationKey = conversationKey,
                requestBody = requestBody,
                modelProfile = modelProfile,
                assistantResponseFormat = assistantResponseFormat,
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
            modelProfile = modelProfile,
        )
    }

    private fun createViaHttp(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
        modelProfile: OpenAiSubscriptionModelProfile,
    ): OpenAiSubscriptionParsedResponse {
        val transportRequest = requestMapper.toTransportRequest(requestBody, modelProfile)
        val requestJson = json.encodeToString(transportRequest)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(responsesUrl))
            .timeout(Duration.ofMillis(httpResponseTimeoutMs.coerceAtLeast(1L)))
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("originator", OPENAI_SUBSCRIPTION_ORIGINATOR)
            .header("User-Agent", openAiSubscriptionUserAgent(clientVersion))
            .header("session_id", conversationKey)
            .header("conversation_id", conversationKey)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))

        session.accountId?.let { requestBuilder.header("ChatGPT-Account-Id", it) }
        if (modelProfile.useResponsesLite) {
            requestBuilder.header("x-openai-internal-codex-responses-lite", "true")
        }

        val response = httpClient
            .sendAsync(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            )
            .awaitHttpResponse(httpResponseTimeoutMs.coerceAtLeast(1L))
        if (response.statusCode() == 401) {
            val body = response.body()
            throw OpenAiSubscriptionUnauthorizedException(
                "OpenAI subscription request is unauthorized: ${responseMapper.extractErrorMessage(body)}"
            )
        }

        if (response.statusCode() !in 200..299) {
            val body = response.body()
            throw OpenAiSubscriptionRequestException(
                statusCode = response.statusCode(),
                message = "OpenAI subscription request failed: ${responseMapper.extractErrorMessage(body)}",
            )
        }

        return parseEventStream(response.body())
    }

    private suspend fun createViaWebSocket(
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        requestBody: OpenAiSubscriptionResponsesRequest,
        modelProfile: OpenAiSubscriptionModelProfile,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): OpenAiSubscriptionParsedResponse {
        val sessionState = webSocketSessions.compute(conversationKey) { _, existing ->
            existing?.takeUnless { it.isExpired(websocketIdleMs) }
                ?: WebSocketSessionState(
                    conversationKey = conversationKey,
                    websocketUrl = websocketUrl,
                    responseTimeoutMs = websocketResponseTimeoutMs,
                    transportTimeoutMs = websocketTransportTimeoutMs,
                )
        }!!

        return sessionState.execute(
            session = session,
            requestBody = requestBody,
            json = json,
            requestMapper = requestMapper,
            responseMapper = responseMapper,
            modelProfile = modelProfile,
            assistantResponseFormat = assistantResponseFormat,
            userAgent = openAiSubscriptionUserAgent(clientVersion),
        )
    }

    private fun parseEventStream(body: String): OpenAiSubscriptionParsedResponse {
        val collector = OpenAiSubscriptionResponseEventCollector(
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

        body.lineSequence().forEach { rawLine ->
            when {
                rawLine.isBlank() -> dispatch()
                rawLine.startsWith("event:") -> currentEvent = rawLine.removePrefix("event:").trim()
                rawLine.startsWith("data:") -> dataLines += rawLine.removePrefix("data:").trimStart()
            }
        }

        dispatch()
        return collector.toParsedResponse()
    }

    private fun CompletableFuture<HttpResponse<String>>.awaitHttpResponse(timeoutMs: Long): HttpResponse<String> {
        return try {
            get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            cancel(true)
            throw OpenAiSubscriptionTransportException(
                "Timed out waiting for OpenAI subscription HTTP response",
                error,
            )
        } catch (error: InterruptedException) {
            cancel(true)
            Thread.currentThread().interrupt()
            throw OpenAiSubscriptionTransportException(
                "Interrupted while waiting for OpenAI subscription HTTP response",
                error,
            )
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is OpenAiSubscriptionApiException) throw cause
            throw OpenAiSubscriptionTransportException(
                "OpenAI subscription HTTP transport failed: ${cause.message}",
                cause,
            )
        } catch (error: CompletionException) {
            val cause = error.cause ?: error
            if (cause is OpenAiSubscriptionApiException) throw cause
            throw OpenAiSubscriptionTransportException(
                "OpenAI subscription HTTP transport failed: ${cause.message}",
                cause,
            )
        }
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

    private sealed interface WebSocketInboundEvent {
        data class Text(val payload: String) : WebSocketInboundEvent
        data class Closed(val statusCode: Int, val reason: String) : WebSocketInboundEvent
        data class Failed(val error: Throwable) : WebSocketInboundEvent
    }

    private class WebSocketSessionState(
        private val conversationKey: String,
        private val websocketUrl: String,
        private val responseTimeoutMs: Long,
        private val transportTimeoutMs: Long,
    ) {
        private val log = KLoggers.logger(this)
        private val boundedResponseTimeoutMs = responseTimeoutMs.coerceAtLeast(1L)
        private val boundedTransportTimeoutMs = transportTimeoutMs.coerceAtLeast(1L)
        private val httpClient = HttpClient.newBuilder().build()
        private val inboundEvents = LinkedBlockingQueue<WebSocketInboundEvent>()
        private val requestMutex = Mutex()
        private val open = AtomicBoolean(false)
        private val textBuffer = StringBuilder()

        @Volatile
        private var webSocket: WebSocket? = null

        @Volatile
        private var lastUsedAt: Long = System.currentTimeMillis()

        private val incrementalState = OpenAiSubscriptionIncrementalState()

        suspend fun execute(
            session: OpenAiSubscriptionSession,
            requestBody: OpenAiSubscriptionResponsesRequest,
            json: Json,
            requestMapper: OpenAiSubscriptionRequestMapper,
            responseMapper: OpenAiSubscriptionResponseMapper,
            modelProfile: OpenAiSubscriptionModelProfile,
            assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
            userAgent: String,
        ): OpenAiSubscriptionParsedResponse = requestMutex.withLock {
            lastUsedAt = System.currentTimeMillis()
            ensureOpen(session = session, userAgent = userAgent)

            val transportRequest = requestMapper.toTransportRequest(requestBody, modelProfile)
            val transportSignature = requestMapper.buildTransportSignature(transportRequest)
            val plan = incrementalState.plan(
                transportRequest = transportRequest,
                transportSignature = transportSignature,
            )

            log.info(
                "OpenAI subscription websocket request: " +
                    "conversationKey=$conversationKey, mode=${plan.mode.name.lowercase()}, " +
                    "reason=${plan.reason}, fullInputItems=${transportRequest.input.size}, " +
                    "sentInputItems=${plan.request.input.size}, tools=${requestBody.tools.orEmpty().size}, " +
                    "previousResponseId=${plan.request.previousResponseId != null}"
            )

            val parsed = try {
                val coroutineContext = currentCoroutineContext()
                sendAndAwait(
                    requestBody = plan.request,
                    json = json,
                    responseMapper = responseMapper,
                    useResponsesLite = modelProfile.useResponsesLite,
                    ensureActive = { coroutineContext.ensureActive() },
                )
            } catch (error: Throwable) {
                close("request_failure")
                throw error
            }

            val responseId = parsed.completed?.id
                ?: throw OpenAiSubscriptionTransportException(
                    "OpenAI subscription websocket response completed without response id"
                )

            incrementalState.record(
                transportSignature = transportSignature,
                responseId = responseId,
                expectedNextInputPrefix = buildExpectedNextInputPrefix(
                    requestBody = transportRequest,
                    replayItems = requestMapper.toReplayItems(
                        outputItems = parsed.outputItems,
                        assistantResponseFormat = assistantResponseFormat,
                    ),
                ),
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
            incrementalState.clear()
            log.info(
                "OpenAI subscription websocket session closed: " +
                    "conversationKey=$conversationKey, reason=$reason"
            )
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

        private fun ensureOpen(
            session: OpenAiSubscriptionSession,
            userAgent: String,
        ) {
            val existing = webSocket
            if (existing != null && open.get()) return

            incrementalState.clear()

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
                .connectTimeout(Duration.ofMillis(boundedTransportTimeoutMs))
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("OpenAI-Beta", "responses_websockets=2026-02-06")
                .header("originator", OPENAI_SUBSCRIPTION_ORIGINATOR)
                .header("User-Agent", userAgent)
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
            useResponsesLite: Boolean,
            ensureActive: () -> Unit,
        ): OpenAiSubscriptionParsedResponse {
            val socket = webSocket
                ?: throw OpenAiSubscriptionTransportException("OpenAI subscription websocket session is not open")

            inboundEvents.clear()
            val collector = OpenAiSubscriptionResponseEventCollector(
                json = json,
                responseMapper = responseMapper,
                log = log,
            )

            val payload = json.encodeToString(
                OpenAiSubscriptionResponsesWebSocketRequest.serializer(),
                OpenAiSubscriptionResponsesWebSocketRequest.from(
                    request = requestBody,
                    useResponsesLite = useResponsesLite,
                ),
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

            val responseDeadline = OpenAiSubscriptionResponseDeadline.after(boundedResponseTimeoutMs)
            while (true) {
                ensureActive()
                val remainingTimeoutMs = responseDeadline.remainingMs()
                if (remainingTimeoutMs <= 0L) {
                    throw OpenAiSubscriptionTransportException(
                        "Timed out waiting for OpenAI subscription websocket response completion"
                    )
                }

                val pollTimeoutMs = remainingTimeoutMs.coerceAtMost(WEBSOCKET_RESPONSE_POLL_SLICE_MS)
                when (val event = inboundEvents.poll(pollTimeoutMs, TimeUnit.MILLISECONDS)) {
                    null -> Unit

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

        private fun <T> CompletableFuture<T>.awaitTransport(action: String): T {
            try {
                return get(boundedTransportTimeoutMs, TimeUnit.MILLISECONDS)
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

        private fun JsonObject.isCompactionItem(): Boolean {
            return itemType() in setOf("compaction", "compaction_summary")
        }

        private fun JsonObject.isFunctionCallItem(): Boolean {
            return itemType() == "function_call"
        }

        private fun JsonObject.itemType(): String {
            return this["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        }

    }
}

internal class OpenAiSubscriptionIncrementalState {
    private var lastTransportSignature: String? = null
    private var lastResponseId: String? = null
    private var expectedNextInputPrefix: List<JsonObject> = emptyList()

    @Synchronized
    fun plan(
        transportRequest: OpenAiSubscriptionResponsesRequest,
        transportSignature: String,
    ): OpenAiSubscriptionRequestPlan {
        val fullRequest = transportRequest.copy(previousResponseId = null)
        val previousResponseId = lastResponseId
        if (previousResponseId.isNullOrBlank()) {
            return OpenAiSubscriptionRequestPlan(
                request = fullRequest,
                mode = OpenAiSubscriptionRequestMode.FULL,
                reason = "missing_previous_response",
            )
        }

        if (lastTransportSignature != transportSignature) {
            return OpenAiSubscriptionRequestPlan(
                request = fullRequest,
                mode = OpenAiSubscriptionRequestMode.FULL,
                reason = "request_shape_changed",
            )
        }

        if (!transportRequest.input.startsWith(expectedNextInputPrefix)) {
            return OpenAiSubscriptionRequestPlan(
                request = fullRequest,
                mode = OpenAiSubscriptionRequestMode.FULL,
                reason = "input_not_strict_extension@" + firstMismatchDescription(
                    expected = expectedNextInputPrefix,
                    actual = transportRequest.input,
                ),
            )
        }

        return OpenAiSubscriptionRequestPlan(
            request = transportRequest.copy(
                input = transportRequest.input.drop(expectedNextInputPrefix.size),
                previousResponseId = previousResponseId,
            ),
            mode = OpenAiSubscriptionRequestMode.INCREMENTAL,
            reason = "strict_extension",
        )
    }

    @Synchronized
    fun record(
        transportSignature: String,
        responseId: String,
        expectedNextInputPrefix: List<JsonObject>,
    ) {
        lastTransportSignature = transportSignature
        lastResponseId = responseId
        this.expectedNextInputPrefix = expectedNextInputPrefix
    }

    @Synchronized
    fun clear() {
        lastTransportSignature = null
        lastResponseId = null
        expectedNextInputPrefix = emptyList()
    }

    private fun List<JsonObject>.startsWith(prefix: List<JsonObject>): Boolean {
        if (prefix.size > size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
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

    private fun JsonObject.itemType(): String =
        this["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
}

internal data class OpenAiSubscriptionRequestPlan(
    val request: OpenAiSubscriptionResponsesRequest,
    val mode: OpenAiSubscriptionRequestMode,
    val reason: String,
)

internal enum class OpenAiSubscriptionRequestMode {
    FULL,
    INCREMENTAL,
}

private const val WEBSOCKET_RESPONSE_POLL_SLICE_MS = 1_000L

internal class OpenAiSubscriptionResponseDeadline private constructor(
    private val deadlineEpochMs: Long,
    private val nowEpochMs: () -> Long,
) {
    fun remainingMs(): Long = deadlineEpochMs - nowEpochMs()

    companion object {
        fun after(
            timeoutMs: Long,
            nowEpochMs: () -> Long = System::currentTimeMillis,
        ): OpenAiSubscriptionResponseDeadline {
            val boundedTimeoutMs = timeoutMs.coerceAtLeast(1L)
            val startEpochMs = nowEpochMs()
            val deadlineEpochMs = if (Long.MAX_VALUE - startEpochMs < boundedTimeoutMs) {
                Long.MAX_VALUE
            } else {
                startEpochMs + boundedTimeoutMs
            }
            return OpenAiSubscriptionResponseDeadline(deadlineEpochMs, nowEpochMs)
        }
    }
}

data class OpenAiSubscriptionParsedResponse(
    val outputItems: List<JsonObject>,
    val completed: OpenAiSubscriptionCompletedResponse?,
)
