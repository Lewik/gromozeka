package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiHttpSettings
import com.gromozeka.infrastructure.ai.LiveTranscriptionBackend
import com.gromozeka.infrastructure.ai.runtime.SdkHttpSupport
import com.gromozeka.domain.service.AiAdmittedSession
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.domain.service.LiveTranscriptionSession
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import javax.net.ssl.SSLContext
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val NATIVE_REALTIME_MODELS = setOf("gpt-live-transcribe", "gpt-transcribe")

@Component
class OpenAiLiveTranscriptionProvider(
    private val clients: OpenAiSdkClientFactory,
    private val admission: AiCallAdmission,
) : LiveTranscriptionBackend {
    override fun supports(connectionKind: AiConnection.Kind): Boolean = connectionKind == AiConnection.Kind.OPENAI_API

    override suspend fun open(
        userStableId: String,
        runtime: ResolvedAiRuntime,
        languages: List<String>,
        nativeTranslationLanguage: String?,
    ): LiveTranscriptionSession {
        require(nativeTranslationLanguage == null) { "OpenAI Live does not provide native translation" }
        require(runtime.connection.kind == AiConnection.Kind.OPENAI_API) { "Live transcription requires an OpenAI API connection" }
        val model = runtime.modelConfiguration.providerModelId
        require(model in NATIVE_REALTIME_MODELS) { "Unsupported OpenAI Live transcription model: $model" }
        val configuration = OpenAiLiveTranscriptionConfiguration(
            baseUrl = clients.resolveBaseUrl(runtime.connection),
            apiKey = clients.resolveApiKey(runtime.connection),
            model = model,
            languages = languages,
            httpSettings = SdkHttpSupport.settings(runtime.connection),
        )
        val admitted = admission.openSession(runtime.modelConfiguration, "LIVE_TRANSCRIPTION")
        val session = OpenAiLiveTranscriptionSession(configuration, userStableId, admitted)
        try {
            session.start()
            return session
        } catch (error: Throwable) {
            session.close(if (error is CancellationException && !currentCoroutineContext().isActive) AiAdmissionOutcome.CANCELLED else AiAdmissionOutcome.FAILED)
            throw error
        }
    }
}

internal class OpenAiLiveTranscriptionConfiguration(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val languages: List<String>,
    val httpSettings: AiHttpSettings = AiHttpSettings(),
) {
    init {
        require(model.isNotBlank()) { "Live transcription model must not be blank" }
        require(languages.all { it.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")) }) { "Invalid transcription language hint" }
        require(languages.distinct().size == languages.size) { "Transcription language hints must be unique" }
    }

    fun endpoint(): URI {
        val base = URI(baseUrl)
        require(base.scheme?.lowercase() in setOf("http", "https") && !base.host.isNullOrBlank() &&
            base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null) {
            "Live API base URL must be an absolute HTTP(S) inference prefix"
        }
        val scheme = if (base.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        return URI("$scheme://${base.rawAuthority}${base.rawPath.trimEnd('/')}/realtime?intent=transcription")
    }

    fun httpClient(): HttpClient {
        val builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        SdkHttpSupport.proxy(httpSettings, baseUrl)?.let { selected ->
            builder.proxy(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(selected)
                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
            })
        }
        SdkHttpSupport.tls(SdkHttpSupport.readCaBundle(httpSettings))?.let { tls ->
            builder.sslContext(SSLContext.getInstance("TLS").apply { init(null, arrayOf(tls.trustManager), null) })
        }
        return builder.build()
    }

    fun requestHeaders(safetyId: String): Map<String, String> {
        val headers = linkedMapOf("Authorization" to "Bearer $apiKey", "OpenAI-Safety-Identifier" to safetyId)
        SdkHttpSupport.headers(httpSettings).forEach { (name, value) ->
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
            headers[name] = value
        }
        return headers
    }

    val turnDetection: LiveTranscriptionTurnDetection
        get() = if (model == "gpt-transcribe") LiveTranscriptionTurnDetection.PROVIDER else LiveTranscriptionTurnDetection.CLIENT

    fun sessionUpdate(disableTurnDetection: Boolean = false): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "transcription")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", 24_000)
                    }
                    putJsonObject("transcription") {
                        put("model", model)
                        if (model == "gpt-live-transcribe") put("delay", "low")
                        if (languages.isNotEmpty()) putJsonArray("languages") { languages.forEach { add(it) } }
                    }
                    if (turnDetection == LiveTranscriptionTurnDetection.PROVIDER && !disableTurnDetection) {
                        putJsonObject("turn_detection") {
                            put("type", "semantic_vad")
                            put("eagerness", "low")
                        }
                    } else put("turn_detection", JsonNull)
                }
            }
        }
    }
}

