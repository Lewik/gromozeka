package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class RemotePttController(
    private val appViewModel: AppViewModel,
    private val audioRecorder: ClientAudioRecorder,
    private val audioTranscriptionService: AudioTranscriptionService,
    private val clientSideSpeechToTextService: ClientSideSpeechToTextService,
    private val ttsQueue: TtsQueue,
    private val systemAudioMuteService: SystemAudioMuteService,
    private val settingsService: SettingsService,
    private val uiFeedbackController: UiFeedbackController,
    private val messageInputClientPlatform: MessageInputContext.ClientPlatform,
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
                    current.completionRequest = PreparationCompletion.RELEASE
                    _statusMessage.value = "Завершение записи"
                    ReleaseAction.AwaitPreparation
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
            ReleaseAction.AwaitPreparation -> return
            is ReleaseAction.Transcribe -> release.capture
        }

        transcribeAndDeliver(capture)
    }

    private suspend fun transcribeAndDeliver(capture: CaptureLifecycle.Recording) {
        val text = try {
            runCatching {
                when (val session = capture.session) {
                    is ActiveRecordingSession.Local -> transcribeLocal(session)
                    is ActiveRecordingSession.Worker ->
                        audioTranscriptionService.stopCapture(session.sessionId).trim()
                }
            }.getOrElse { error ->
                reportError("Не удалось распознать голос: ${error.message}")
                log.warn(error) {
                    "PTT recording or transcription failed: " +
                        "session=${capture.session.sessionId} error=${error.message}"
                }
                return
            }
        } finally {
            restoreSystemAudioAfterPtt(capture.systemAudioMuted)
            finishPtt()
        }

        if (text.isBlank()) {
            reportError("Голос распознан как пустой текст")
            log.info { "PTT transcription returned blank text: session=${capture.session.sessionId}" }
            return
        }

        val currentTab = appViewModel.currentTab.value
        if (currentTab == null) {
            reportError("Нет активного обсуждения для распознанного текста")
            log.warn {
                "PTT transcription has no current tab: " +
                    "session=${capture.session.sessionId} textChars=${text.length}"
            }
            return
        }

        _statusMessage.value = null
        val messageInputContext = MessageInputContext(
            modality = MessageInputContext.Modality.SPEECH_TO_TEXT,
            source = MessageInputContext.Source.PUSH_TO_TALK,
            clientPlatform = messageInputClientPlatform,
            reliability = MessageInputContext.Reliability.MAY_CONTAIN_RECOGNITION_ERRORS,
        )
        if (settingsService.userDeviceSettings.voiceInputSettings.autoSend) {
            log.info {
                "PTT transcription sending message: " +
                    "session=${capture.session.sessionId} textChars=${text.length}"
            }
            currentTab.sendMessageToSession(text, messageInputContext = messageInputContext)
        } else {
            log.info {
                "PTT transcription adding composer draft: " +
                    "session=${capture.session.sessionId} textChars=${text.length}"
            }
            currentTab.appendUserInput(text, messageInputContext)
        }
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
                    current.completionRequest = PreparationCompletion.CANCEL
                    captureLifecycle = null
                    current.preparationJob?.cancel(CancellationException("PTT preparation cancelled"))
                    _statusMessage.value = null
                    _state.value = PttState.IDLE
                    CancelAction.CancelPreparation(current)
                }
                is CaptureLifecycle.Recording -> {
                    captureLifecycle = null
                    CancelAction.CancelRecording(current)
                }
            }
        }

        val capture = when (cancellation) {
            CancelAction.None -> return
            is CancelAction.CancelPreparation -> {
                cancelRemotePreparation(cancellation.preparation)
                restoreSystemAudioAfterPtt(cancellation.preparation.systemAudioMuted)
                finishPtt()
                return
            }
            is CancelAction.CancelRecording -> cancellation.capture
        }
        runCatching { cancelSession(capture.session) }
            .onFailure { error ->
                log.warn(error) { "PTT recording cancel failed: ${error.message}" }
        }
        restoreSystemAudioAfterPtt(capture.systemAudioMuted)
        finishPtt()
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
        val preparationJob = scope.launch(start = CoroutineStart.LAZY) {
            prepareRecording(preparation, source)
        }
        preparation.preparationJob = preparationJob
        withContext(NonCancellable) {
            ttsQueue.blockAndClear()
        }
        val shouldStart = mutex.withLock {
            captureLifecycle === preparation && preparation.completionRequest != PreparationCompletion.CANCEL
        }
        if (shouldStart) {
            preparationJob.start()
        } else {
            preparationJob.cancel(CancellationException("PTT preparation cancelled before start"))
        }
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
            session = withTimeout(PREPARATION_TIMEOUT_MILLIS) {
                when (source) {
                    SpeechAudioSource.CurrentClient -> ActiveRecordingSession.Local(
                        sessionId = preparation.sessionId,
                        value = audioRecorder.start(scope),
                    )
                    is SpeechAudioSource.WorkerInput -> {
                        audioTranscriptionService.startCapture(preparation.sessionId)
                        ActiveRecordingSession.Worker(preparation.sessionId)
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            cleanupPreparation(preparation, session, error)
            return
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                cleanupPreparation(preparation, session, null)
            }
            throw error
        } catch (error: Throwable) {
            cleanupPreparation(preparation, session, error)
            return
        }

        val preparedSession = requireNotNull(session)
        val preparedAction = mutex.withLock {
            val recording = CaptureLifecycle.Recording(
                session = preparedSession,
                systemAudioMuted = preparation.systemAudioMuted,
            )
            if (captureLifecycle !== preparation) {
                PreparedAction.Cancel
            } else {
                when (preparation.completionRequest) {
                    null -> {
                        captureLifecycle = recording
                        _state.value = PttState.RECORDING
                        PreparedAction.Recording
                    }
                    PreparationCompletion.CANCEL -> PreparedAction.Cancel
                    PreparationCompletion.RELEASE -> {
                        captureLifecycle = null
                        _state.value = PttState.TRANSCRIBING
                        _statusMessage.value = null
                        PreparedAction.Transcribe(recording)
                    }
                }
            }
        }
        when (preparedAction) {
            PreparedAction.Recording -> log.info { "PTT recording started" }
            PreparedAction.Cancel -> cleanupPreparation(preparation, preparedSession, null)
            is PreparedAction.Transcribe -> transcribeAndDeliver(preparedAction.capture)
        }
    }

    private suspend fun cleanupPreparation(
        preparation: CaptureLifecycle.Preparing,
        session: ActiveRecordingSession?,
        error: Throwable?,
    ) {
        val cleanupResult = mutex.withLock {
            if (captureLifecycle === preparation) {
                captureLifecycle = null
                PreparationCleanupResult(
                    ownsLifecycle = true,
                    reportFailure = preparation.completionRequest != PreparationCompletion.CANCEL &&
                        error != null,
                )
            } else {
                PreparationCleanupResult(ownsLifecycle = false, reportFailure = false)
            }
        }
        session?.let {
            runCatching { cancelSession(it) }
                .onFailure { cancelError ->
                    log.warn(cancelError) { "PTT prepared session cancel failed: ${cancelError.message}" }
                }
        }
        restoreSystemAudioAfterPtt(preparation.systemAudioMuted)
        if (cleanupResult.ownsLifecycle) {
            finishPtt()
        }
        if (cleanupResult.reportFailure) {
            val failure = requireNotNull(error)
            reportError("Не удалось открыть микрофон: ${failure.message}")
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

    private suspend fun finishPtt() {
        withContext(NonCancellable) {
            _statusMessage.value = null
            ttsQueue.allowPlayback()
            _state.value = PttState.IDLE
        }
    }

    private fun shouldMuteSystemAudioDuringPtt(): Boolean =
        (settingsService.settings.userDeviceSettings as? UserDeviceSettings.Desktop)
            ?.inputSettings
            ?.muteSystemAudioDuringPtt
            ?: false

    private fun reportError(message: String) {
        _statusMessage.value = message
        uiFeedbackController.notifyError()
    }

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
            var completionRequest: PreparationCompletion? = null,
            var preparationJob: Job? = null,
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

    private enum class PreparationCompletion {
        CANCEL,
        RELEASE,
    }

    private data class PreparationCleanupResult(
        val ownsLifecycle: Boolean,
        val reportFailure: Boolean,
    )

    private sealed interface PreparedAction {
        data object Recording : PreparedAction

        data object Cancel : PreparedAction

        data class Transcribe(
            val capture: CaptureLifecycle.Recording,
        ) : PreparedAction
    }

    private sealed interface ReleaseAction {
        data object None : ReleaseAction

        data object AwaitPreparation : ReleaseAction

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
        const val PREPARATION_TIMEOUT_MILLIS = 15_000L
    }
}
