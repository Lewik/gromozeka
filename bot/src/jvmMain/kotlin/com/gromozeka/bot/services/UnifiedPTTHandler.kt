package com.gromozeka.bot.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UnifiedPTTHandler(
    private val sttService: SttService,
    private val ttsQueueService: TTSQueueService,
    private val autoSend: Boolean,
    private val onTextReceived: (String) -> Unit,
    private val onSendMessage: suspend (String) -> Unit,
    private val onInterrupt: suspend () -> Unit
) : PTTGestureHandler {
    
    private val _currentMode = MutableStateFlow<PTTMode?>(null)
    val currentMode: StateFlow<PTTMode?> = _currentMode
    
    override suspend fun onSingleClick() {
        ttsQueueService.stopAndClear()
    }

    override suspend fun onDoubleClick() {
        ttsQueueService.stopAndClear()
        onInterrupt()
    }

    override suspend fun onPTTStart(mode: PTTMode) {
        ttsQueueService.stopAndClear()
        
        if (mode == PTTMode.DOUBLE) {
            onInterrupt()
        }
        
        if (_currentMode.value != null) {
            return
        }
        
        _currentMode.value = mode
        sttService.startRecording()
    }

    override suspend fun onPTTStop(mode: PTTMode) {
        if (_currentMode.value != mode) {
            return
        }
        
        _currentMode.value = null
        
        val text = sttService.stopAndTranscribe()
        if (text.isNotBlank()) {
            when (mode) {
                PTTMode.SINGLE -> {
                    // Normal mode - respect autoSend setting
                    if (autoSend) {
                        onSendMessage(text)
                    } else {
                        onTextReceived(text)
                    }
                }
                PTTMode.DOUBLE -> {
                    // Double hold - always send immediately
                    onSendMessage(text)
                }
            }
        }
    }
    
    fun isRecording(): Boolean = _currentMode.value != null
}