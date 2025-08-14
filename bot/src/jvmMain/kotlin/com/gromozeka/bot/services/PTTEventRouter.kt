package com.gromozeka.bot.services

import com.gromozeka.bot.viewmodel.AppViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration.Companion.minutes

enum class PTTEvent {
    BUTTON_DOWN,     // Stop TTS + start PTT recording (immediate)
    SINGLE_CLICK,    // Cancel PTT recording
    DOUBLE_CLICK,    // Cancel PTT recording + send interrupt
    SINGLE_PUSH,     // Transcribe PTT recording
    DOUBLE_PUSH      // Transcribe PTT recording + send interrupt
}

class PTTEventRouter(
    private val pttService: PTTService,
    private val ttsQueueService: TTSQueueService,
    private val appViewModel: AppViewModel,
    private val settingsService: SettingsService,
) {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<PTTEvent>()
    val events: SharedFlow<PTTEvent> = _events

    // Interrupt command management
    private val _interruptCommand = MutableSharedFlow<Unit>()
    val interruptCommand: MutableSharedFlow<Unit> = _interruptCommand

    // Current PTT session
    private var currentPTTJob: Job? = null

    // Target session ViewModel captured at PTT start
    private var targetViewModel: com.gromozeka.bot.viewmodel.TabViewModel? = null

    /**
     * Initialize PTT event router and start listening to interrupt commands
     */
    fun initialize() {
        serviceScope.launch {
            _interruptCommand.collect {
                appViewModel.sendInterruptToCurrentSession()
            }
        }
    }


    suspend fun handlePTTEvent(event: PTTEvent) {
        _events.emit(event)

        when (event) {
            PTTEvent.BUTTON_DOWN -> {
                // Capture target session ViewModel at PTT start to prevent race conditions
                targetViewModel = appViewModel.currentTab.value
                // Start PTT recording immediately (TTS continues playing muted)
                startPTTRecording()
            }

            PTTEvent.SINGLE_CLICK -> {
                // Stop TTS + Cancel recording without sending
                ttsQueueService.stopAndClear()
                cancelCurrentPTT()
            }

            PTTEvent.DOUBLE_CLICK -> {
                // Stop TTS + Cancel recording + send interrupt
                ttsQueueService.stopAndClear()
                _interruptCommand.emit(Unit)
                cancelCurrentPTT()
            }

            PTTEvent.SINGLE_PUSH -> {
                // Stop TTS + Continue recording until release
                ttsQueueService.stopAndClear()
                // No other action needed - handlePTTRelease() will transcribe
            }

            PTTEvent.DOUBLE_PUSH -> {
                // Stop TTS + PTT already recording + send interrupt
                ttsQueueService.stopAndClear()
                _interruptCommand.emit(Unit)
                // Continue recording until release
            }
        }
    }

    suspend fun handlePTTRelease() {
        // Cancel current PTT recording job
        currentPTTJob?.cancel()
        currentPTTJob = null

        // Get transcribed text from the recording
        try {
            val text = pttService.stopAndTranscribe()
            if (text.isNotEmpty() && targetViewModel != null) {
                val currentSettings = settingsService.settingsFlow.value
                if (currentSettings.autoSend) {
                    // Send message directly to target session
                    targetViewModel!!.sendMessageToSession(text)
                } else {
                    // Set text in user input field for editing
                    targetViewModel!!.userInput = text
                }
            }
        } catch (e: Exception) {
            println("[PTT] Failed to transcribe recording: ${e.message}")
        } finally {
            // Clear target session reference
            targetViewModel = null
        }
    }

    private suspend fun startPTTRecording() {
        // Cancel any existing recording first
        currentPTTJob?.cancel()

        // Create fresh scope to avoid cancelled context issues
        val freshScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Start PTT recording - simple suspend function call
        currentPTTJob = freshScope.launch {
            try {
                // Start recording with timeout
                withTimeout(5.minutes) {
                    pttService.startRecording()

                    // Wait for cancellation (PTT release) or timeout
                    awaitCancellation()
                }
            } catch (e: TimeoutCancellationException) {
                println("[PTT] Recording timeout after 5 minutes")
            } catch (e: CancellationException) {
                // Normal PTT release, don't log
            } catch (e: Exception) {
                println("[PTT] Failed to start recording: ${e.message}")
            } finally {
                // Recording state is managed by PTTService
            }
        }
    }

    private suspend fun cancelCurrentPTT() {
        currentPTTJob?.cancel()
        currentPTTJob = null
        targetViewModel = null // Clear captured target session

        // Cancel the recording without transcription
        try {
            pttService.cancelRecording()
        } catch (e: Exception) {
            println("[PTT] Failed to cancel recording: ${e.message}")
        }
    }

}