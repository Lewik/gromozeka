package com.gromozeka.infrastructure.ai.soniox

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiHttpSettings
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.service.AiAdmittedSession
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.domain.service.LiveTranscriptionSession
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.LiveTranscriptionBackend
import com.gromozeka.infrastructure.ai.runtime.SdkHttpSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

private const val DEFAULT_SONIOX_REALTIME_URL = "https://stt-rt.soniox.com/transcribe-websocket"
private const val SONIOX_IDLE_TIMEOUT_MILLIS = 20_000

@Component
class SonioxLiveTranscriptionProvider(
    private val settingsProvider: SettingsProvider,
    private val admission: AiCallAdmission,
) : LiveTranscriptionBackend {
    override fun supports(connectionKind: AiConnection.Kind): Boolean = connectionKind == AiConnection.Kind.SONIOX_API

    override suspend fun open(
        userStableId: String,
        runtime: ResolvedAiRuntime,
        languages: List<String>,
        nativeTranslationLanguage: String?,
    ): LiveTranscriptionSession {
        val connection = runtime.connection as? AiConnection.SonioxApi
            ?: error("Soniox Live requires a Soniox API connection")
        require(AiModelCapability.SPEECH_TO_TEXT in runtime.modelSpec.capabilities) {
            "Soniox Live requires a speech-to-text model"
        }
        require(AiModelCapability.SPEAKER_DIARIZATION in runtime.modelSpec.capabilities) {
            "Soniox Live 2 requires speaker diarization"
        }
        val configuration = SonioxLiveTranscriptionConfiguration(
            baseUrl = connection.baseUrl ?: DEFAULT_SONIOX_REALTIME_URL,
            apiKey = SdkHttpSupport.token(connection, settingsProvider, "SONIOX_API_KEY"),
            model = runtime.modelConfiguration.providerModelId,
            languages = languages,
            nativeTranslationLanguage = nativeTranslationLanguage,
            httpSettings = connection.httpSettings,
        )
        val admitted = admission.openSession(runtime.modelConfiguration, "LIVE_TRANSCRIPTION")
        val session = SonioxLiveTranscriptionSession(configuration, admitted)
        try {
            session.start()
            return session
        } catch (error: Throwable) {
            session.close(
                if (error is CancellationException && !currentCoroutineContext().isActive) {
                    AiAdmissionOutcome.CANCELLED
                } else {
                    AiAdmissionOutcome.FAILED
                }
            )
            throw error
        }
    }
}

