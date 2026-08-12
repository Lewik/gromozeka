package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.client.LiveVoiceProviderVadService
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.remote.protocol.RemoteAudioChunk
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.RemotePcmByteOrder
import com.gromozeka.remote.protocol.LiveVoiceProviderVadFailedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadSpeechStartedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadSpeechStoppedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStatusEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStoppedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadTranscriptCompletedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadTranscriptDeltaEvent
import com.gromozeka.shared.audio.SpeechPcmWav
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LiveVoiceInputState {
    IDLE,
    STARTING,
    LISTENING,
    SPEECH,
    TRANSCRIBING,
}

interface LiveVoiceInputService {
    val state: StateFlow<LiveVoiceInputState>
    val statusMessage: StateFlow<String?>
    val unavailableReason: StateFlow<String?>

    suspend fun start()
    suspend fun stop()
    suspend fun toggle()
    fun shutdown()
}

class NoOpLiveVoiceInputService(
    reason: String = "Непрерывный голосовой ввод недоступен на этом клиенте",
) : LiveVoiceInputService {
    private val _state = MutableStateFlow(LiveVoiceInputState.IDLE)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _unavailableReason = MutableStateFlow<String?>(reason)

    override val state: StateFlow<LiveVoiceInputState> = _state
    override val statusMessage: StateFlow<String?> = _statusMessage
    override val unavailableReason: StateFlow<String?> = _unavailableReason

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun toggle() = Unit
    override fun shutdown() = Unit
}

