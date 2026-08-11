package com.gromozeka.presentation.services

import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.TtsTask
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AttachmentAcquisitionCapabilities(
    val filePicker: Boolean,
    val screenshot: Boolean,
)

sealed interface AttachmentAcquisitionEvent {
    data class Acquired(val uploads: List<ArtifactUpload>) : AttachmentAcquisitionEvent

    data class Failed(val message: String) : AttachmentAcquisitionEvent
}

interface AttachmentAcquisitionController {
    val capabilities: AttachmentAcquisitionCapabilities
    val externalEvents: Flow<AttachmentAcquisitionEvent>
        get() = emptyFlow()

    suspend fun pickAttachments(): List<ArtifactUpload>

    suspend fun captureScreenshot(): ArtifactUpload?

    fun close() = Unit
}

object NoOpAttachmentAcquisitionController : AttachmentAcquisitionController {
    override val capabilities = AttachmentAcquisitionCapabilities(
        filePicker = false,
        screenshot = false,
    )

    override suspend fun pickAttachments(): List<ArtifactUpload> = emptyList()

    override suspend fun captureScreenshot(): ArtifactUpload? = null
}

interface GlobalHotkeyController {
    fun initializeService()
    fun registerQuickTextActionHotkeys(handler: (QuickTextAction.Id) -> Unit) = Unit
    fun cleanup()
    fun isSupported(): Boolean = false
    fun getImplementationType(): String = "none"
}

object NoOpGlobalHotkeyController : GlobalHotkeyController {
    override fun initializeService() = Unit
    override fun cleanup() = Unit
}

interface SoundNotificationPlayer {
    suspend fun playAttentionSound()
    suspend fun playActivitySound()
    suspend fun playErrorSound()
}

object NoOpSoundNotificationPlayer : SoundNotificationPlayer {
    override suspend fun playAttentionSound() = Unit
    override suspend fun playActivitySound() = Unit
    override suspend fun playErrorSound() = Unit
}

enum class UiFeedbackEvent {
    ERROR,
}

class UiFeedbackController {
    private val _events = MutableSharedFlow<UiFeedbackEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<UiFeedbackEvent> = _events.asSharedFlow()

    fun notifyError() {
        _events.tryEmit(UiFeedbackEvent.ERROR)
    }
}

interface TtsQueue {
    val isPlaying: StateFlow<Boolean>
    fun start()
    suspend fun enqueue(task: TtsTask)
    suspend fun stopAndClear()
    suspend fun blockAndClear()
    suspend fun allowPlayback()
    fun shutdown()
}

class NoOpTtsQueue : TtsQueue {
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override fun start() = Unit
    override suspend fun enqueue(task: TtsTask) = Unit
    override suspend fun stopAndClear() = Unit
    override suspend fun blockAndClear() = Unit
    override suspend fun allowPlayback() = Unit
    override fun shutdown() = Unit
}

interface ClientAudioPlayer {
    suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String, volume: Float = 1.0f)
    suspend fun playPcmStream(chunks: Flow<ByteArray>, sampleRate: Int, channels: Int, bitsPerSample: Int)
    fun stop()
}

object NoOpClientAudioPlayer : ClientAudioPlayer {
    override suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String, volume: Float) = Unit
    override suspend fun playPcmStream(chunks: Flow<ByteArray>, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        chunks.collect {}
    }
    override fun stop() = Unit
}

interface SystemAudioMuteService {
    suspend fun mute()
    suspend fun restore()
}

object NoOpSystemAudioMuteService : SystemAudioMuteService {
    override suspend fun mute() = Unit
    override suspend fun restore() = Unit
}

interface PttRecordingService {
    val state: StateFlow<PttState>
    val statusMessage: StateFlow<String?>
    val unavailableReason: StateFlow<String?>
}

class NoOpPttRecordingService : PttRecordingService {
    override val state: StateFlow<PttState> = MutableStateFlow(PttState.IDLE)
    override val statusMessage: StateFlow<String?> = MutableStateFlow(null)
    override val unavailableReason: StateFlow<String?> = MutableStateFlow("Запись голоса недоступна")
}

enum class PttState {
    IDLE,
    PREPARING,
    RECORDING,
    TRANSCRIBING,
}

enum class PTTEvent {
    BUTTON_DOWN,
    SINGLE_CLICK,
    DOUBLE_CLICK,
    SINGLE_PUSH,
    DOUBLE_PUSH
}

interface PttEventHandler {
    fun initialize()
    suspend fun handlePTTEvent(event: PTTEvent)
    suspend fun handlePTTRelease()
    suspend fun handlePTTCancel() = Unit
}

object NoOpPttEventHandler : PttEventHandler {
    override fun initialize() = Unit
    override suspend fun handlePTTEvent(event: PTTEvent) = Unit
    override suspend fun handlePTTRelease() = Unit
}