internal class SonioxLiveTranscriptionConfiguration(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val languages: List<String>,
    val nativeTranslationLanguage: String? = null,
    val httpSettings: AiHttpSettings = AiHttpSettings(),
) {
    init {
        require(model.isNotBlank()) { "Soniox model must not be blank" }
        require(languages.size <= 32 && languages.distinct().size == languages.size &&
            languages.all { it.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")) }) {
            "Invalid Soniox language hints"
        }
        require(nativeTranslationLanguage == null ||
            nativeTranslationLanguage.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
            "Invalid Soniox native translation language"
        }
    }

    fun endpoint(): URI {
        val base = URI(baseUrl)
        require(base.scheme?.lowercase() in setOf("http", "https") && !base.host.isNullOrBlank() &&
            base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null) {
            "Soniox base URL must be an absolute HTTP(S) endpoint"
        }
        val scheme = if (base.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        val path = base.rawPath.takeUnless { it.isNullOrBlank() || it == "/" } ?: "/transcribe-websocket"
        return URI("$scheme://${base.rawAuthority}${path.trimEnd('/')}")
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

    fun requestHeaders(): Map<String, String> {
        val headers = linkedMapOf("Authorization" to "Bearer $apiKey")
        SdkHttpSupport.headers(httpSettings).forEach { (name, value) ->
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
            headers[name] = value
        }
        return headers
    }

    fun startRequest(): String = buildJsonObject {
        put("model", model)
        put("audio_format", "pcm_s16be")
        put("sample_rate", 16_000)
        put("num_channels", 1)
        if (languages.isNotEmpty()) putJsonArray("language_hints") { languages.forEach(::add) }
        put("enable_language_identification", true)
        put("enable_speaker_diarization", true)
        put("enable_endpoint_detection", false)
        nativeTranslationLanguage?.let { target ->
            put("translation", buildJsonObject {
                put("type", "one_way")
                put("target_language", target)
            })
        }
        put("idle_timeout_ms", SONIOX_IDLE_TIMEOUT_MILLIS)
    }.toString()
}

@Serializable
internal data class SonioxRealtimeToken(
    val text: String,
    @SerialName("is_final") val isFinal: Boolean,
    @SerialName("start_ms") val startMillis: Long? = null,
    @SerialName("end_ms") val endMillis: Long? = null,
    val confidence: Double? = null,
    val speaker: String? = null,
    val language: String? = null,
    @SerialName("source_language") val sourceLanguage: String? = null,
    @SerialName("translation_status") val translationStatus: String? = null,
) {
    init {
        require(text.length <= 4_096) { "Soniox token text exceeds size limit" }
        require((startMillis == null) == (endMillis == null) &&
            (startMillis == null || startMillis >= 0 && endMillis!! >= startMillis)) {
            "Soniox token audio range is invalid"
        }
        require(confidence == null || confidence in 0.0..1.0) { "Soniox token confidence is invalid" }
        require(speaker == null || speaker.isNotBlank() && speaker.length <= 64) { "Soniox speaker id is invalid" }
        require(language == null || language.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
            "Soniox token language is invalid"
        }
        require(sourceLanguage == null || sourceLanguage.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
            "Soniox token source language is invalid"
        }
        require(translationStatus in setOf(null, "none", "original", "translation")) {
            "Soniox token translation status is invalid"
        }
    }
}

@Serializable
private data class SonioxRealtimeResponse(
    val tokens: List<SonioxRealtimeToken> = emptyList(),
    @SerialName("final_audio_proc_ms") val finalAudioMillis: Long = 0,
    @SerialName("total_audio_proc_ms") val totalAudioMillis: Long = 0,
    val finished: Boolean = false,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_type") val errorType: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("request_id") val requestId: String? = null,
)

internal class SonioxTranscriptAssembler(
    private val maximumSegmentMillis: Long = 8_000,
    private val maximumSegmentCharacters: Int = 1_000,
    private val nativeTranslationLanguage: String? = null,
) {
    private class ObservedLanguages {
        private val values = linkedSetOf<String>()

        fun add(language: String?) {
            if (language != null) values += language
        }

        fun singleOrNull(additional: Iterable<String> = emptyList()): String? =
            (values + additional).singleOrNull()
    }

    private class Active(
        val id: String,
        val stable: StringBuilder = StringBuilder(),
        var speakerId: String? = null,
        val languages: ObservedLanguages = ObservedLanguages(),
        var startMillis: Long? = null,
        var endMillis: Long? = null,
        var committed: Boolean = false,
        var flushPending: Boolean = false,
        var lastPartialSignature: String? = null,
    ) {
        val language: String? get() = languages.singleOrNull()
    }

    private class NativeTranslationState(val targetLanguage: String) {
        val sourceLanguages = ObservedLanguages()
        val sourceLanguage: String? get() = sourceLanguages.singleOrNull()
        var speakerId: String? = null
        var sourceClosed: Boolean = false
        var lastPublishedSignature: String? = null
    }

    private sealed interface NativeTranslationChunk {
        val itemId: String
        val speakerId: String?
        val sourceLanguage: String?

        data class Direct(
            override val itemId: String,
            override val speakerId: String?,
            override val sourceLanguage: String?,
            val text: String,
        ) : NativeTranslationChunk

        data class Pending(
            override val itemId: String,
            override val speakerId: String?,
            override val sourceLanguage: String?,
            val stable: StringBuilder = StringBuilder(),
            var draft: String = "",
            var completed: Boolean = false,
        ) : NativeTranslationChunk
    }

    private var active: Active? = null
    private var previousItemId: String? = null
    private var nextItem = 0L
    private var latestDraft: List<SonioxRealtimeToken> = emptyList()
    private val nativeTranslations = mutableMapOf<String, NativeTranslationState>()
    private val nativeTranslationChunks = mutableListOf<NativeTranslationChunk>()

    init {
        require(maximumSegmentMillis > 0 && maximumSegmentCharacters > 0) {
            "Soniox segment limits must be positive"
        }
    }

    fun accept(tokens: List<SonioxRealtimeToken>): List<LiveTranscriptionEvent> =
        if (nativeTranslationLanguage == null) acceptTranscriptionOnly(tokens) else acceptWithTranslation(tokens)

    fun finish(): List<LiveTranscriptionEvent> {
        check(latestDraft.isEmpty()) { "Soniox stream ended with provisional transcription tokens" }
        check(nativeTranslationChunks.none { it is NativeTranslationChunk.Pending && !it.completed }) {
            "Soniox stream ended before native translations were completed"
        }
        return mutableListOf<LiveTranscriptionEvent>().also { events ->
            finalizeActive(events)
            nativeTranslations.forEach { (itemId, translation) ->
                check(translation.sourceClosed) { "Soniox native translation source was not closed: $itemId" }
                publishNativeTranslation(itemId, events)
            }
        }
    }

    private fun acceptTranscriptionOnly(tokens: List<SonioxRealtimeToken>): List<LiveTranscriptionEvent> {
        val events = mutableListOf<LiveTranscriptionEvent>()
        var sawNonFinal = false
        tokens.forEach { token ->
            require(token.translationStatus in setOf(null, "none")) {
                "Unexpected Soniox translation token in transcription-only session"
            }
            if (token.isFinal) {
                check(!sawNonFinal) { "Soniox final token followed a provisional token in one response" }
                appendFinal(token, events)
            } else {
                sawNonFinal = true
            }
        }
        latestDraft = tokens.filterNot { it.isFinal }
        publishPartial(events)
        return events
    }

    private fun acceptWithTranslation(tokens: List<SonioxRealtimeToken>): List<LiveTranscriptionEvent> {
        val events = mutableListOf<LiveTranscriptionEvent>()
        val original = tokens.filter { it.translationStatus != "translation" }
        val translated = tokens.filter { it.translationStatus == "translation" }
        require(original.all { it.translationStatus in setOf("original", "none") }) {
            "Soniox native translation stream returned an invalid original token"
        }
        acceptOriginalTokens(original, events)
        acceptTranslatedTokens(translated, events)
        return events
    }

    private fun acceptOriginalTokens(tokens: List<SonioxRealtimeToken>, events: MutableList<LiveTranscriptionEvent>) {
        var sawNonFinal = false
        tokens.forEach { token ->
            if (!token.isFinal) sawNonFinal = true
            else check(!sawNonFinal) { "Soniox final original token followed a provisional token" }
        }
        val finalized = tokens.filter { it.isFinal }
        latestDraft = tokens.filterNot { it.isFinal }
        var offset = 0
        while (offset < finalized.size) {
            val first = finalized[offset]
            if (first.text in setOf("<end>", "<fin>")) {
                finalizeActive(events)
                offset += 1
                continue
            }
            var end = offset + 1
            while (end < finalized.size && finalized[end].text !in setOf("<end>", "<fin>") &&
                finalized[end].speaker == first.speaker && finalized[end].language == first.language) {
                end += 1
            }
            appendOriginalGroup(finalized.subList(offset, end), events)
            offset = end
        }
        publishPartial(events)
    }

    private fun appendOriginalGroup(tokens: List<SonioxRealtimeToken>, events: MutableList<LiveTranscriptionEvent>) {
        check(tokens.isNotEmpty())
        var current = active
        val first = tokens.first()
        if (current != null && (current.speakerId != null && first.speaker != null && current.speakerId != first.speaker ||
                current.flushPending && first.text.firstOrNull()?.isWhitespace() == true)) {
            finalizeActive(events)
            current = null
        }
        if (current == null) current = newActive(first)
        val itemId = current.id
        val sourceText = tokens.joinToString(separator = "") { it.text }
        val sourceLanguage = tokens.mapNotNull { it.language }.distinct().singleOrNull() ?: current.language
        val speakerId = tokens.mapNotNull { it.speaker }.distinct().singleOrNull() ?: current.speakerId
        nativeTranslation(itemId, sourceLanguage, speakerId)
        nativeTranslationChunks += if (sourceLanguage == nativeTranslationLanguage) {
            NativeTranslationChunk.Direct(itemId, speakerId, sourceLanguage, sourceText)
        } else {
            NativeTranslationChunk.Pending(itemId, speakerId, sourceLanguage)
        }
        tokens.forEachIndexed { index, token ->
            appendToActive(current, token)
            if (index == tokens.lastIndex && isSentenceBoundary(token.text)) finalizeActive(events)
        }
        if (sourceLanguage == nativeTranslationLanguage) publishNativeTranslation(itemId, events)
    }

    private fun acceptTranslatedTokens(tokens: List<SonioxRealtimeToken>, events: MutableList<LiveTranscriptionEvent>) {
        val contentTokens = tokens.filter { it.text !in setOf("<end>", "<fin>") }
        if (contentTokens.isEmpty()) return
        val pending = nativeTranslationChunks
            .filterIsInstance<NativeTranslationChunk.Pending>()
            .firstOrNull { !it.completed }
            ?: error("Soniox returned native translation without a preceding original chunk")
        val target = requireNotNull(nativeTranslationLanguage)
        require(contentTokens.all { it.language == null || it.language == target }) {
            "Soniox native translation target language is inconsistent"
        }
        require(contentTokens.all { it.sourceLanguage == null || pending.sourceLanguage == null || it.sourceLanguage == pending.sourceLanguage }) {
            "Soniox native translation source language is inconsistent"
        }
        require(contentTokens.all { it.speaker == null || pending.speakerId == null || it.speaker == pending.speakerId }) {
            "Soniox native translation speaker is inconsistent"
        }
        var sawNonFinal = false
        contentTokens.forEach { token ->
            if (!token.isFinal) sawNonFinal = true
            else check(!sawNonFinal) { "Soniox final translation token followed a provisional token" }
        }
        val finalText = contentTokens.filter { it.isFinal && it.text !in setOf("<end>", "<fin>") }
            .joinToString(separator = "") { it.text }
        if (finalText.isNotEmpty()) pending.stable.append(finalText)
        pending.draft = contentTokens.filterNot { it.isFinal }.joinToString(separator = "") { it.text }
        nativeTranslation(pending.itemId,
            contentTokens.mapNotNull { it.sourceLanguage }.distinct().singleOrNull() ?: pending.sourceLanguage,
            contentTokens.mapNotNull { it.speaker }.distinct().singleOrNull() ?: pending.speakerId)
        publishNativeTranslation(pending.itemId, events)
        if (pending.draft.isEmpty() && finalText.isNotEmpty()) {
            pending.completed = true
            publishNativeTranslation(pending.itemId, events)
        }
    }

    private fun appendFinal(token: SonioxRealtimeToken, events: MutableList<LiveTranscriptionEvent>) {
        if (token.text in setOf("<end>", "<fin>")) {
            finalizeActive(events)
            return
        }
        var current = active ?: newActive(token)
        if (current.stable.isEmpty()) {
            current.speakerId = token.speaker ?: current.speakerId
            current.startMillis = token.startMillis ?: current.startMillis
        } else if (current.speakerId != null && token.speaker != null && current.speakerId != token.speaker) {
            finalizeActive(events)
            current = newActive(token)
        } else if (current.flushPending && token.text.firstOrNull()?.isWhitespace() == true) {
            finalizeActive(events)
            current = newActive(token)
        }
        appendToActive(current, token)
        if (isSentenceBoundary(token.text)) finalizeActive(events)
    }

    private fun appendToActive(current: Active, token: SonioxRealtimeToken) {
        if (current.speakerId == null) current.speakerId = token.speaker
        current.languages.add(token.language)
        if (current.startMillis == null) current.startMillis = token.startMillis
        current.endMillis = token.endMillis ?: current.endMillis
        current.stable.append(token.text)
        check(current.stable.length <= 65_536) { "Soniox Live segment exceeds text size limit" }
        if (current.stable.length >= maximumSegmentCharacters || durationMillis(current) >= maximumSegmentMillis) {
            current.flushPending = true
        }
    }

    private fun publishPartial(events: MutableList<LiveTranscriptionEvent>) {
        val draftText = latestDraft.joinToString(separator = "") { it.text }
        var current = active
        if (current == null && draftText.isNotEmpty()) current = newActive(latestDraft.first())
        current ?: return
        val text = current.stable.toString() + draftText
        if (text.isEmpty()) return
        val draftSpeaker = latestDraft.mapNotNull { it.speaker }.distinct().singleOrNull()
        val speaker = current.speakerId ?: draftSpeaker
        val language = current.languages.singleOrNull(latestDraft.mapNotNull { it.language })
        val start = current.startMillis ?: latestDraft.firstOrNull()?.startMillis
        val end = latestDraft.lastOrNull()?.endMillis ?: current.endMillis
        val signature = listOf(text, speaker.orEmpty(), language.orEmpty(), start?.toString().orEmpty(), end?.toString().orEmpty())
            .joinToString("\u0000")
        if (signature == current.lastPartialSignature) return
        ensureCommitted(current, events)
        current.lastPartialSignature = signature
        events += LiveTranscriptionEvent.Transcript(
            itemId = current.id,
            text = text,
            isFinal = false,
            speakerId = speaker,
            language = language,
            startMillis = start.takeIf { end != null },
            endMillis = end.takeIf { start != null },
        )
    }

    private fun finalizeActive(events: MutableList<LiveTranscriptionEvent>) {
        val current = active ?: return
        if (current.stable.isEmpty()) {
            if (current.committed) {
                events += LiveTranscriptionEvent.Transcript(current.id, "", isFinal = true)
                previousItemId = current.id
            }
            nativeTranslations.remove(current.id)
            check(nativeTranslationChunks.none { it.itemId == current.id }) {
                "An empty Soniox source item still has native translation chunks"
            }
            active = null
            return
        }
        ensureCommitted(current, events)
        events += LiveTranscriptionEvent.Transcript(
            itemId = current.id,
            text = current.stable.toString(),
            isFinal = true,
            speakerId = current.speakerId,
            language = current.language,
            startMillis = current.startMillis.takeIf { current.endMillis != null },
            endMillis = current.endMillis.takeIf { current.startMillis != null },
        )
        previousItemId = current.id
        markNativeSourceClosed(current.id, events)
        active = null
    }

    private fun ensureCommitted(current: Active, events: MutableList<LiveTranscriptionEvent>) {
        if (current.committed) return
        current.committed = true
        events += LiveTranscriptionEvent.Committed(current.id, previousItemId)
    }

    private fun nativeTranslation(itemId: String, sourceLanguage: String?, speakerId: String?): NativeTranslationState {
        val target = requireNotNull(nativeTranslationLanguage)
        return nativeTranslations.getOrPut(itemId) { NativeTranslationState(target) }.also { state ->
            state.sourceLanguages.add(sourceLanguage)
            if (state.speakerId == null) state.speakerId = speakerId
        }
    }

    private fun markNativeSourceClosed(itemId: String, events: MutableList<LiveTranscriptionEvent>) {
        if (nativeTranslationLanguage == null) return
        nativeTranslation(itemId, null, null).sourceClosed = true
        publishNativeTranslation(itemId, events)
    }

    private fun publishNativeTranslation(itemId: String, events: MutableList<LiveTranscriptionEvent>) {
        val state = nativeTranslations[itemId] ?: return
        val chunks = nativeTranslationChunks.filter { it.itemId == itemId }
        val text = buildString {
            for (chunk in chunks) {
                when (chunk) {
                    is NativeTranslationChunk.Direct -> append(chunk.text)
                    is NativeTranslationChunk.Pending -> {
                        append(chunk.stable).append(chunk.draft)
                        if (!chunk.completed) break
                    }
                }
            }
        }
        val final = state.sourceClosed && chunks.all { it !is NativeTranslationChunk.Pending || it.completed }
        if (text.isEmpty() && !final) return
        val signature = "$final\u0000$text"
        if (signature == state.lastPublishedSignature) return
        state.lastPublishedSignature = signature
        events += LiveTranscriptionEvent.Translation(
            itemId = itemId,
            text = text,
            isFinal = final,
            targetLanguage = state.targetLanguage,
            sourceLanguage = state.sourceLanguage,
            speakerId = state.speakerId,
        )
    }

    private fun newActive(token: SonioxRealtimeToken): Active = Active(
        id = "soniox-${nextItem++}",
        speakerId = token.speaker,
        startMillis = token.startMillis,
        endMillis = token.endMillis,
    ).also { active = it }

    private fun durationMillis(current: Active): Long =
        if (current.startMillis == null || current.endMillis == null) 0 else current.endMillis!! - current.startMillis!!

    private fun isSentenceBoundary(text: String): Boolean =
        text.trimEnd().lastOrNull() in setOf('.', '!', '?', '。', '！', '？', '؟')
}

internal class SonioxLiveTranscriptionSession(
    private val configuration: SonioxLiveTranscriptionConfiguration,
    private val admission: AiAdmittedSession,
    private val assembler: SonioxTranscriptAssembler = SonioxTranscriptAssembler(
        nativeTranslationLanguage = configuration.nativeTranslationLanguage,
    ),
) : LiveTranscriptionSession {
    override val turnDetection: LiveTranscriptionTurnDetection = LiveTranscriptionTurnDetection.PROVIDER
    private val json = Json { ignoreUnknownKeys = true }
    private val eventChannel = Channel<LiveTranscriptionEvent>(128)
    override val events: Flow<LiveTranscriptionEvent> = eventChannel.receiveAsFlow()
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val finishingInput = AtomicBoolean(false)
    private val finished = CompletableDeferred<Unit>()
    private val failure = AtomicReference<Exception?>(null)
    private val messageBuffer = StringBuilder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepalive: Job? = null
    private var finalAudioMillis = 0L
    private var totalAudioMillis = 0L
    @Volatile private var socket: WebSocket? = null

    suspend fun start() {
        val builder = configuration.httpClient().newWebSocketBuilder()
        configuration.requestHeaders().forEach { (name, value) -> builder.header(name, value) }
        try {
            socket = runInterruptible(Dispatchers.IO) {
                builder.buildAsync(configuration.endpoint(), Listener()).get(20, TimeUnit.SECONDS)
            }
            sendText(configuration.startRequest())
            keepalive = scope.launch {
                try {
                    while (isActive) {
                        delay(10_000)
                        writeMutex.withLock {
                            if (!finishingInput.get() && !closed.get()) sendText("{\"type\":\"keepalive\"}")
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val handshake = error.cause as? WebSocketHandshakeException ?: error as? WebSocketHandshakeException
            val status = handshake?.response?.statusCode()
            throw failure.get() ?: LiveTranscriptionException(
                message = if (status == null) "Soniox Live connection failed" else "Soniox Live connection failed (HTTP $status)",
                retryable = status == null || status == 408 || status == 429 || status >= 500,
                cause = error,
            )
        }
    }

    override suspend fun appendAudio(pcm: ByteArray) = writeMutex.withLock {
        failure.get()?.let { throw it }
        check(!finishingInput.get()) { "Live input is already finishing" }
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 32_000) {
            "Soniox Live PCM chunks must contain at most one second of complete PCM16 samples"
        }
        val connected = checkNotNull(socket) { "Soniox Live session is not connected" }
        try {
            runInterruptible(Dispatchers.IO) {
                connected.sendBinary(ByteBuffer.wrap(pcm), true).get(10, TimeUnit.SECONDS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LiveTranscriptionException("Soniox Live audio transport failed", retryable = true, cause = error)
        }
        Unit
    }

    override suspend fun commit() {
        error("Soniox owns Live token finalization")
    }

    override suspend fun finishInput() {
        failure.get()?.let { throw it }
        check(finishingInput.compareAndSet(false, true)) { "Live input is already finishing" }
        keepalive?.cancel()
        withTimeout(20_000) {
            writeMutex.withLock { sendText("") }
            finished.await()
        }
        failure.get()?.let { throw it }
        eventChannel.close()
    }

    private suspend fun sendText(text: String) {
        failure.get()?.let { throw it }
        check(!closed.get()) { "Soniox Live session is closed" }
        val connected = checkNotNull(socket) { "Soniox Live session is not connected" }
        try {
            runInterruptible(Dispatchers.IO) { connected.sendText(text, true).get(10, TimeUnit.SECONDS) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LiveTranscriptionException("Soniox Live control transport failed", retryable = true, cause = error)
        }
    }

    override suspend fun close(outcome: AiAdmissionOutcome): Unit = withContext(NonCancellable) {
        if (!closed.compareAndSet(false, true)) return@withContext
        keepalive?.cancel()
        scope.cancel()
        val connected = socket
        socket = null
        try {
            withContext(Dispatchers.IO) {
                connected?.sendClose(WebSocket.NORMAL_CLOSURE, "stopped")?.get(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            // Closing is best effort after the explicit Soniox drain or failure.
        } finally {
            connected?.abort()
            eventChannel.close(failure.get())
            admission.finish(outcome)
        }
    }

    private fun handle(message: String) {
        val response = json.decodeFromString<SonioxRealtimeResponse>(message)
        response.errorType?.let { type ->
            fail(LiveTranscriptionException(
                message = "Soniox Live failed: $type${response.errorMessage?.let { ": $it" }.orEmpty()}",
                retryable = type in setOf("request_timeout", "rate_limit_exceeded", "limit_exceeded",
                    "max_concurrent_connections_reached", "internal_error", "service_unavailable"),
            ))
            return
        }
        check(response.finalAudioMillis >= finalAudioMillis && response.totalAudioMillis >= totalAudioMillis &&
            response.totalAudioMillis >= response.finalAudioMillis) {
            "Soniox audio progress moved backwards"
        }
        finalAudioMillis = response.finalAudioMillis
        totalAudioMillis = response.totalAudioMillis
        assembler.accept(response.tokens).forEach(::publish)
        if (response.finished) {
            assembler.finish().forEach(::publish)
            finished.complete(Unit)
        }
    }

    private fun publish(event: LiveTranscriptionEvent) {
        if (!eventChannel.trySend(event).isSuccess) {
            fail(LiveTranscriptionException("Soniox Live event buffer overflow", retryable = true))
        }
    }

    private fun fail(error: Exception) {
        if (closed.get() || !failure.compareAndSet(null, error)) return
        finished.completeExceptionally(error)
        eventChannel.close(error)
        socket?.abort()
    }

    private inner class Listener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket
            if (closed.get()) webSocket.abort() else webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            try {
                check(messageBuffer.length + data.length <= 1_048_576) { "Soniox Live event exceeds size limit" }
                messageBuffer.append(data)
                if (last) {
                    val message = messageBuffer.toString()
                    messageBuffer.clear()
                    handle(message)
                }
                webSocket.request(1)
            } catch (error: Exception) {
                fail(error)
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            fail(LiveTranscriptionException("Soniox Live connection was interrupted", retryable = true, cause = error))
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (!closed.get() && !finished.isCompleted) {
                fail(LiveTranscriptionException("Soniox Live connection closed (code $statusCode)", retryable = true))
            }
            return null
        }
    }
}
