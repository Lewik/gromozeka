package com.gromozeka.bot.services

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import org.springframework.stereotype.Service
import java.util.logging.Level
import java.util.logging.Logger

@Service
class GlobalHotkeyService(
    private val settingsService: SettingsService,
    private val unifiedPTTService: UnifiedPTTService
) {
    
    companion object {
        // Global hotkey: Fn + Control
        private const val HOTKEY_FN_KEYCODE = 63  // macOS fn key code (0x3F)
        private const val HOTKEY_CTRL_KEYCODE = NativeKeyEvent.VC_CONTROL
        private const val HOTKEY_CTRL_MASK = NativeKeyEvent.CTRL_L_MASK
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gestureDetector: PTTGestureDetector? = null
    
    private val _gestureEvents = MutableSharedFlow<PTTGestureEvent>()
    val gestureEvents: SharedFlow<PTTGestureEvent> = _gestureEvents
    
    private var isRegistered = false
    
    fun initializeService() {
        // Create gesture detector with handler that delegates to UnifiedPTTService
        val handler = object : PTTGestureHandler {
            override suspend fun onPTTStart(mode: PTTMode) {
                unifiedPTTService.onPTTPressed()
            }
            
            override suspend fun onPTTStop(mode: PTTMode) {
                unifiedPTTService.onPTTReleased()
            }
            
            override suspend fun onSingleClick() {
                // Single click - just cancel recording or stop TTS
                unifiedPTTService.onEscapePressed()
            }
            
            override suspend fun onDoubleClick() {
                // Double click - interrupt command
                unifiedPTTService.interruptCommand.emit(Unit)
            }
        }
        gestureDetector = PTTGestureDetector(handler, serviceScope)
        
        // Also start listening to settings
        startListeningToSettings()
    }
    
    private fun startListeningToSettings() {
        // Subscribe to settings changes
        serviceScope.launch {
            settingsService.settingsFlow.collectLatest { settings ->
                settings?.let { 
                    handleSettingsUpdate(it.globalPttHotkeyEnabled)
                }
            }
        }
    }
    
    private fun handleSettingsUpdate(enabled: Boolean) {
        if (enabled && !isRegistered) {
            initialize()
            println("[HOTKEY] Global hotkey service enabled via settings")
        } else if (!enabled && isRegistered) {
            shutdown()
            println("[HOTKEY] Global hotkey service disabled via settings")
        }
    }
    
    private val keyListener = object : NativeKeyListener {
        override fun nativeKeyPressed(e: NativeKeyEvent) {
            // Debug logging for fn+ctrl key detection
            if (e.keyCode == HOTKEY_FN_KEYCODE || e.keyCode == HOTKEY_CTRL_KEYCODE) {
                println("[HOTKEY] Key detected: keyCode=${e.keyCode}, modifiers=${e.modifiers}")
            }
            
            if (isTargetHotkey(e)) {
                println("[HOTKEY] Fn+Control hotkey pressed")
                serviceScope.launch {
                    gestureDetector?.onKeyDown()
                }
            }
        }
        
        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Release of ANY key from combination
            if (isPartOfHotkey(e)) {
                serviceScope.launch {
                    gestureDetector?.onKeyUp()
                }
            }
        }
    }
    
    private fun isTargetHotkey(event: NativeKeyEvent): Boolean {
        // Check for Fn + Control (both modifiers pressed)
        val ctrlPressed = (event.modifiers and HOTKEY_CTRL_MASK) != 0
        return (event.keyCode == HOTKEY_FN_KEYCODE || event.keyCode == HOTKEY_CTRL_KEYCODE) && ctrlPressed
    }
    
    private fun isPartOfHotkey(event: NativeKeyEvent): Boolean {
        // Any of the hotkey combination keys: Fn or Control
        return event.keyCode == HOTKEY_FN_KEYCODE ||
               event.keyCode == HOTKEY_CTRL_KEYCODE
    }
    
    
    private fun initialize(): Boolean {
        try {
            Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
                level = Level.WARNING
                useParentHandlers = false
            }
            
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(keyListener)
            isRegistered = true
            println("[HOTKEY] Global hotkey service initialized (Fn+Control)")
            return true
        } catch (ex: NativeHookException) {
            println("[HOTKEY] Failed to register native hook: ${ex.message}")
            println("[HOTKEY] Make sure Input Monitoring permission is granted in System Settings")
            return false
        }
    }
    
    private fun shutdown() {
        if (isRegistered) {
            try {
                gestureDetector?.reset()
                GlobalScreen.removeNativeKeyListener(keyListener)
                GlobalScreen.unregisterNativeHook()
                isRegistered = false
                println("[HOTKEY] Global hotkey service shutdown")
            } catch (ex: NativeHookException) {
                println("[HOTKEY] Failed to unregister native hook: ${ex.message}")
            }
        }
    }
    
    fun cleanup() {
        shutdown()
        serviceScope.cancel()
    }
}

sealed class PTTGestureEvent {
    object SingleClick : PTTGestureEvent()
    object DoubleClick : PTTGestureEvent()
    data class PTTStart(val mode: PTTMode) : PTTGestureEvent()
    data class PTTStop(val mode: PTTMode) : PTTGestureEvent()
}