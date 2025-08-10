package com.gromozeka.bot.services

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class PTTEvent {
    SINGLE_CLICK,    // Just stop TTS
    DOUBLE_CLICK,    // Stop TTS + send interrupt
    SINGLE_PUSH,     // Stop TTS + start PTT recording
    DOUBLE_PUSH      // Stop TTS + send interrupt + start PTT recording
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
            PTTEvent.SINGLE_CLICK -> {
                // Stop TTS + cancel recording without sending
                ttsQueueService.stopAndClear()
                cancelCurrentPTT()
            }

            PTTEvent.DOUBLE_CLICK -> {
                // Stop TTS + send interrupt + cancel recording
                ttsQueueService.stopAndClear()
                _interruptCommand.emit(Unit)
                cancelCurrentPTT()
            }

            PTTEvent.SINGLE_PUSH -> {
                // Stop TTS + start PTT recording
                ttsQueueService.stopAndClear()
                startPTTRecording()
            }

            PTTEvent.DOUBLE_PUSH -> {
                // Stop TTS + send interrupt + start PTT recording
                ttsQueueService.stopAndClear()
                _interruptCommand.emit(Unit)
                startPTTRecording()
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