class LiveVoiceInputController(
    private val appViewModel: AppViewModel,
    private val audioRecorder: ClientAudioRecorder,
    private val audioTranscriptionService: AudioTranscriptionService,
    private val liveVoiceProviderVadService: LiveVoiceProviderVadService,
    private val clientSideSpeechToTextService: ClientSideSpeechToTextService,
    private val ttsQueue: TtsQueue,
    private val settingsService: SettingsService,
    private val messageInputClientPlatform: MessageInputContext.ClientPlatform,
    private val scope: CoroutineScope,
) : LiveVoiceInputService {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LiveVoiceInputState.IDLE)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _unavailableReason = MutableStateFlow<String?>(null)
    private var sessionJob: Job? = null
    private var recordingSession: ClientAudioRecordingSession? = null

    override val state: StateFlow<LiveVoiceInputState> = _state
    override val statusMessage: StateFlow<String?> = _statusMessage
    override val unavailableReason: StateFlow<String?> = _unavailableReason

    init {
        _unavailableReason.value = "Проверка доступности непрерывного голосового ввода"
        scope.launch {
            while (isActive) {
                if (_state.value == LiveVoiceInputState.IDLE) {
                    refreshAvailability()
                }
                delay(AVAILABILITY_REFRESH_MILLIS)
            }
        }
    }

    override suspend fun start() {
        val reason = refreshAvailability()
        if (reason != null) {
            _statusMessage.value = reason
            return
        }

        mutex.withLock {
            if (sessionJob?.isActive == true) return
            _state.value = LiveVoiceInputState.STARTING
            _statusMessage.value = "Запуск непрерывного голосового ввода"
            sessionJob = scope.launch { runLiveSession() }
        }
    }

    override suspend fun stop() {
        val lifecycle = mutex.withLock {
            ActiveLiveVoiceSession(
                job = sessionJob,
                recordingSession = recordingSession,
            ).also {
                sessionJob = null
            }
        }
        if (lifecycle.job != null) {
            lifecycle.job.cancelAndJoin()
        } else {
            lifecycle.recordingSession?.cancel()
        }
        mutex.withLock {
            if (recordingSession == lifecycle.recordingSession) {
                recordingSession = null
            }
        }
        finishStopped()
    }

    override suspend fun toggle() {
        if (_state.value == LiveVoiceInputState.IDLE) {
            start()
        } else {
            stop()
        }
    }

    override fun shutdown() {
        sessionJob?.cancel()
        recordingSession?.cancel()
        sessionJob = null
        recordingSession = null
    }

    private suspend fun runLiveSession() {
        when (settingsService.userDeviceSettings.voiceInputSettings.liveVoiceVadMode) {
            UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.LOCAL_VAD -> runLocalVadLiveSession()
            UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.PROVIDER_VAD -> runProviderVadLiveSession()
        }
    }

    private suspend fun runLocalVadLiveSession() {
        val ownJob = currentCoroutineContext()[Job]
        val utterances = Channel<ByteArray>(Channel.UNLIMITED)
        val microphoneGate = LiveVoiceMicrophoneGate()
        var speaking = false
        var terminalStatus: String? = null
        try {
            val session = audioRecorder.start(scope)
            mutex.withLock { recordingSession = session }
            _state.value = LiveVoiceInputState.LISTENING
            _statusMessage.value = "Слушаю голос"

            coroutineScope {
                val collector = launch {
                    val segmenter = LiveVoiceVadSegmenter()
                    try {
                        session.audioChunks.collect { chunk ->
                            when (val decision = microphoneGate.accept(ttsQueue.isPlaying.value)) {
                                is LiveVoiceMicrophoneGateDecision.Suppress -> {
                                    if (decision.started) {
                                        _state.value = LiveVoiceInputState.LISTENING
                                        _statusMessage.value = LIVE_VOICE_TTS_SUPPRESSED_STATUS
                                    }
                                    return@collect
                                }

                                is LiveVoiceMicrophoneGateDecision.Allow -> {
                                    if (decision.resumed) {
                                        _state.value = LiveVoiceInputState.LISTENING
                                        _statusMessage.value = "Слушаю голос"
                                    }
                                }
                            }
                            for (event in segmenter.accept(chunk)) {
                                when (event) {
                                    LiveVoiceVadEvent.SpeechStarted -> {
                                        speaking = true
                                        _state.value = LiveVoiceInputState.SPEECH
                                        _statusMessage.value = "Слушаю фразу"
                                        ttsQueue.blockAndClear()
                                    }

                                    is LiveVoiceVadEvent.Utterance -> {
                                        speaking = false
                                        utterances.send(event.pcmBigEndian)
                                        _state.value = LiveVoiceInputState.LISTENING
                                        _statusMessage.value = "Фраза отправлена на распознавание"
                                    }

                                    LiveVoiceVadEvent.SpeechDiscarded -> {
                                        speaking = false
                                        ttsQueue.allowPlayback()
                                        _state.value = LiveVoiceInputState.LISTENING
                                        _statusMessage.value = "Короткий шум отброшен"
                                    }
                                }
                            }
                        }
                    } finally {
                        val flushed = segmenter.flush()
                        if (flushed != null) {
                            utterances.trySend(flushed)
                        }
                        utterances.close()
                    }
                }

                val transcriber = launch {
                    for (pcmBigEndian in utterances) {
                        transcribeAndDeliver(pcmBigEndian)
                        if (!speaking && isActive) {
                            ttsQueue.allowPlayback()
                            _state.value = LiveVoiceInputState.LISTENING
                            _statusMessage.value = "Слушаю голос"
                        }
                    }
                }

                collector.join()
                transcriber.join()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (ownJob?.isCancelled == true) {
                throw CancellationException("Live voice input stopped", error)
            }
            log.warn(error) { "Live voice input failed: ${error.message}" }
            terminalStatus = "Непрерывный голосовой ввод остановлен: ${error.message}"
        } finally {
            recordingSession?.cancel()
            mutex.withLock {
                recordingSession = null
                if (sessionJob == ownJob) {
                    sessionJob = null
                }
            }
            finishStopped(statusMessage = terminalStatus)
        }
    }

    private suspend fun runProviderVadLiveSession() {
        val ownJob = currentCoroutineContext()[Job]
        var terminalStatus: String? = null
        var providerSession: com.gromozeka.client.LiveVoiceProviderVadSession? = null
        try {
            val speechToText = settingsService.userProfile.speechSettings.speechToText
            val recording = audioRecorder.start(scope)
            mutex.withLock { recordingSession = recording }
            providerSession = liveVoiceProviderVadService.start(
                languageCode = speechToText.mainLanguageCode,
                prompt = null,
            )
            _state.value = LiveVoiceInputState.LISTENING
            _statusMessage.value = "Provider VAD слушает голос"

            coroutineScope {
                val sender = launch {
                    val microphoneGate = LiveVoiceMicrophoneGate()
                    var sequenceNumber = 0
                    recording.audioChunks.collect { chunk ->
                        when (val decision = microphoneGate.accept(ttsQueue.isPlaying.value)) {
                            is LiveVoiceMicrophoneGateDecision.Suppress -> {
                                if (decision.started) {
                                    _state.value = LiveVoiceInputState.LISTENING
                                    _statusMessage.value = LIVE_VOICE_TTS_SUPPRESSED_STATUS
                                }
                                return@collect
                            }

                            is LiveVoiceMicrophoneGateDecision.Allow -> {
                                if (decision.resumed) {
                                    _state.value = LiveVoiceInputState.LISTENING
                                    _statusMessage.value = "Provider VAD слушает голос"
                                }
                            }
                        }
                        providerSession?.sendAudioChunk(
                            RemotePcmAudioChunk(
                                sequenceNumber = sequenceNumber++,
                                data = chunk,
                                sampleRate = SpeechPcmWav.SAMPLE_RATE,
                                channels = SpeechPcmWav.CHANNELS,
                                bitsPerSample = SpeechPcmWav.BITS_PER_SAMPLE,
                                byteOrder = RemotePcmByteOrder.BIG_ENDIAN,
                            )
                        )
                    }
                }

                val receiver = launch {
                    providerSession?.events?.collect { event ->
                        when (event) {
                            is LiveVoiceProviderVadStatusEvent ->
                                _statusMessage.value = event.message

                            is LiveVoiceProviderVadSpeechStartedEvent -> {
                                _state.value = LiveVoiceInputState.SPEECH
                                _statusMessage.value = "Provider VAD: слушаю фразу"
                                ttsQueue.blockAndClear()
                            }

                            is LiveVoiceProviderVadSpeechStoppedEvent -> {
                                _state.value = LiveVoiceInputState.TRANSCRIBING
                                _statusMessage.value = "Provider VAD: распознаю фразу"
                            }

                            is LiveVoiceProviderVadTranscriptDeltaEvent -> {
                                if (event.delta.isNotBlank()) {
                                    _state.value = LiveVoiceInputState.TRANSCRIBING
                                    _statusMessage.value = "Provider VAD: идет распознавание"
                                }
                            }

                            is LiveVoiceProviderVadTranscriptCompletedEvent -> {
                                deliverText(event.text, event.itemId.ifBlank { uuid7() })
                                ttsQueue.allowPlayback()
                                _state.value = LiveVoiceInputState.LISTENING
                                _statusMessage.value = "Provider VAD слушает голос"
                            }

                            is LiveVoiceProviderVadFailedEvent -> {
                                terminalStatus = "Provider VAD остановлен: ${event.message}"
                                error(event.message)
                            }

                            is LiveVoiceProviderVadStoppedEvent ->
                                return@collect

                            else -> Unit
                        }
                    }
                }

                receiver.invokeOnCompletion { sender.cancel() }
                sender.invokeOnCompletion { receiver.cancel() }
                receiver.join()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (ownJob?.isCancelled == true) {
                throw CancellationException("Provider VAD live voice input stopped", error)
            }
            log.warn(error) { "Provider VAD live voice input failed: ${error.message}" }
            terminalStatus = terminalStatus ?: "Provider VAD остановлен: ${error.message}"
        } finally {
            recordingSession?.cancel()
            runCatching { providerSession?.stop() }
            providerSession?.closeLocally()
            mutex.withLock {
                recordingSession = null
                if (sessionJob == ownJob) {
                    sessionJob = null
                }
            }
            finishStopped(statusMessage = terminalStatus)
        }
    }

    private suspend fun transcribeAndDeliver(pcmBigEndian: ByteArray) {
        if (pcmBigEndian.isEmpty()) return

        val sessionId = uuid7()
        val text = runCatching {
            _state.value = LiveVoiceInputState.TRANSCRIBING
            _statusMessage.value = "Распознаю фразу"
            val wav = SpeechPcmWav.encode(pcm16BigEndianToLittleEndian(pcmBigEndian))
            val recording = RemoteAudioRecording(
                sessionId = sessionId,
                format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
                chunks = listOf(RemoteAudioChunk(sequenceNumber = 0, data = wav)),
            )
            if (clientSideSpeechToTextService.isEnabled()) {
                clientSideSpeechToTextService.transcribe(recording)
            } else {
                audioTranscriptionService.transcribe(recording)
            }.trim()
        }.getOrElse { error ->
            log.warn(error) { "Live voice transcription failed: session=$sessionId error=${error.message}" }
            _statusMessage.value = "Не удалось распознать фразу: ${error.message}"
            return
        }

        if (text.isBlank()) {
            log.info { "Live voice transcription returned blank text: session=$sessionId" }
            _statusMessage.value = "Пустая фраза"
            return
        }

        deliverText(text, sessionId)
    }

    private suspend fun deliverText(text: String, sessionId: String) {
        if (text.isBlank()) {
            log.info { "Live voice transcription returned blank text: session=$sessionId" }
            _statusMessage.value = "Пустая фраза"
            return
        }

        val currentTab = appViewModel.currentTab.value
        if (currentTab == null) {
            log.warn { "Live voice transcription has no current tab: session=$sessionId textChars=${text.length}" }
            _statusMessage.value = "Нет активного обсуждения для распознанного текста"
            return
        }

        val messageInputContext = MessageInputContext(
            modality = MessageInputContext.Modality.SPEECH_TO_TEXT,
            source = MessageInputContext.Source.LIVE_VOICE,
            clientPlatform = messageInputClientPlatform,
            reliability = MessageInputContext.Reliability.MAY_CONTAIN_RECOGNITION_ERRORS,
        )
        if (settingsService.userDeviceSettings.voiceInputSettings.autoSend) {
            log.info { "Live voice sending message: session=$sessionId textChars=${text.length}" }
            currentTab.sendMessageToSession(text, messageInputContext = messageInputContext)
        } else {
            log.info { "Live voice adding composer draft: session=$sessionId textChars=${text.length}" }
            currentTab.appendUserInput(text, messageInputContext)
        }
    }

    private suspend fun finishStopped(statusMessage: String? = null) {
        ttsQueue.allowPlayback()
        _state.value = LiveVoiceInputState.IDLE
        _statusMessage.value = statusMessage
    }

    private suspend fun refreshAvailability(): String? {
        val speechToText = settingsService.userProfile.speechSettings.speechToText
        val liveVoiceSettings = settingsService.userDeviceSettings.voiceInputSettings
        val localReason = audioRecorder.unavailableReason
        val reason = when {
            !speechToText.enabled -> "Speech-to-text выключен"
            speechToText.audioSource != SpeechAudioSource.CurrentClient ->
                "Непрерывный голосовой ввод работает только с микрофоном текущего клиента"
            localReason != null -> localReason
            !audioRecorder.supportsStreamingAudioChunks ->
                "Непрерывный голосовой ввод требует потоковые audio chunks на клиенте"
            liveVoiceSettings.liveVoiceVadMode == UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.PROVIDER_VAD ->
                runCatching {
                    liveVoiceProviderVadService.unavailableReason()
                }.getOrElse { error ->
                    "Не удалось проверить Provider VAD: ${error.message}"
                }
            clientSideSpeechToTextService.isEnabled() -> null
            else -> runCatching {
                audioTranscriptionService.captureUnavailableReason()
            }.getOrElse { error ->
                "Не удалось проверить доступность распознавания голоса: ${error.message}"
            }
        }
        _unavailableReason.value = reason
        return reason
    }

    private data class ActiveLiveVoiceSession(
        val job: Job?,
        val recordingSession: ClientAudioRecordingSession?,
    )

    private companion object {
        const val AVAILABILITY_REFRESH_MILLIS = 5_000L
        const val LIVE_VOICE_TTS_SUPPRESSED_STATUS = "Озвучиваю ответ, микрофон временно приглушен"
    }
}

internal sealed interface LiveVoiceMicrophoneGateDecision {
    data class Suppress(val started: Boolean) : LiveVoiceMicrophoneGateDecision
    data class Allow(val resumed: Boolean) : LiveVoiceMicrophoneGateDecision
}

internal class LiveVoiceMicrophoneGate {
    private var suppressed = false

    fun accept(ttsIsPlaying: Boolean): LiveVoiceMicrophoneGateDecision =
        if (ttsIsPlaying) {
            LiveVoiceMicrophoneGateDecision.Suppress(started = !suppressed).also {
                suppressed = true
            }
        } else {
            LiveVoiceMicrophoneGateDecision.Allow(resumed = suppressed).also {
                suppressed = false
            }
        }
}

internal data class LiveVoiceVadConfig(
    val sampleRate: Int = SpeechPcmWav.SAMPLE_RATE,
    val bytesPerSample: Int = 2,
    val startThreshold: Double = 0.020,
    val continueThreshold: Double = 0.012,
    val preRollMillis: Int = 500,
    val silenceMillis: Int = 900,
    val minSpeechMillis: Int = 300,
    val maxUtteranceMillis: Int = 60_000,
) {
    val bytesPerMillisecond: Int = sampleRate * bytesPerSample / 1000
    val preRollBytes: Int = preRollMillis * bytesPerMillisecond
    val silenceBytes: Int = silenceMillis * bytesPerMillisecond
    val minSpeechBytes: Int = minSpeechMillis * bytesPerMillisecond
    val maxUtteranceBytes: Int = maxUtteranceMillis * bytesPerMillisecond
}

internal sealed interface LiveVoiceVadEvent {
    data object SpeechStarted : LiveVoiceVadEvent
    data object SpeechDiscarded : LiveVoiceVadEvent
    data class Utterance(val pcmBigEndian: ByteArray) : LiveVoiceVadEvent
}

internal class LiveVoiceVadSegmenter(
    private val config: LiveVoiceVadConfig = LiveVoiceVadConfig(),
) {
    private var preRoll = ByteArray(0)
    private val utterance = PcmByteAccumulator()
    private var speechBytes = 0
    private var trailingSilenceBytes = 0
    private var inSpeech = false

    fun accept(chunk: ByteArray): List<LiveVoiceVadEvent> {
        if (chunk.isEmpty()) return emptyList()

        val events = mutableListOf<LiveVoiceVadEvent>()
        val rms = chunk.rmsPcm16BigEndian()
        val isSpeechChunk = rms >= if (inSpeech) config.continueThreshold else config.startThreshold

        if (!inSpeech) {
            appendPreRoll(chunk)
            if (isSpeechChunk) {
                inSpeech = true
                utterance.clear()
                utterance.append(preRoll)
                speechBytes = chunk.size
                trailingSilenceBytes = 0
                events += LiveVoiceVadEvent.SpeechStarted
            }
            return events
        }

        utterance.append(chunk)
        if (isSpeechChunk) {
            speechBytes += chunk.size
            trailingSilenceBytes = 0
        } else {
            trailingSilenceBytes += chunk.size
        }

        when {
            utterance.byteSize >= config.maxUtteranceBytes -> events += finishUtterance()
            trailingSilenceBytes >= config.silenceBytes -> events += finishUtterance()
        }

        return events
    }

    fun flush(): ByteArray? =
        (finishUtterance() as? LiveVoiceVadEvent.Utterance)?.pcmBigEndian

    private fun finishUtterance(): LiveVoiceVadEvent {
        val completed = utterance.toByteArray()
        val accepted = inSpeech && speechBytes >= config.minSpeechBytes
        inSpeech = false
        utterance.clear()
        speechBytes = 0
        trailingSilenceBytes = 0
        preRoll = ByteArray(0)
        return if (accepted) LiveVoiceVadEvent.Utterance(completed) else LiveVoiceVadEvent.SpeechDiscarded
    }

    private fun appendPreRoll(chunk: ByteArray) {
        preRoll = when {
            chunk.size >= config.preRollBytes -> chunk.copyOfRange(chunk.size - config.preRollBytes, chunk.size)
            else -> preRoll + chunk
        }
        if (preRoll.size > config.preRollBytes) {
            preRoll = preRoll.copyOfRange(preRoll.size - config.preRollBytes, preRoll.size)
        }
    }
}

private class PcmByteAccumulator {
    private val chunks = mutableListOf<ByteArray>()
    var byteSize: Int = 0
        private set

    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        chunks += chunk
        byteSize += chunk.size
    }

    fun clear() {
        chunks.clear()
        byteSize = 0
    }

    fun toByteArray(): ByteArray {
        val output = ByteArray(byteSize)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
        }
        return output
    }
}

internal fun pcm16BigEndianToLittleEndian(input: ByteArray): ByteArray {
    require(input.size % 2 == 0) { "PCM16 byte array size must be even: ${input.size}" }
    val output = ByteArray(input.size)
    var index = 0
    while (index < input.size) {
        output[index] = input[index + 1]
        output[index + 1] = input[index]
        index += 2
    }
    return output
}

private fun ByteArray.rmsPcm16BigEndian(): Double {
    if (size < 2) return 0.0
    var sumSquares = 0.0
    var samples = 0
    var index = 0
    while (index + 1 < size) {
        val sample = ((this[index].toInt() shl 8) or (this[index + 1].toInt() and 0xff)).toShort().toInt()
        val normalized = sample / 32768.0
        sumSquares += normalized * normalized
        samples += 1
        index += 2
    }
    return kotlin.math.sqrt(sumSquares / samples)
}
