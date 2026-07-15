package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RemotePttController(
    private val appViewModel: AppViewModel,
    private val audioRecorder: ClientAudioRecorder,
    private val audioTranscriptionService: AudioTranscriptionService,
    private val clientSideSpeechToTextService: ClientSideSpeechToTextService,
    private val ttsQueue: TtsQueue,
    private val systemAudioMuteService: SystemAudioMuteService,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
) : PttEventHandler, PttRecordingService {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PttState.IDLE)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private var recordingSession: ClientAudioRecordingSession? = null

    override val state: StateFlow<PttState> = _state
    override val statusMessage: StateFlow<String?> = _statusMessage

    override fun initialize() = Unit

    override suspend fun handlePTTEvent(event: PTTEvent) {
        log.info { "PTT event received: event=$event recording=${recordingSession != null}" }
        when (event) {
            PTTEvent.BUTTON_DOWN -> startRecording()
            PTTEvent.SINGLE_PUSH,
            PTTEvent.DOUBLE_PUSH -> Unit
            PTTEvent.SINGLE_CLICK -> stopCurrentTts("single click")
            PTTEvent.DOUBLE_CLICK -> interruptCurrentSession()
        }
    }

    override suspend fun handlePTTRelease() {
        log.info { "PTT release received: recording=${recordingSession != null}" }
        val session = mutex.withLock {
            val current = recordingSession ?: return
            recordingSession = null
            _state.value = PttState.TRANSCRIBING
            _statusMessage.value = null
            current
        }

        val sessionId = uuid7()
        val text = try {
            val recording = runCatching {
                try {
                    session.stop()
                } finally {
                    restoreSystemAudioAfterPtt()
                }
            }
                .getOrElse { error ->
                    _statusMessage.value = "Не удалось записать голос: ${error.message}"
                    log.warn(error) { "PTT recording stop failed: ${error.message}" }
                    return
                }

            if (recording.byteSize == 0) {
                _statusMessage.value = "Не удалось записать голос: аудио пустое"
                log.info { "PTT recording ignored: empty audio" }
                return
            }

            log.info {
                "PTT recording captured: session=$sessionId bytes=${recording.byteSize} format=${recording.format}"
            }

            val remoteRecording = recording.toRemoteRecording(sessionId)
            runCatching {
                if (clientSideSpeechToTextService.isEnabled()) {
                    log.info { "PTT transcription using client-side speech-to-text: session=$sessionId" }
                    clientSideSpeechToTextService.transcribe(remoteRecording).trim()
                } else {
                    log.info { "PTT transcription using remote speech-to-text: session=$sessionId" }
                    audioTranscriptionService.transcribe(remoteRecording).trim()
                }
            }.getOrElse { error ->
                _statusMessage.value = "Не удалось распознать голос: ${error.message}"
                log.warn(error) { "PTT transcription failed: ${error.message}" }
                return
            }
        } finally {
            _state.value = PttState.IDLE
        }

        if (text.isBlank()) {
            _statusMessage.value = "Голос распознан как пустой текст"
            log.info { "PTT transcription returned blank text: session=$sessionId" }
            return
        }

        val currentTab = appViewModel.currentTab.value
        if (currentTab == null) {
            log.warn { "PTT transcription has no current tab: session=$sessionId textChars=${text.length}" }
            return
        }

        _statusMessage.value = null
        log.info { "PTT transcription sending message: session=$sessionId textChars=${text.length}" }
        currentTab.sendMessageToSession(text)
    }

    override suspend fun handlePTTCancel() {
        log.info { "PTT cancel received: recording=${recordingSession != null}" }
        val session = mutex.withLock {
            val current = recordingSession ?: return
            recordingSession = null
            _state.value = PttState.IDLE
            current
        }
        runCatching {
            try {
                session.cancel()
            } finally {
                restoreSystemAudioAfterPtt()
            }
        }.onFailure { error ->
            log.warn(error) { "PTT recording cancel failed: ${error.message}" }
        }
        _statusMessage.value = null
        log.info { "PTT recording cancelled" }
    }

    private suspend fun stopCurrentTts(reason: String) {
        _statusMessage.value = null
        log.info { "PTT TTS stop requested: reason=$reason" }
        ttsQueue.stopAndClear()
    }

    private suspend fun interruptCurrentSession() {
        _statusMessage.value = null
        log.info { "PTT double click interrupt requested" }
        appViewModel.sendInterruptToCurrentSession()
    }

    private suspend fun startRecording() {
        mutex.withLock {
            if (_state.value != PttState.IDLE || recordingSession != null) {
                log.info { "PTT recording start skipped: state=${_state.value}" }
                return
            }

            _statusMessage.value = null
            log.info { "PTT recording start requested" }
            muteSystemAudioBeforePtt()
            stopCurrentTts("button down")
            val session = runCatching { audioRecorder.start(scope) }
                .getOrElse { error ->
                    restoreSystemAudioAfterPtt()
                    _statusMessage.value = "Не удалось открыть микрофон: ${error.message}"
                    log.warn(error) { "PTT recording start failed: ${error.message}" }
                    return
                }

            recordingSession = session
            _state.value = PttState.RECORDING
            log.info { "PTT recording started" }
        }
    }

    private suspend fun muteSystemAudioBeforePtt() {
        if (!shouldMuteSystemAudioDuringPtt()) return

        log.info { "PTT system audio mute requested" }
        systemAudioMuteService.mute()
    }

    private suspend fun restoreSystemAudioAfterPtt() {
        if (!shouldMuteSystemAudioDuringPtt()) return

        withContext(NonCancellable) {
            log.info { "PTT system audio restore requested" }
            systemAudioMuteService.restore()
        }
    }

    private fun shouldMuteSystemAudioDuringPtt(): Boolean =
        (settingsService.settings.userDeviceSettings as? UserDeviceSettings.Desktop)
            ?.inputSettings
            ?.muteSystemAudioDuringPtt
            ?: false
}
