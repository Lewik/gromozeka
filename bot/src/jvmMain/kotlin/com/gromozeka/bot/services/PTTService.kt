package com.gromozeka.bot.services

import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.shared.audio.RecordingSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PTTService(
    private val audioRecorder: AudioRecorder,
    private val sttService: SttService,
    private val settingsService: SettingsService,
    private val audioMuteManager: AudioMuteManager,
) {


    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()

    // Current recording session
    private var currentRecordingSession: RecordingSession? = null
    private var recordingStartTime: Long = 0
    private val minRecordingDurationMs = 150L

    // Track mute state independently from recording session
    private var isMuted = false

    /**
     * Start PTT recording - simple suspend function
     */
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        // Cancel any existing recording
        currentRecordingSession?.cancel()

        val settings = settingsService.settings
        val audioConfig = AudioConfig(
            sampleRate = 16000,
            channels = 1,
            bitDepth = 16,
            chunkSizeBytes = 2048
        )

        try {
            // Mute audio first (inside try-catch to ensure cleanup)
            if (settings.muteSystemAudioDuringPTT) {
                println("[PTT] MUTING audio")
                isMuted = true  // Set flag BEFORE actual mute to prevent race condition
                audioMuteManager.mute()
            }

            println("[PTT] Starting recording")
            val session = audioRecorder.launchRecording(CoroutineScope(SupervisorJob() + Dispatchers.IO), audioConfig)
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

            // ALWAYS cleanup on failure (including mute restore)
            if (isMuted) {
                println("[PTT] RESTORING audio mute after startRecording error")
                audioMuteManager.restoreOriginalState()
                isMuted = false
            } else {
                println("[PTT] startRecording error but isMuted=false")
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

        val finalAudio = try {
            // Handle minimum duration
            val recordingDuration = System.currentTimeMillis() - recordingStartTime
            if (recordingDuration < minRecordingDurationMs) {
                println("[PTT] Recording too short (${recordingDuration}ms), waiting...")
                delay(minRecordingDurationMs - recordingDuration)
            }

            // Get final audio - this is the key operation
            withContext(NonCancellable) {
                session.stop()
            }

        } catch (e: Exception) {
            println("[PTT] Failed to stop recording: ${e.message}")
            null

        } finally {
            // ALWAYS restore audio immediately after recording attempt (success or failure)
            withContext(NonCancellable) {
                session.cancel()
                if (isMuted) {
                    println("[PTT] RESTORING audio mute after stopAndTranscribe")
                    audioMuteManager.restoreOriginalState()
                    isMuted = false
                } else {
                    println("[PTT] stopAndTranscribe but isMuted=false - no restore needed")
                }
            }
        }

        // Transcribe if we got audio (this happens AFTER mute is restored)  
        if (finalAudio != null) {
            try {
                val text = sttService.transcribe(finalAudio)
                println("[PTT] Recording stopped, transcribed: '${text.take(50)}${if (text.length > 50) "..." else ""}'")
                text
            } catch (e: Exception) {
                println("[PTT] Failed to transcribe audio: ${e.message}")
                ""
            }
        } else {
            ""
        }
    }

    /**
     * Cancel recording without transcription
     */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        val session = currentRecordingSession
        val wasRecording = _recordingState.value

        println("[PTT] Canceling recording (wasRecording=$wasRecording, session=${session != null}, isMuted=$isMuted)")

        // Always reset recording state
        _recordingState.value = false
        currentRecordingSession = null

        // Cleanup session if exists
        if (session != null) {
            withContext(NonCancellable) {
                session.cancel()
            }
        }

        // ALWAYS restore mute if it was set (independent of session state)
        if (isMuted) {
            withContext(NonCancellable) {
                println("[PTT] RESTORING audio mute after cancel")
                audioMuteManager.restoreOriginalState()
                isMuted = false
            }
            println("[PTT] Audio mute restored after cancel")
        } else {
            println("[PTT] Cancel called but isMuted=false - no restore needed")
        }
    }
}