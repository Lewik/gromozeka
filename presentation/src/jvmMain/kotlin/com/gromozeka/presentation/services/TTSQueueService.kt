package com.gromozeka.presentation.services

import com.gromozeka.domain.model.TtsTask
import com.gromozeka.infrastructure.ai.springai.TtsService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.springframework.stereotype.Service

@Service
class TTSQueueService(
    private val ttsService: TtsService,
) {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ttsQueue = Channel<TtsTask>(Channel.UNLIMITED)
    private var currentPlaybackJob: Job? = null
    private var actorJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    fun start() {
        check(actorJob == null) { "TTS queue service already started" }

        actorJob = serviceScope.launch {
            for (task in ttsQueue) {
                try {
                    _isPlaying.value = true
                    currentPlaybackJob = launch {
                        ttsService.generateAndPlay(task)
                    }
                    currentPlaybackJob?.join()
                } catch (e: CancellationException) {
                    throw e // Re-throw for correct cancellation
                } catch (e: Exception) {
                    // Fail fast: don't hide internal component errors
                    throw IllegalStateException("TTS service failed for task: \"$task\"", e)
                } finally {
                    _isPlaying.value = false
                    currentPlaybackJob = null
                }
            }
        }
    }

    suspend fun enqueue(task: TtsTask) {
        require(task.text.isNotBlank()) { "TTS text cannot be blank" }
        check(actorJob != null) { "TTS service not started - call start() first" }
        check(serviceScope.isActive) { "TTS service is shut down" }

        ttsQueue.send(task)
    }

    fun stopAndClear() {
        check(actorJob != null) { "TTS service not started" }
        check(serviceScope.isActive) { "TTS service is already shut down" }

        // Stop current playback
        currentPlaybackJob?.cancel()
        currentPlaybackJob = null

        // Clear queue
        var cleared = 0
        while (ttsQueue.tryReceive().isSuccess) {
            cleared++
        }


        _isPlaying.value = false
    }

    fun shutdown() {
        if (actorJob != null && serviceScope.isActive) {
            stopAndClear()
        }
        actorJob?.cancel()
        actorJob = null
        serviceScope.cancel()
    }
}