package com.gromozeka.bot.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class PTTEvent {
    SINGLE_CLICK,    // Just stop TTS
    DOUBLE_CLICK,    // Stop TTS + send interrupt
    SINGLE_PUSH,     // Stop TTS + start PTT recording
    DOUBLE_PUSH      // Stop TTS + send interrupt + start PTT recording
}

interface PTTEventHandler {
    suspend fun handlePTTEvent(event: PTTEvent)
    suspend fun handlePTTRelease()
}

class PTTEventRouter(
    private val unifiedPTTService: UnifiedPTTService,
    private val ttsQueueService: TTSQueueService
) : PTTEventHandler {
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _events = MutableSharedFlow<PTTEvent>()
    val events: SharedFlow<PTTEvent> = _events
    
    // Interrupt command management
    private val _interruptCommand = MutableSharedFlow<Unit>()
    val interruptCommand: MutableSharedFlow<Unit> = _interruptCommand
    private var interruptExecutor: (suspend () -> Boolean)? = null
    
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
    
    override suspend fun handlePTTEvent(event: PTTEvent) {
        _events.emit(event)
        
        when (event) {
            PTTEvent.SINGLE_CLICK -> {
                // Stop TTS + cancel recording without sending
                ttsQueueService.stopAndClear()
                unifiedPTTService.cancelRecordingOnly()
            }
            
            PTTEvent.DOUBLE_CLICK -> {
                // Stop TTS + send interrupt + cancel recording
                ttsQueueService.stopAndClear()
                _interruptCommand.emit(Unit)
                unifiedPTTService.cancelRecordingOnly()
            }
            
            PTTEvent.SINGLE_PUSH -> {
                // Stop TTS + start PTT recording
                ttsQueueService.stopAndClear()
                unifiedPTTService.onPTTPressed()
            }
            
            PTTEvent.DOUBLE_PUSH -> {
                // Stop TTS + send interrupt + start PTT recording
                ttsQueueService.stopAndClear()
                _interruptCommand.emit(Unit)
                unifiedPTTService.onPTTPressed()
            }
        }
    }
    
    override suspend fun handlePTTRelease() {
        // Always same behavior regardless of single/double press
        unifiedPTTService.onPTTReleased()
    }
}