package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteAudioTranscriptionService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemotePttController(
    private val appViewModel: AppViewModel,
    private val audioRecorder: ClientAudioRecorder,
    private val audioTranscriptionService: RemoteAudioTranscriptionService,
    private val scope: CoroutineScope,
) : PttEventHandler, PttRecordingService {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val _recordingState = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private var recordingSession: ClientAudioRecordingSession? = null

    override val recordingState: StateFlow<Boolean> = _recordingState
    override val statusMessage: StateFlow<String?> = _statusMessage

    override fun initialize() = Unit

    override suspend fun handlePTTEvent(event: PTTEvent) {
        log.info { "PTT event received: event=$event recording=${recordingSession != null}" }
        when (event) {
            PTTEvent.BUTTON_DOWN,
            PTTEvent.SINGLE_PUSH,
            PTTEvent.DOUBLE_PUSH -> startRecording()
            PTTEvent.SINGLE_CLICK,
            PTTEvent.DOUBLE_CLICK -> Unit
        }
    }

    override suspend fun handlePTTRelease() {
        log.info { "PTT release received: recording=${recordingSession != null}" }
        val session = mutex.withLock {
            val current = recordingSession ?: return
            recordingSession = null
            _recordingState.value = false
            current
        }

        val recording = runCatching { session.stop() }
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

        val sessionId = uuid7()
        log.info {
            "PTT recording captured: session=$sessionId bytes=${recording.byteSize} mediaType=${recording.mediaType}"
        }

        val text = runCatching {
            audioTranscriptionService.transcribe(recording.toRemoteRecording(sessionId)).trim()
        }.getOrElse { error ->
            _statusMessage.value = "Не удалось распознать голос: ${error.message}"
            log.warn(error) { "PTT transcription failed: ${error.message}" }
            return
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

    private suspend fun startRecording() {
        mutex.withLock {
            if (recordingSession != null) {
                log.info { "PTT recording start skipped: already recording" }
                return
            }

            _statusMessage.value = null
            log.info { "PTT recording start requested" }
            val session = runCatching { audioRecorder.start(scope) }
                .getOrElse { error ->
                    _statusMessage.value = "Не удалось открыть микрофон: ${error.message}"
                    log.warn(error) { "PTT recording start failed: ${error.message}" }
                    return
                }

            recordingSession = session
            _recordingState.value = true
            log.info { "PTT recording started" }
        }
    }
}
