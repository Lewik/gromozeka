package com.gromozeka.bot.services

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class PTTEvent {
    BUTTON_DOWN,     // Stop TTS + start PTT recording (immediate)
    MODIFIER_CONFLICT, // Cancel PTT recording due to hotkey conflict
    SINGLE_CLICK,    // Cancel PTT recording
    DOUBLE_CLICK,    // Cancel PTT recording + send interrupt
    SINGLE_PUSH,     // Transcribe PTT recording
    DOUBLE_PUSH      // Transcribe PTT recording + send interrupt
}

class PTTEventRouter(
    private val pttService: PTTService,
    private val ttsQueueService: TTSQueueService,
) {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<PTTEvent>()
    val events: SharedFlow<PTTEvent> = _events

    // PTT results for UI
    private val _textTranscribed = MutableSharedFlow<String>()
    val textTranscribed: SharedFlow<String> = _textTranscribed.asSharedFlow()


    // Interrupt command management
    private val _interruptCommand = MutableSharedFlow<Unit>()
    val interruptCommand: MutableSharedFlow<Unit> = _interruptCommand
    private var interruptExecutor: (suspend () -> Boolean)? = null

    // Current PTT session
    private var currentPTTJob: Job? = null

    /**
     * Initialize PTT event router and start listening to interrupt commands
     */
    fun initialize() {
        serviceScope.launch {
            _interruptCommand.collect {
                interruptExecutor?.invoke()
            }
        }
    }

    /**
     * Set the interrupt executor that will be called when interrupt is requested
     */
    fun setInterruptExecutor(executor: suspend () -> Boolean) {
        interruptExecutor = executor
    }

    suspend fun handlePTTEvent(event: PTTEvent) {
        _events.emit(event)

        when (event) {
            PTTEvent.BUTTON_DOWN -> {
                // Stop TTS + start PTT recording immediately
                ttsQueueService.stopAndClear()
                startPTTRecording()
            }

            PTTEvent.MODIFIER_CONFLICT -> {
                // Cancel recording due to hotkey conflict (e.g. Ctrl+Tab)
                println("[PTT] Modifier conflict detected, canceling PTT")
                cancelCurrentPTT()
            }

            PTTEvent.SINGLE_CLICK -> {
                // Cancel recording without sending (TTS already stopped)
                cancelCurrentPTT()
            }

            PTTEvent.DOUBLE_CLICK -> {
                // Cancel recording + send interrupt (TTS already stopped)
                _interruptCommand.emit(Unit)
                cancelCurrentPTT()
            }

            PTTEvent.SINGLE_PUSH -> {
                // PTT already recording, just continue until release
                // No action needed - handlePTTRelease() will transcribe
            }

            PTTEvent.DOUBLE_PUSH -> {
                // PTT already recording + send interrupt
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
            if (text.isNotEmpty()) {
                _textTranscribed.emit(text)
            }
        } catch (e: Exception) {
            println("[PTT] Failed to transcribe recording: ${e.message}")
        }
    }

    private suspend fun startPTTRecording() {
        // Cancel any existing recording first
        currentPTTJob?.cancel()


        // Start PTT recording - simple suspend function call
        currentPTTJob = serviceScope.launch {
            try {
                // Start recording with timeout
                withTimeout(30.seconds) {
                    pttService.startRecording()
                    
                    // Wait for cancellation (PTT release) or timeout
                    awaitCancellation()
                }
            } catch (e: TimeoutCancellationException) {
                println("[PTT] Recording timeout after 30 seconds")
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

        // Cancel the recording without transcription
        try {
            pttService.cancelRecording()
        } catch (e: Exception) {
            println("[PTT] Failed to cancel recording: ${e.message}")
        }
    }
}