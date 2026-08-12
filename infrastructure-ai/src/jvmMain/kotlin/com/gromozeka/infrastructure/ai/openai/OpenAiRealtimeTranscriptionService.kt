package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt

@Service
class OpenAiRealtimeTranscriptionService(
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val clientFactory: OpenAiSdkClientFactory,
) {
    fun availabilityFailure(): String? =
        runCatching {
            resolveConfiguration()
            null
        }.getOrElse { error ->
            error.message ?: "OpenAI realtime transcription is unavailable"
        }

    suspend fun start(
        userStableId: String,
        languageCode: String?,
        prompt: String?,
    ): OpenAiRealtimeTranscriptionSession {
        val configuration = resolveConfiguration(languageCode, prompt)
        val session = OpenAiRealtimeTranscriptionSession(configuration, userStableId)
        session.start()
        return session
    }

    private fun resolveConfiguration(
        languageCode: String? = null,
        prompt: String? = null,
    ): OpenAiRealtimeTranscriptionConfiguration {
        val speechToText = settingsProvider.userProfile.speechSettings.speechToText
        require(speechToText.enabled) { "Speech-to-text is disabled" }
        require(speechToText.engine == UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API) {
            "Provider VAD requires OpenAI API speech-to-text"
        }
        require(speechToText.audioSource == SpeechAudioSource.CurrentClient) {
            "Provider VAD requires current-client microphone input"
        }

        val runtime = aiConfigurationProvider.resolveAiRuntime(AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT)
        require(runtime.connection.enabled) {
            "OpenAI speech-to-text connection is disabled: ${runtime.connection.id.value}"
        }
        require(runtime.connection.executionTarget == AiExecutionTarget.Server) {
            "OpenAI realtime transcription requires a Server-targeted speech-to-text runtime"
        }
        require(runtime.connection.kind == AiConnection.Kind.OPENAI_API) {
            "Provider VAD is implemented only for OpenAI API connections"
        }

        return OpenAiRealtimeTranscriptionConfiguration(
            apiKey = clientFactory.resolveApiKey(runtime.connection),
            baseUrl = clientFactory.resolveBaseUrl(runtime.connection),
            sessionModel = REALTIME_SESSION_MODEL,
            transcriptionModel = REALTIME_TRANSCRIPTION_MODEL,
            languageCode = languageCode?.trim()?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
                ?: speechToText.mainLanguageCode?.trim()?.takeIf {
                    it.isNotBlank() && !it.equals("auto", ignoreCase = true)
                },
            prompt = prompt?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val REALTIME_SESSION_MODEL = "gpt-realtime-2.1"
        const val REALTIME_TRANSCRIPTION_MODEL = "gpt-live-transcribe"
    }
}

class OpenAiRealtimeTranscriptionSession internal constructor(
    private val configuration: OpenAiRealtimeTranscriptionConfiguration,
    private val userStableId: String,
) {
    private val log = KLoggers.logger(this)
    private val json = Json { ignoreUnknownKeys = true }
    private val eventsChannel = Channel<OpenAiRealtimeTranscriptionEvent>(Channel.UNLIMITED)
    private val textBuffer = StringBuilder()
    private var webSocket: WebSocket? = null
    val events: Flow<OpenAiRealtimeTranscriptionEvent> = eventsChannel.receiveAsFlow()

    suspend fun start() {
        val uri = URI.create("${configuration.websocketBaseUrl()}/realtime?model=${configuration.sessionModel}")
        val listener = Listener()
        val socket = withContext(Dispatchers.IO) {
            HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer ${configuration.apiKey}")
                .header("OpenAI-Safety-Identifier", userStableId.toSha256())
                .buildAsync(uri, listener)
                .get(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        webSocket = socket
        sendJson(sessionUpdateEvent())
        eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.Status("Provider VAD realtime session started"))
    }

    suspend fun appendPcm16(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        byteOrder: OpenAiRealtimePcmByteOrder,
    ) {
        if (pcm.isEmpty()) return
        require(channels == 1) { "OpenAI realtime transcription currently supports mono PCM input" }
        val pcm24k = resamplePcm16To24kLittleEndian(pcm, sampleRate, byteOrder)
        val base64 = Base64.getEncoder().encodeToString(pcm24k)
        sendJson(
            buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", base64)
            }
        )
    }

    suspend fun stop() {
        val socket = webSocket
        webSocket = null
        withContext(Dispatchers.IO) {
            runCatching {
                socket?.sendClose(WebSocket.NORMAL_CLOSURE, "stopped")
                    ?.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.Stopped)
        eventsChannel.close()
    }

    private suspend fun sendJson(payload: JsonObject) {
        val text = json.encodeToString(payload)
        val socket = requireNotNull(webSocket) { "OpenAI realtime transcription session is not connected" }
        withContext(Dispatchers.IO) {
            socket.sendText(text, true).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun sessionUpdateEvent(): JsonObject =
        buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "realtime")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", OPENAI_SAMPLE_RATE)
                        }
                        putJsonObject("transcription") {
                            put("model", configuration.transcriptionModel)
                            configuration.languageCode?.let { language ->
                                put("language", language)
                            }
                            configuration.prompt?.let { put("prompt", it) }
                        }
                        putJsonObject("noise_reduction") {
                            put("type", "near_field")
                        }
                        putJsonObject("turn_detection") {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 500)
                            put("create_response", false)
                            put("interrupt_response", false)
                        }
                    }
                }
            }
        }

    private fun handleMessage(message: String) {
        val payload = runCatching { json.parseToJsonElement(message).jsonObject }
            .getOrElse { error ->
                log.warn(error) { "Failed to parse OpenAI realtime event: ${message.take(500)}" }
                return
            }
        val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "session.created", "session.updated" ->
                eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.Status(type))
            "input_audio_buffer.speech_started" ->
                eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.SpeechStarted)
            "input_audio_buffer.speech_stopped" ->
                eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.SpeechStopped)
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = payload["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotBlank()) {
                    eventsChannel.trySend(
                        OpenAiRealtimeTranscriptionEvent.TranscriptDelta(
                            itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            delta = delta,
                        )
                    )
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = payload["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                eventsChannel.trySend(
                    OpenAiRealtimeTranscriptionEvent.TranscriptCompleted(
                        itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        text = transcript,
                    )
                )
            }
            "conversation.item.input_audio_transcription.failed" ->
                fail(payload.openAiErrorMessage())
            "error" ->
                fail(payload.openAiErrorMessage())
            else -> Unit
        }
    }

    private fun fail(message: String) {
        eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.Failed(message))
        eventsChannel.close()
        runCatching { webSocket?.abort() }
    }

    private inner class Listener : WebSocket.Listener {
        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            textBuffer.append(data)
            if (last) {
                val message = textBuffer.toString()
                textBuffer.clear()
                handleMessage(message)
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.warn(error) { "OpenAI realtime transcription WebSocket failed: ${error.message}" }
            eventsChannel.trySend(
                OpenAiRealtimeTranscriptionEvent.Failed(
                    error.message ?: "OpenAI realtime transcription failed"
                )
            )
            eventsChannel.close(error)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*>? {
            eventsChannel.trySend(OpenAiRealtimeTranscriptionEvent.Stopped)
            eventsChannel.close()
            return null
        }
    }

    private companion object {
        const val OPENAI_SAMPLE_RATE = 24_000
        const val OPEN_TIMEOUT_SECONDS = 20L
        const val SEND_TIMEOUT_SECONDS = 10L
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}

data class OpenAiRealtimeTranscriptionConfiguration(
    val apiKey: String,
    val baseUrl: String,
    val sessionModel: String,
    val transcriptionModel: String,
    val languageCode: String?,
    val prompt: String?,
) {
    fun websocketBaseUrl(): String =
        baseUrl.removeSuffix("/")
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
}

enum class OpenAiRealtimePcmByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN,
}

