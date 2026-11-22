package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.remote.protocol.RemoteAudioRecording
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
    private val transcriptionQueue: VoiceTranscriptionQueue,
    private val ttsQueue: TtsQueue,
    private val systemAudioMuteService: SystemAudioMuteService,
    private val settingsService: SettingsService,
    private val uiFeedbackController: UiFeedbackController,
    private val voiceInputDelivery: VoiceInputDelivery,
    private val scope: CoroutineScope,
) : PttEventHandler, PttRecordingService {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val _target = MutableStateFlow<VoiceInputTarget?>(null)
    override val target: StateFlow<VoiceInputTarget?> = _target
    private val _state = MutableStateFlow(PttState.IDLE)
    private val _statusMessage = MutableStateFlow<LocalizedText?>(null)
    private val _unavailableReason = MutableStateFlow<LocalizedText?>(localizedText("voice.checkingAvailability"))
    private var captureLifecycle: CaptureLifecycle? = null

    override val state: StateFlow<PttState> = _state
    override val statusMessage: StateFlow<LocalizedText?> = _statusMessage
    override val unavailableReason: StateFlow<LocalizedText?> = _unavailableReason

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

    override val canRecord: Boolean
        get() = _state.value == PttState.IDLE && _unavailableReason.value == null &&
            settingsService.userProfile.speechSettings.speechToText.enabled

    override fun initialize() = Unit

    override suspend fun handlePTTEvent(event: PTTEvent) {
        log.info { "PTT event received: event=$event state=${_state.value}" }
        when (event) {
            PTTEvent.SINGLE_PUSH -> if (canRecord) {
                val target = voiceInputDelivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK)
                if (target == null) {
                    reportError(localizedText("voice.noConversation"))
                } else {
                    beginRecording(
                        settingsService.userProfile.speechSettings.speechToText.audioSource,
                        target,
                    )
                }
            }
            PTTEvent.SINGLE_CLICK -> stopCurrentTts("single click")
            PTTEvent.DOUBLE_CLICK -> {
                stopCurrentTts("double click")
                interruptCurrentSession()
            }
        }
    }

    override suspend fun handlePTTRelease() {
        log.info { "PTT release received: state=${_state.value}" }
        val release = mutex.withLock {
            when (val current = captureLifecycle) {
                null -> ReleaseAction.None
                is CaptureLifecycle.Preparing -> {
                    current.completionRequest = PreparationCompletion.RELEASE
                    _statusMessage.value = localizedText("voice.finishingRecording")
                    ReleaseAction.AwaitPreparation
                }
                is CaptureLifecycle.Recording -> {
                    captureLifecycle = null
                    _state.value = PttState.FINALIZING
                    _statusMessage.value = localizedText("voice.finishingRecording")
                    ReleaseAction.Transcribe(current)
                }
            }
        }

        val capture = when (release) {
            ReleaseAction.None -> return
            ReleaseAction.AwaitPreparation -> return
            is ReleaseAction.Transcribe -> release.capture
        }

        finalizeAndQueue(capture)
    }

    private suspend fun finalizeAndQueue(capture: CaptureLifecycle.Recording) {
        var failure: LocalizedText? = null
        try {
            val recording = try {
                when (val session = capture.session) {
                    is ActiveRecordingSession.Local -> stopLocalRecording(session)
                    is ActiveRecordingSession.Worker -> audioTranscriptionService.stopCapture(session.sessionId)
                }
            } catch (error: CancellationException) {
                cancelAfterFailedFinalization(capture.session)
                throw error
            } catch (error: Throwable) {
                failure = localizedText("voice.recognitionFailed", "error" to error.localizedText())
                cancelAfterFailedFinalization(capture.session)
                log.warn(error) {
                    "PTT recording finalization failed: session=${capture.session.sessionId} error=${error.message}"
                }
                null
            }
            if (recording != null) {
                withContext(NonCancellable) {
                    transcriptionQueue.submit(recording, capture.target)
                }
            }
        } finally {
            restoreSystemAudioAfterPtt(capture.systemAudioMuted)
            finishPtt()
        }
        failure?.let(::reportError)
    }

    override suspend fun handlePTTCancel() {
        log.info { "PTT cancel received: state=${_state.value}" }
        val cancellation = mutex.withLock {
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
                withContext(NonCancellable) {
                    cancelRemotePreparation(cancellation.preparation)
                    cancellation.preparation.preparationJob?.join()
                    restoreSystemAudioAfterPtt(cancellation.preparation.systemAudioMuted)
                    finishPtt()
                }
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

    private suspend fun beginRecording(source: SpeechAudioSource, target: VoiceInputTarget) {
        val preparation = mutex.withLock {
            if (_state.value != PttState.IDLE || captureLifecycle != null) {
                log.info { "PTT recording start skipped: state=${_state.value}" }
                return
            }

            _target.value = target
            _statusMessage.value = null
            log.info { "PTT recording start requested" }
            _state.value = PttState.PREPARING
            CaptureLifecycle.Preparing(
                sessionId = uuid7(),
                source = source,
                target = target,
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
                audioRecorder.unavailableReason?.let { throw LocalizedTextException(it) }
            }
            muteSystemAudioBeforePtt(preparation.systemAudioMuted)
            withTimeout(PREPARATION_TIMEOUT_MILLIS) {
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
        val preparedAction = try {
            mutex.withLock {
                val recording = CaptureLifecycle.Recording(
                    session = preparedSession,
                    target = preparation.target,
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
                            _state.value = PttState.FINALIZING
                            _statusMessage.value = localizedText("voice.finishingRecording")
                            PreparedAction.Transcribe(recording)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                cleanupPreparation(preparation, preparedSession, null)
            }
            throw error
        }
        when (preparedAction) {
            PreparedAction.Recording -> log.info { "PTT recording started" }
            PreparedAction.Cancel -> cleanupPreparation(preparation, preparedSession, null)
            is PreparedAction.Transcribe -> finalizeAndQueue(preparedAction.capture)
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
        if (session != null) {
            runCatching { cancelSession(session) }
                .onFailure { cancelError ->
                    log.warn(cancelError) { "PTT prepared session cancel failed: ${cancelError.message}" }
                }
        } else {
            cancelRemotePreparation(preparation)
        }
        if (!cleanupResult.ownsLifecycle) {
            log.info { "PTT recording preparation cancelled by its owner" }
            return
        }

        restoreSystemAudioAfterPtt(preparation.systemAudioMuted)
        finishPtt()
        if (cleanupResult.reportFailure) {
            val failure = requireNotNull(error)
            reportError(localizedText("voice.microphoneFailed", "error" to failure.localizedText()))
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

    private suspend fun cancelAfterFailedFinalization(session: ActiveRecordingSession) {
        withContext(NonCancellable) {
            runCatching { cancelSession(session) }
                .onFailure { error ->
                    log.warn(error) {
                        "PTT failed recording cleanup failed: " +
                            "session=${session.sessionId} error=${error.message}"
                    }
                }
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

    private fun reportError(message: LocalizedText) {
        _statusMessage.value = message
        uiFeedbackController.notifyError()
    }

    private suspend fun refreshAvailability(source: SpeechAudioSource): LocalizedText? {
        val localReason = when (source) {
            SpeechAudioSource.CurrentClient -> audioRecorder.unavailableReason
            is SpeechAudioSource.WorkerInput -> null
        }
        val reason = when {
            localReason != null -> localReason
            else -> runCatching {
                audioTranscriptionService.captureUnavailableReason()?.localizedText()
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                localizedText("voice.availabilityFailed", "error" to error.localizedText())
            }
        }
        _unavailableReason.value = reason
        return reason
    }

    private suspend fun refreshAvailability(): LocalizedText? =
        refreshAvailability(settingsService.userProfile.speechSettings.speechToText.audioSource)

    private suspend fun stopLocalRecording(session: ActiveRecordingSession.Local): RemoteAudioRecording {
        val recording = session.value.stop()
        if (recording.byteSize == 0) {
            throw LocalizedTextException(localizedText("voice.emptyRecording"))
        }
        log.info {
            "PTT recording captured: session=${session.sessionId} " +
                "bytes=${recording.byteSize} format=${recording.format}"
        }
        return recording.toRemoteRecording(session.sessionId)
    }

    private sealed interface CaptureLifecycle {
        val systemAudioMuted: Boolean
        val target: VoiceInputTarget

        data class Preparing(
            val sessionId: String,
            val source: SpeechAudioSource,
            override val target: VoiceInputTarget,
            override val systemAudioMuted: Boolean,
            var completionRequest: PreparationCompletion? = null,
            var preparationJob: Job? = null,
        ) : CaptureLifecycle

        data class Recording(
            val session: ActiveRecordingSession,
            override val target: VoiceInputTarget,
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
