package com.gromozeka.bot.services

import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.shared.audio.RecordingSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
class PTTService(
    private val audioRecorder: AudioRecorder,
    private val sttService: SttService,
    private val settingsService: SettingsService,
    private val audioMuteManager: AudioMuteManager
) {
    
    
    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()
    
    // Current recording session
    private var currentRecordingSession: RecordingSession? = null
    private var recordingStartTime: Long = 0
    private val minRecordingDurationMs = 150L
    
    /**
     * Start PTT recording - simple suspend function
     */
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        // Cancel any existing recording
        currentRecordingSession?.cancel()
        
        val settings = settingsService.settings
        
        // Muting
        if (settings.muteSystemAudioDuringPTT) {
            audioMuteManager.mute()
        }
        
        val audioConfig = AudioConfig(
            sampleRate = 16000,
            channels = 1,
            bitDepth = 16,
            chunkSizeBytes = 2048
        )
        
        try {
            println("[PTT] Starting recording")
            val session = audioRecorder.launchRecording(CoroutineScope(currentCoroutineContext()), audioConfig)
            currentRecordingSession = session
            _recordingState.value = true
            recordingStartTime = System.currentTimeMillis()
            
            // Start background collection (but don't block here)
            launch {
                try {
                    session.audioChunks.collect { chunk ->
                        // Could emit for visualization if needed
                        // For now just consume to keep the stream alive
                    }
                } catch (e: Exception) {
                    // Audio stream ended
                }
            }
            
        } catch (e: Exception) {
            println("[PTT] Failed to start recording: ${e.message}")
            _recordingState.value = false
            currentRecordingSession = null
            
            // Cleanup on failure
            if (settings.muteSystemAudioDuringPTT) {
                audioMuteManager.restoreOriginalState()
            }
            throw e
        }
    }
    
    /**
     * Stop recording and get transcribed text - simple suspend function
     */
    suspend fun stopAndTranscribe(): String = withContext(Dispatchers.IO) {
        val session = currentRecordingSession
        val wasRecording = _recordingState.value
        
        if (!wasRecording || session == null) {
            println("[PTT] Not recording - ignoring stop")
            return@withContext ""
        }
        
        // Set state immediately
        _recordingState.value = false
        currentRecordingSession = null
        val settings = settingsService.settings
        
        try {
            // Handle minimum duration
            val recordingDuration = System.currentTimeMillis() - recordingStartTime
            if (recordingDuration < minRecordingDurationMs) {
                println("[PTT] Recording too short (${recordingDuration}ms), waiting...")
                delay(minRecordingDurationMs - recordingDuration)
            }
            
            // Get final audio - this is the key operation
            val finalAudio = withContext(NonCancellable) {
                session.stop()
            }
            
            // Transcribe
            val text = sttService.transcribe(finalAudio)
            println("[PTT] Recording stopped, transcribed: '${text.take(50)}${if (text.length > 50) "..." else ""}'")
            
            
            return@withContext text
            
        } catch (e: Exception) {
            println("[PTT] Failed to stop recording: ${e.message}")
            return@withContext ""
            
        } finally {
            // ALWAYS cleanup - this is critical
            withContext(NonCancellable) {
                session.cancel()
                if (settings.muteSystemAudioDuringPTT) {
                    audioMuteManager.restoreOriginalState()
                }
            }
        }
    }
    
    /**
     * Cancel recording without transcription
     */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        val session = currentRecordingSession
        val wasRecording = _recordingState.value
        
        if (wasRecording && session != null) {
            println("[PTT] Canceling recording")
            _recordingState.value = false
            currentRecordingSession = null
            val settings = settingsService.settings
            
            // Cleanup
            withContext(NonCancellable) {
                session.cancel()
                if (settings.muteSystemAudioDuringPTT) {
                    audioMuteManager.restoreOriginalState()
                }
            }
            
        } else {
            println("[PTT] No recording to cancel")
        }
    }
}