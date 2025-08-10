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
    private val settingsService: SettingsService
) {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Internal state
    private var autoSend = true
    private var recordingText = ""
    
    // Outgoing events (from service to UI)
    private val _textReceived = MutableSharedFlow<String>()
    val textReceived: SharedFlow<String> = _textReceived.asSharedFlow()
    
    private val _sendMessage = MutableSharedFlow<String>()
    val sendMessage: SharedFlow<String> = _sendMessage.asSharedFlow()
    
    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()
    
    /**
     * Initialize UnifiedPTTService and start listening to settings changes
     */
    fun initialize() {
        serviceScope.launch {
            settingsService.settingsFlow.collect { settings ->
                settings?.let {
                    autoSend = it.autoSend
                }
            }
        }
    }
    
    /**
     * Handle PTT button press (mouse down or hotkey press)
     */
    suspend fun onPTTPressed() {
        // If already recording, stop current and start new
        if (_recordingState.value) {
            try {
                sttService.stopAndTranscribe() // Discard result
            } catch (e: Exception) {
                println("[UnifiedPTT] Failed to stop previous recording: ${e.message}")
            }
        }
        
        // Start recording
        _recordingState.value = true
        recordingText = ""
        
        try {
            sttService.startRecording()
        } catch (e: Exception) {
            println("[UnifiedPTT] Failed to start recording: ${e.message}")
            _recordingState.value = false
        }
    }
    
    /**
     * Handle PTT button release (mouse up or hotkey release)
     */
    suspend fun onPTTReleased() {
        if (!_recordingState.value) return
        
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
     * Cancel recording without sending
     */
    suspend fun cancelRecordingOnly() {
        if (_recordingState.value) {
            _recordingState.value = false
            
            try {
                sttService.stopAndTranscribe() // Discard result
                _textReceived.emit("") // Clear the input
            } catch (e: Exception) {
                println("[UnifiedPTT] Failed to cancel recording: ${e.message}")
            }
        }
    }
    
    
    @PreDestroy
    fun cleanup() {
        serviceScope.launch {
            if (_recordingState.value) {
                sttService.stopAndTranscribe()
            }
        }
    }
}