sealed interface OpenAiRealtimeTranscriptionEvent {
    data class Status(val message: String) : OpenAiRealtimeTranscriptionEvent
    data object SpeechStarted : OpenAiRealtimeTranscriptionEvent
    data object SpeechStopped : OpenAiRealtimeTranscriptionEvent
    data class TranscriptDelta(val itemId: String, val delta: String) : OpenAiRealtimeTranscriptionEvent
    data class TranscriptCompleted(val itemId: String, val text: String) : OpenAiRealtimeTranscriptionEvent
    data class Failed(val message: String) : OpenAiRealtimeTranscriptionEvent
    data object Stopped : OpenAiRealtimeTranscriptionEvent
}

private fun resamplePcm16To24kLittleEndian(
    input: ByteArray,
    sourceSampleRate: Int,
    byteOrder: OpenAiRealtimePcmByteOrder,
): ByteArray {
    require(sourceSampleRate > 0) { "PCM source sample rate must be positive" }
    require(input.size % 2 == 0) { "PCM16 input byte size must be even" }
    if (input.isEmpty()) return input

    val sourceSamples = ShortArray(input.size / 2)
    var inputIndex = 0
    var sampleIndex = 0
    while (inputIndex < input.size) {
        val first = input[inputIndex].toInt() and 0xff
        val second = input[inputIndex + 1].toInt() and 0xff
        sourceSamples[sampleIndex] = when (byteOrder) {
            OpenAiRealtimePcmByteOrder.BIG_ENDIAN -> ((first shl 8) or second).toShort()
            OpenAiRealtimePcmByteOrder.LITTLE_ENDIAN -> ((second shl 8) or first).toShort()
        }
        inputIndex += 2
        sampleIndex += 1
    }

    val targetSampleRate = 24_000
    val targetSamples = if (sourceSampleRate == targetSampleRate) {
        sourceSamples
    } else {
        val targetSize = (sourceSamples.size.toLong() * targetSampleRate / sourceSampleRate).toInt()
            .coerceAtLeast(1)
        ShortArray(targetSize) { targetIndex ->
            val sourcePosition = targetIndex.toDouble() * sourceSampleRate / targetSampleRate
            val leftIndex = floor(sourcePosition).toInt().coerceIn(sourceSamples.indices)
            val rightIndex = (leftIndex + 1).coerceAtMost(sourceSamples.lastIndex)
            val fraction = sourcePosition - leftIndex
            val value = sourceSamples[leftIndex] * (1.0 - fraction) + sourceSamples[rightIndex] * fraction
            value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    val output = ByteArray(targetSamples.size * 2)
    targetSamples.forEachIndexed { index, sample ->
        val value = sample.toInt()
        output[index * 2] = (value and 0xff).toByte()
        output[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
    }
    return output
}

private fun JsonObject.openAiErrorMessage(): String {
    val error = this["error"]?.jsonObject
    return error?.get("message")?.jsonPrimitive?.contentOrNull
        ?: this["message"]?.jsonPrimitive?.contentOrNull
        ?: "OpenAI realtime transcription failed"
}

private fun String.toSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
