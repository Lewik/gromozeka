package com.gromozeka.bot.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy

@Service
class UnifiedPTTService(
    private val sttService: SttService,
    private val ttsQueueService: TTSQueueService,
    private val settingsService: SettingsService
) {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Internal state
    private var isRecording = false
    private var autoSend = true
    private var recordingText = ""
    
    // Outgoing events (from service to UI)
    private val _textReceived = MutableSharedFlow<String>()
    val textReceived: SharedFlow<String> = _textReceived.asSharedFlow()
    
    private val _sendMessage = MutableSharedFlow<String>()
    val sendMessage: SharedFlow<String> = _sendMessage.asSharedFlow()
    
    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()
    
    // Incoming commands (from UI to service)
    private val _interruptCommand = MutableSharedFlow<Unit>()
    val interruptCommand: MutableSharedFlow<Unit> = _interruptCommand
    
    // Subscribe to interrupt commands and execute provider
    private var interruptExecutor: (suspend () -> Boolean)? = null
    
    init {
        // Subscribe to settings changes
        serviceScope.launch {
            settingsService.settingsFlow.collect { settings ->
                settings?.let {
                    autoSend = it.autoSend
                }
            }
        }
        
        // Subscribe to interrupt commands
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
    
    /**
     * Handle PTT button press (mouse down or hotkey press)
     */
    suspend fun onPTTPressed() {
        if (isRecording) {
            // Already recording, treat as interrupt
            _interruptCommand.emit(Unit)
            return
        }
        
        // Stop TTS before starting recording
        ttsQueueService.stopAndClear()
        
        // Start recording
        isRecording = true
        _recordingState.value = true
        recordingText = ""
        
        try {
            sttService.startRecording()
        } catch (e: Exception) {
            println("[UnifiedPTT] Failed to start recording: ${e.message}")
            isRecording = false
            _recordingState.value = false
        }
    }
    
    /**
     * Handle PTT button release (mouse up or hotkey release)
     */
    suspend fun onPTTReleased() {
        if (!isRecording) return
        
        isRecording = false
        _recordingState.value = false
        
        try {
            val finalText = sttService.stopAndTranscribe()
            
            if (finalText.isNotBlank()) {
                // Update UI with final text
                _textReceived.emit(finalText)
                
                // Auto-send if enabled
                if (autoSend) {
                    _sendMessage.emit(finalText)
                }
            }
        } catch (e: Exception) {
            println("[UnifiedPTT] Failed to stop recording: ${e.message}")
        }
    }
    
    /**
     * Handle escape key press
     */
    suspend fun onEscapePressed() {
        if (isRecording) {
            // Cancel recording without sending
            isRecording = false
            _recordingState.value = false
            
            try {
                sttService.stopAndTranscribe() // Discard result
                _textReceived.emit("") // Clear the input
            } catch (e: Exception) {
                println("[UnifiedPTT] Failed to cancel recording: ${e.message}")
            }
        } else {
            // Not recording, treat as interrupt
            _interruptCommand.emit(Unit)
        }
    }
    
    /**
     * Get current recording state
     */
    fun isRecording(): Boolean = isRecording
    
    @PreDestroy
    fun cleanup() {
        serviceScope.launch {
            if (isRecording) {
                sttService.stopAndTranscribe()
            }
        }
    }
}