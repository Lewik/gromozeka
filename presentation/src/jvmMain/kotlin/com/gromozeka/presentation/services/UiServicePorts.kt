package com.gromozeka.presentation.services

import com.gromozeka.domain.model.TtsTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

interface PttRecordingService {
    val recordingState: StateFlow<Boolean>
}

class NoOpPttRecordingService : PttRecordingService {
    override val recordingState: StateFlow<Boolean> = MutableStateFlow(false)
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
