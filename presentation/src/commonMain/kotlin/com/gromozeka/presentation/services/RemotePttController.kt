package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val _unavailableReason = MutableStateFlow<String?>("Проверка доступности записи голоса")
    private var captureLifecycle: CaptureLifecycle? = null
    private var armedWorkerSource: SpeechAudioSource.WorkerInput? = null

    override val state: StateFlow<PttState> = _state
    override val statusMessage: StateFlow<String?> = _statusMessage
    override val unavailableReason: StateFlow<String?> = _unavailableReason

    init {
        scope.launch {
            while (isActive) {
                if (_state.value == PttState.IDLE) {
                    refreshAvailability()
                }
                delay(AVAILABILITY_REFRESH_MILLIS)
            }
        }
    }

    override fun initialize() = Unit

    override suspend fun toggleVoiceCapture() {
        when (_state.value) {
            PttState.IDLE -> beginRecording(
                settingsService.userProfile.speechSettings.speechToText.audioSource
            )
            PttState.PREPARING,
            PttState.RECORDING -> handlePTTRelease()
            PttState.TRANSCRIBING -> Unit
        }
    }

    override suspend fun handlePTTEvent(event: PTTEvent) {
        log.info { "PTT event received: event=$event state=${_state.value}" }
        when (event) {
            PTTEvent.BUTTON_DOWN -> handleButtonDown()
            PTTEvent.SINGLE_PUSH,
            PTTEvent.DOUBLE_PUSH -> confirmHold()
            PTTEvent.SINGLE_CLICK -> stopCurrentTts("single click")
            PTTEvent.DOUBLE_CLICK -> interruptCurrentSession()
        }
    }

    override suspend fun handlePTTRelease() {
        log.info { "PTT release received: state=${_state.value}" }
        val release = mutex.withLock {
            armedWorkerSource = null
            when (val current = captureLifecycle) {
                null -> ReleaseAction.None
                is CaptureLifecycle.Preparing -> {
                    current.cancelRequested = true
                    _statusMessage.value = "Отмена подготовки микрофона"
                    ReleaseAction.CancelPreparation(current)
                }
                is CaptureLifecycle.Recording -> {
                    captureLifecycle = null
                    _state.value = PttState.TRANSCRIBING
                    _statusMessage.value = null
                    ReleaseAction.Transcribe(current)
                }
            }
        }

        val capture = when (release) {
            ReleaseAction.None -> return
            is ReleaseAction.CancelPreparation -> {
                cancelRemotePreparation(release.preparation)
                return
            }
            is ReleaseAction.Transcribe -> release.capture
        }

        val text = try {
            runCatching {
                when (val session = capture.session) {
                    is ActiveRecordingSession.Local -> transcribeLocal(session)
                    is ActiveRecordingSession.Worker ->
                        audioTranscriptionService.stopCapture(session.sessionId).trim()
                }
            }.getOrElse { error ->
                _statusMessage.value = "Не удалось распознать голос: ${error.message}"
                log.warn(error) {
                    "PTT recording or transcription failed: " +
                        "session=${capture.session.sessionId} error=${error.message}"
                }
                return
            }
        } finally {
            restoreSystemAudioAfterPtt(capture.systemAudioMuted)
            _state.value = PttState.IDLE
        }

        if (text.isBlank()) {
            _statusMessage.value = "Голос распознан как пустой текст"
            log.info { "PTT transcription returned blank text: session=${capture.session.sessionId}" }
            return
        }

        val currentTab = appViewModel.currentTab.value
        if (currentTab == null) {
            log.warn {
                "PTT transcription has no current tab: " +
                    "session=${capture.session.sessionId} textChars=${text.length}"
            }
            return
        }

        _statusMessage.value = null
        log.info {
            "PTT transcription sending message: " +
                "session=${capture.session.sessionId} textChars=${text.length}"
        }
        currentTab.sendMessageToSession(text)
    }

    override suspend fun handlePTTCancel() {
        log.info { "PTT cancel received: state=${_state.value}" }
        val cancellation = mutex.withLock {
            armedWorkerSource = null
            when (val current = captureLifecycle) {
                null -> {
                    _statusMessage.value = null
                    CancelAction.None
                }
                is CaptureLifecycle.Preparing -> {
                    current.cancelRequested = true
                    _statusMessage.value = "Отмена подготовки микрофона"
                    CancelAction.CancelPreparation(current)
                }
                is CaptureLifecycle.Recording -> {
                    captureLifecycle = null
                    _state.value = PttState.IDLE
                    CancelAction.CancelRecording(current)
                }
            }
        }

        val capture = when (cancellation) {
            CancelAction.None -> return
            is CancelAction.CancelPreparation -> {
                cancelRemotePreparation(cancellation.preparation)
                return
            }
            is CancelAction.CancelRecording -> cancellation.capture
        }
        runCatching { cancelSession(capture.session) }
            .onFailure { error ->
                log.warn(error) { "PTT recording cancel failed: ${error.message}" }
            }
        restoreSystemAudioAfterPtt(capture.systemAudioMuted)
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

    private suspend fun handleButtonDown() {
        when (val source = settingsService.userProfile.speechSettings.speechToText.audioSource) {
            SpeechAudioSource.CurrentClient -> beginRecording(source)
            is SpeechAudioSource.WorkerInput -> mutex.withLock {
                if (_state.value != PttState.IDLE || captureLifecycle != null) {
                    log.info { "PTT Worker hold skipped: state=${_state.value}" }
                    return
                }
                armedWorkerSource = source
                _statusMessage.value = null
            }
        }
    }

    private suspend fun confirmHold() {
        val source = mutex.withLock {
            armedWorkerSource?.also { armedWorkerSource = null }
        } ?: return
        beginRecording(source)
    }

    private suspend fun beginRecording(source: SpeechAudioSource) {
        val preparation = mutex.withLock {
            if (_state.value != PttState.IDLE || captureLifecycle != null) {
                log.info { "PTT recording start skipped: state=${_state.value}" }
                return
            }

            _statusMessage.value = null
            log.info { "PTT recording start requested" }
            _state.value = PttState.PREPARING
            CaptureLifecycle.Preparing(
                sessionId = uuid7(),
                source = source,
                systemAudioMuted = shouldMuteSystemAudioDuringPtt(),
            ).also { captureLifecycle = it }
        }
        scope.launch { prepareRecording(preparation, source) }
    }

    private suspend fun prepareRecording(
        preparation: CaptureLifecycle.Preparing,
        source: SpeechAudioSource,
    ) {
        var session: ActiveRecordingSession? = null
        try {
            if (source == SpeechAudioSource.CurrentClient) {
                audioRecorder.unavailableReason?.let { error(it) }
            }
            muteSystemAudioBeforePtt(preparation.systemAudioMuted)
            stopCurrentTts("button down")
            session = when (source) {
                SpeechAudioSource.CurrentClient -> ActiveRecordingSession.Local(
                    sessionId = preparation.sessionId,
                    value = audioRecorder.start(scope),
                )
                is SpeechAudioSource.WorkerInput -> {
                    audioTranscriptionService.startCapture(preparation.sessionId)
                    ActiveRecordingSession.Worker(preparation.sessionId)
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                cleanupPreparation(preparation, session, null)
            }
            throw error
        } catch (error: Throwable) {
            cleanupPreparation(preparation, session, error)
            return
        }

        val recordingStarted = mutex.withLock {
            check(captureLifecycle === preparation) { "PTT preparation changed unexpectedly" }
            if (preparation.cancelRequested) {
                false
            } else {
                captureLifecycle = CaptureLifecycle.Recording(
                    session = requireNotNull(session),
                    systemAudioMuted = preparation.systemAudioMuted,
                )
                _state.value = PttState.RECORDING
                true
            }
        }
        if (recordingStarted) {
            log.info { "PTT recording started" }
        } else {
            cleanupPreparation(preparation, session, null)
        }
    }

    private suspend fun cleanupPreparation(
        preparation: CaptureLifecycle.Preparing,
        session: ActiveRecordingSession?,
        error: Throwable?,
    ) {
        val reportFailure = mutex.withLock {
            if (captureLifecycle === preparation) {
                captureLifecycle = null
                _state.value = PttState.IDLE
                !preparation.cancelRequested && error != null
            } else {
                false
            }
        }
        session?.let {
            runCatching { cancelSession(it) }
                .onFailure { cancelError ->
                    log.warn(cancelError) { "PTT prepared session cancel failed: ${cancelError.message}" }
                }
        }
        restoreSystemAudioAfterPtt(preparation.systemAudioMuted)
        if (reportFailure) {
            val failure = requireNotNull(error)
            _statusMessage.value = "Не удалось открыть микрофон: ${failure.message}"
            log.warn(failure) { "PTT recording start failed: ${failure.message}" }
        } else {
            _statusMessage.value = null
            log.info { "PTT recording preparation cancelled" }
        }
    }

    private suspend fun cancelSession(session: ActiveRecordingSession) {
        when (session) {
            is ActiveRecordingSession.Local -> session.value.cancel()
            is ActiveRecordingSession.Worker ->
                audioTranscriptionService.cancelCapture(session.sessionId)
        }
    }

    private suspend fun cancelRemotePreparation(preparation: CaptureLifecycle.Preparing) {
        if (preparation.source !is SpeechAudioSource.WorkerInput) return
        runCatching { audioTranscriptionService.cancelCapture(preparation.sessionId) }
            .onFailure { error ->
                log.warn(error) {
                    "PTT remote preparation cancel failed: " +
                        "session=${preparation.sessionId} error=${error.message}"
                }
            }
    }

    private suspend fun muteSystemAudioBeforePtt(enabled: Boolean) {
        if (!enabled) return

        log.info { "PTT system audio mute requested" }
        systemAudioMuteService.mute()
    }

    private suspend fun restoreSystemAudioAfterPtt(enabled: Boolean) {
        if (!enabled) return

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

    private suspend fun refreshAvailability(source: SpeechAudioSource): String? {
        val localReason = when (source) {
            SpeechAudioSource.CurrentClient -> audioRecorder.unavailableReason
            is SpeechAudioSource.WorkerInput -> null
        }
        val usesClientTranscription =
            source == SpeechAudioSource.CurrentClient && clientSideSpeechToTextService.isEnabled()
        val reason = when {
            localReason != null -> localReason
            usesClientTranscription -> null
            else -> runCatching {
                audioTranscriptionService.captureUnavailableReason()
            }.getOrElse { error ->
                "Не удалось проверить доступность записи голоса: ${error.message}"
            }
        }
        _unavailableReason.value = reason
        return reason
    }

    private suspend fun refreshAvailability(): String? =
        refreshAvailability(settingsService.userProfile.speechSettings.speechToText.audioSource)

    private suspend fun transcribeLocal(session: ActiveRecordingSession.Local): String {
        val recording = session.value.stop()
        if (recording.byteSize == 0) {
            error("Записанное аудио пустое")
        }
        log.info {
            "PTT recording captured: session=${session.sessionId} " +
                "bytes=${recording.byteSize} format=${recording.format}"
        }
        val remoteRecording = recording.toRemoteRecording(session.sessionId)
        return if (clientSideSpeechToTextService.isEnabled()) {
            log.info { "PTT transcription using client-side speech-to-text: session=${session.sessionId}" }
            clientSideSpeechToTextService.transcribe(remoteRecording).trim()
        } else {
            log.info { "PTT transcription using remote speech-to-text: session=${session.sessionId}" }
            audioTranscriptionService.transcribe(remoteRecording).trim()
        }
    }

    private sealed interface CaptureLifecycle {
        val systemAudioMuted: Boolean

        data class Preparing(
            val sessionId: String,
            val source: SpeechAudioSource,
            override val systemAudioMuted: Boolean,
            var cancelRequested: Boolean = false,
        ) : CaptureLifecycle

        data class Recording(
            val session: ActiveRecordingSession,
            override val systemAudioMuted: Boolean,
        ) : CaptureLifecycle
    }

    private sealed interface ActiveRecordingSession {
        val sessionId: String

        data class Local(
            override val sessionId: String,
            val value: ClientAudioRecordingSession,
        ) : ActiveRecordingSession

        data class Worker(
            override val sessionId: String,
        ) : ActiveRecordingSession
    }

    private sealed interface ReleaseAction {
        data object None : ReleaseAction

        data class CancelPreparation(
            val preparation: CaptureLifecycle.Preparing,
        ) : ReleaseAction

        data class Transcribe(
            val capture: CaptureLifecycle.Recording,
        ) : ReleaseAction
    }

    private sealed interface CancelAction {
        data object None : CancelAction

        data class CancelPreparation(
            val preparation: CaptureLifecycle.Preparing,
        ) : CancelAction

        data class CancelRecording(
            val capture: CaptureLifecycle.Recording,
        ) : CancelAction
    }

    private companion object {
        const val AVAILABILITY_REFRESH_MILLIS = 5_000L
    }
}
