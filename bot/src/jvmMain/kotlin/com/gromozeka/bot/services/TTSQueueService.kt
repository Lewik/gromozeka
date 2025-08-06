package com.gromozeka.bot.services

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.springframework.stereotype.Service

@Service
class TTSQueueService(
    private val ttsService: TtsService
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ttsQueue = Channel<String>(Channel.UNLIMITED)
    private var currentPlaybackJob: Job? = null
    private var actorJob: Job? = null
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize
    
    fun start() {
        check(actorJob == null) { "TTS queue service already started" }
        
        actorJob = serviceScope.launch {
            for (text in ttsQueue) {
                try {
                    _isPlaying.value = true
                    currentPlaybackJob = launch {
                        ttsService.generateAndPlay(text)
                    }
                    currentPlaybackJob?.join()
                } catch (e: CancellationException) {
                    throw e // Re-throw for correct cancellation
                } catch (e: Exception) {
                    // Fail fast: don't hide internal component errors
                    throw IllegalStateException("TTS service failed for text: \"$text\"", e)
                } finally {
                    _isPlaying.value = false
                    currentPlaybackJob = null
                    updateQueueSize()
                }
            }
        }
    }
    
    fun enqueue(text: String) {
        require(text.isNotBlank()) { "TTS text cannot be blank" }
        check(actorJob != null) { "TTS service not started - call start() first" }
        check(serviceScope.isActive) { "TTS service is shut down" }
        
        // Fail fast: synchronous sending
        runBlocking {
            ttsQueue.send(text)
            updateQueueSize()
        }
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
        updateQueueSize()
    }
    
    private fun updateQueueSize() {
        // Approximate queue size calculation
        // Channel doesn't provide exact size, but this is for UI indication
        val wasEmpty = _queueSize.value == 0
        val isEmpty = _queueSize.value == 0 && !_isPlaying.value
        
        // Simple heuristic: if started playing and queue was empty, then size is 1
        // If finished playing, then size is 0
        _queueSize.value = when {
            _isPlaying.value && wasEmpty -> 1
            !_isPlaying.value -> 0
            else -> _queueSize.value
        }
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