internal class OpenAiLiveTranscriptionSession(
    private val configuration: OpenAiLiveTranscriptionConfiguration,
    private val userStableId: String,
    private val admission: AiAdmittedSession,
) : LiveTranscriptionSession {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventChannel = Channel<LiveTranscriptionEvent>(128)
    override val events: Flow<LiveTranscriptionEvent> = eventChannel.receiveAsFlow()
    override val turnDetection: LiveTranscriptionTurnDetection = configuration.turnDetection
    private val ready = CompletableDeferred<Unit>()
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<Exception?>(null)
    private val pendingLock = Any()
    private data class PendingTurns(
        val acknowledgements: Int = 0,
        val transcripts: Int = 0,
        val automaticCommits: Set<String> = emptySet(),
        val activeSpeechId: String? = null,
        val stopFlushEventId: String? = null,
        val failure: Exception? = null,
    )
    private val pendingTurns = MutableStateFlow(PendingTurns())
    private val completedItems = mutableMapOf<String, String>()
    private val textByItem = mutableMapOf<String, StringBuilder>()
    private val messageBuffer = StringBuilder()
    private var uncommittedSamples = 0L
    private var finishingInput = false
    private val vadDisableRequested = AtomicBoolean(false)
    private val vadDisabled = CompletableDeferred<Unit>()
    @Volatile private var socket: WebSocket? = null

    suspend fun start() {
        val safetyId = MessageDigest.getInstance("SHA-256")
            .digest(userStableId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val builder = configuration.httpClient().newWebSocketBuilder()
        configuration.requestHeaders(safetyId).forEach { (name, value) -> builder.header(name, value) }
        val endpoint = configuration.endpoint()
        try {
            socket = runInterruptible(Dispatchers.IO) {
                builder.buildAsync(endpoint, Listener()).get(20, TimeUnit.SECONDS)
            }
            send(configuration.sessionUpdate())
            withTimeout(20_000) { ready.await() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val handshake = error.cause as? WebSocketHandshakeException ?: error as? WebSocketHandshakeException
            val status = handshake?.response?.statusCode()
            throw failure.get() ?: LiveTranscriptionException(
                message = if (status == null) "OpenAI live transcription connection failed" else "OpenAI live transcription connection failed (HTTP $status)",
                retryable = status == null || status == 429 || status >= 500,
                cause = error,
            )
        }
    }

    override suspend fun appendAudio(pcm: ByteArray) = writeMutex.withLock {
        check(!finishingInput) { "Live input is already finishing" }
        require(pcm.size % 2 == 0) { "Live PCM16 chunks must contain complete samples" }
        if (pcm.isEmpty()) return@withLock
        val converted = resamplePcm16To24kLittleEndian(pcm, 16_000, OpenAiRealtimePcmByteOrder.BIG_ENDIAN)
        appendConverted(converted)
    }

    private suspend fun appendConverted(pcm: ByteArray) {
        send(buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.getEncoder().encodeToString(pcm))
        })
        uncommittedSamples += pcm.size / 2
    }

    override suspend fun commit() = writeMutex.withLock {
        check(turnDetection == LiveTranscriptionTurnDetection.CLIENT) { "The provider owns Live turn boundaries" }
        check(!finishingInput) { "Live input is already finishing" }
        commitPending()
    }

    private suspend fun commitPending() {
        if (uncommittedSamples == 0L) return
        // OpenAI requires at least 100 ms in every client-committed audio item.
        if (uncommittedSamples < 2_400) appendConverted(ByteArray(((2_400 - uncommittedSamples) * 2).toInt()))
        synchronized(pendingLock) {
            val pending = pendingTurns.value
            check(pending.transcripts < 8) { "Live transcription has too many uncompleted turns" }
            pendingTurns.value = pending.copy(acknowledgements = pending.acknowledgements + 1, transcripts = pending.transcripts + 1)
        }
        send(buildJsonObject { put("type", "input_audio_buffer.commit") })
        uncommittedSamples = 0L
    }

    override suspend fun finishInput() {
        withTimeout(15_000) {
            writeMutex.withLock {
                failure.get()?.let { throw it }
                check(!finishingInput) { "Live input is already finishing" }
                finishingInput = true
                if (turnDetection == LiveTranscriptionTurnDetection.PROVIDER) {
                    vadDisableRequested.set(true)
                    send(configuration.sessionUpdate(disableTurnDetection = true))
                } else commitPending()
            }
            if (turnDetection == LiveTranscriptionTurnDetection.PROVIDER) {
                // session.updated is not a VAD drain barrier. Always try to commit the remaining input buffer;
                // an exact empty-buffer error is the successful no-op when VAD already committed everything.
                vadDisabled.await()
                failure.get()?.let { throw it }
                writeMutex.withLock {
                    val eventId = "gromozeka-stop-flush-${UUID.randomUUID()}"
                    synchronized(pendingLock) {
                        val pending = pendingTurns.value
                        check(pending.stopFlushEventId == null) { "Live Stop flush is already pending" }
                        check(pending.transcripts < 8) { "Live transcription has too many uncompleted turns" }
                        pendingTurns.value = pending.copy(
                            acknowledgements = pending.acknowledgements + 1,
                            transcripts = pending.transcripts + 1,
                            stopFlushEventId = eventId,
                        )
                    }
                    send(buildJsonObject {
                        put("type", "input_audio_buffer.commit")
                        put("event_id", eventId)
                    })
                }
            }
            pendingTurns.first {
                it.failure != null || (it.acknowledgements == 0 && it.transcripts == 0 &&
                    it.automaticCommits.isEmpty() && it.activeSpeechId == null && it.stopFlushEventId == null)
            }
            failure.get()?.let { throw it }
        }
        eventChannel.close()
    }

    private suspend fun send(payload: JsonObject) {
        failure.get()?.let { throw it }
        check(!closed.get()) { "Live transcription session is closed" }
        val connected = checkNotNull(socket) { "Live transcription session is not connected" }
        try {
            runInterruptible(Dispatchers.IO) { connected.sendText(payload.toString(), true).get(10, TimeUnit.SECONDS) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LiveTranscriptionException("OpenAI live transcription audio transport failed", retryable = true, cause = error)
        }
    }

    override suspend fun close(outcome: AiAdmissionOutcome): Unit = withContext(NonCancellable) {
        if (!closed.compareAndSet(false, true)) return@withContext
        val connected = socket
        socket = null
        try {
            withContext(Dispatchers.IO) { connected?.sendClose(WebSocket.NORMAL_CLOSURE, "stopped")?.get(2, TimeUnit.SECONDS) }
        } catch (_: Exception) {
            // Closing is best effort after the explicit transcription drain or failure.
        } finally {
            connected?.abort()
            eventChannel.close(failure.get())
            admission.finish(outcome)
        }
    }

    private fun publish(event: LiveTranscriptionEvent) {
        if (!eventChannel.trySend(event).isSuccess) {
            fail(LiveTranscriptionException("Live transcription event buffer overflow", retryable = true))
        }
    }

    private fun fail(error: Exception) {
        if (closed.get() || !failure.compareAndSet(null, error)) return
        synchronized(pendingLock) { pendingTurns.value = pendingTurns.value.copy(failure = error) }
        ready.completeExceptionally(error)
        vadDisabled.completeExceptionally(error)
        eventChannel.close(error)
        socket?.abort()
    }

    private fun openAiTranscriptionFailure(error: JsonObject?, code: String?) = LiveTranscriptionException(
        message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "OpenAI live transcription failed",
        retryable = code in setOf("server_error", "rate_limit_exceeded", "service_unavailable"),
    )

    private fun requiredString(payload: JsonObject, name: String): String {
        val value = payload[name] as? JsonPrimitive
        require(value?.isString == true) { "OpenAI live event field $name must be a string" }
        return value.content
    }

    private fun itemId(payload: JsonObject): String = requiredString(payload, "item_id").also {
        require(it.isNotBlank()) { "OpenAI live transcription event is missing item_id" }
    }

    private fun handle(payload: JsonObject) {
        when (requiredString(payload, "type")) {
            "session.updated" -> {
                if (vadDisableRequested.get()) {
                    val input = payload["session"]?.jsonObject?.get("audio")?.jsonObject?.get("input")?.jsonObject
                    check(input?.get("turn_detection") == JsonNull) { "OpenAI did not disable Live turn detection on Stop" }
                    vadDisabled.complete(Unit)
                } else ready.complete(Unit)
            }
            "input_audio_buffer.speech_started" -> synchronized(pendingLock) {
                check(turnDetection == LiveTranscriptionTurnDetection.PROVIDER && !vadDisabled.isCompleted) {
                    "OpenAI detected speech without provider-owned Live turns"
                }
                val pending = pendingTurns.value
                check(pending.activeSpeechId == null) { "OpenAI started an overlapping Live turn" }
                pendingTurns.value = pending.copy(activeSpeechId = itemId(payload))
            }
            "input_audio_buffer.speech_stopped" -> synchronized(pendingLock) {
                val id = itemId(payload)
                val pending = pendingTurns.value
                check(turnDetection == LiveTranscriptionTurnDetection.PROVIDER && pending.activeSpeechId == id) {
                    "OpenAI stopped an unknown Live speech turn"
                }
                check(id !in pending.automaticCommits && id !in completedItems) { "OpenAI stopped the same Live turn twice" }
                check(pending.transcripts < 8) { "Live transcription has too many uncompleted turns" }
                pendingTurns.value = pending.copy(
                    transcripts = pending.transcripts + 1,
                    automaticCommits = pending.automaticCommits + id,
                    activeSpeechId = null,
                )
            }
            "input_audio_buffer.committed" -> synchronized(pendingLock) {
                val id = itemId(payload)
                val pending = pendingTurns.value
                val automatic = id in pending.automaticCommits
                check(automatic || pending.acknowledgements > 0) { "OpenAI acknowledged an unrequested audio commit" }
                publish(LiveTranscriptionEvent.Committed(
                    itemId = id,
                    previousItemId = payload["previous_item_id"]?.jsonPrimitive?.contentOrNull,
                ))
                val stopFlush = !automatic && pending.stopFlushEventId != null
                pendingTurns.value = pending.copy(
                    acknowledgements = pending.acknowledgements - if (automatic) 0 else 1,
                    automaticCommits = pending.automaticCommits - id,
                    activeSpeechId = if (stopFlush) null else pending.activeSpeechId,
                    stopFlushEventId = if (stopFlush) null else pending.stopFlushEventId,
                )
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val id = itemId(payload)
                check(id !in completedItems) { "OpenAI sent a delta after final transcription" }
                val delta = requiredString(payload, "delta")
                if (delta.isEmpty()) return
                val text = textByItem.getOrPut(id) { StringBuilder() }.append(delta).toString()
                check(text.length <= 65_536) { "Live transcription turn exceeds text size limit" }
                publish(LiveTranscriptionEvent.Transcript(id, text, isFinal = false))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val id = itemId(payload)
                val text = requiredString(payload, "transcript")
                completedItems[id]?.let { previous ->
                    check(previous == text) { "OpenAI sent conflicting final transcripts for the same turn" }
                    return
                }
                completedItems[id] = text
                textByItem.remove(id)
                synchronized(pendingLock) {
                    val pending = pendingTurns.value
                    check(pending.transcripts > 0) { "OpenAI completed an uncommitted transcription turn" }
                    publish(LiveTranscriptionEvent.Transcript(id, text, isFinal = true))
                    pendingTurns.value = pendingTurns.value.copy(transcripts = pending.transcripts - 1)
                }
            }
            "error" -> {
                val error = payload["error"]?.jsonObject
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                val sourceEventId = error?.get("event_id")?.jsonPrimitive?.contentOrNull
                val emptyStopFlush = synchronized(pendingLock) {
                    val pending = pendingTurns.value
                    if (finishingInput && code == "input_audio_buffer_commit_empty" &&
                        sourceEventId == pending.stopFlushEventId) {
                        check(pending.acknowledgements > 0 && pending.transcripts > 0) {
                            "OpenAI rejected a Stop flush without a pending commit"
                        }
                        pendingTurns.value = pending.copy(
                            acknowledgements = pending.acknowledgements - 1,
                            transcripts = pending.transcripts - 1,
                            stopFlushEventId = null,
                        )
                        true
                    } else false
                }
                if (!emptyStopFlush) fail(openAiTranscriptionFailure(error, code))
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val error = payload["error"]?.jsonObject
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                fail(openAiTranscriptionFailure(error, code))
            }
        }
    }

    private inner class Listener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket
            if (closed.get()) webSocket.abort() else webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            try {
                check(messageBuffer.length + data.length <= 1_048_576) { "OpenAI live transcription event exceeds size limit" }
                messageBuffer.append(data)
                if (last) {
                    val message = messageBuffer.toString()
                    messageBuffer.clear()
                    handle(json.parseToJsonElement(message).jsonObject)
                }
                webSocket.request(1)
            } catch (error: Exception) {
                fail(error)
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            fail(LiveTranscriptionException("OpenAI live transcription connection was interrupted", retryable = true, cause = error))
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (!closed.get()) fail(LiveTranscriptionException("OpenAI live transcription connection closed (code $statusCode)", retryable = true))
            return null
        }
    }
}
