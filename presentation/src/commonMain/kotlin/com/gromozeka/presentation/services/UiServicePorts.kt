package com.gromozeka.presentation.services

import com.gromozeka.domain.model.TtsTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ScreenCaptureController {
    suspend fun captureWindow(): String?
    suspend fun captureFullScreen(): String?
    suspend fun captureArea(): String?
}

interface GlobalHotkeyController {
    fun initializeService()
    fun cleanup()
    fun isSupported(): Boolean = false
    fun getImplementationType(): String = "none"
}

object NoOpGlobalHotkeyController : GlobalHotkeyController {
    override fun initializeService() = Unit
    override fun cleanup() = Unit
}

interface SoundNotificationPlayer {
    suspend fun playErrorSound()
    suspend fun playMessageSound()
    suspend fun playReadySound()
}

object NoOpSoundNotificationPlayer : SoundNotificationPlayer {
    override suspend fun playErrorSound() = Unit
    override suspend fun playMessageSound() = Unit
    override suspend fun playReadySound() = Unit
}

interface TtsQueue {
    val isPlaying: StateFlow<Boolean>
    fun start()
    suspend fun enqueue(task: TtsTask)
    fun stopAndClear()
    fun shutdown()
}

class NoOpTtsQueue : TtsQueue {
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override fun start() = Unit
    override suspend fun enqueue(task: TtsTask) = Unit
    override fun stopAndClear() = Unit
    override fun shutdown() = Unit
}

interface ClientAudioPlayer {
    suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String)
    suspend fun playPcmStream(chunks: Flow<ByteArray>, sampleRate: Int, channels: Int, bitsPerSample: Int)
}

object NoOpClientAudioPlayer : ClientAudioPlayer {
    override suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String) = Unit
    override suspend fun playPcmStream(chunks: Flow<ByteArray>, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        chunks.collect {}
    }
}

interface PttRecordingService {
    val recordingState: StateFlow<Boolean>
    val statusMessage: StateFlow<String?>
}

class NoOpPttRecordingService : PttRecordingService {
    override val recordingState: StateFlow<Boolean> = MutableStateFlow(false)
    override val statusMessage: StateFlow<String?> = MutableStateFlow(null)
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
}

object NoOpPttEventHandler : PttEventHandler {
    override fun initialize() = Unit
    override suspend fun handlePTTEvent(event: PTTEvent) = Unit
    override suspend fun handlePTTRelease() = Unit
}
