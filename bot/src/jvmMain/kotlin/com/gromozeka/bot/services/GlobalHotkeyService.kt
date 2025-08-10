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
    private val pttEventRouter: PTTEventRouter
) {
    
    companion object {
        // Global hotkey: Fn + Control
        private const val HOTKEY_FN_KEYCODE = 63  // macOS fn key code (0x3F)
        private const val HOTKEY_CTRL_KEYCODE = NativeKeyEvent.VC_CONTROL
        private const val HOTKEY_CTRL_MASK = NativeKeyEvent.CTRL_L_MASK
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gestureDetector = UnifiedGestureDetector(pttEventRouter, serviceScope)
    
    private var isRegistered = false
    private var isPTTActive = false
    
    fun initializeService() {
        startListeningToSettings()
    }
    
    private suspend fun onHotkeyDown() {
        isPTTActive = true
        gestureDetector.onGestureDown()
    }
    
    private suspend fun onHotkeyUp() {
        isPTTActive = false
        gestureDetector.onGestureUp()
    }
    
    private fun startListeningToSettings() {
        // Subscribe to settings changes
        serviceScope.launch {
            settingsService.settingsFlow.collectLatest { settings ->
                settings?.let { 
                    // Global hotkey should work only if both STT and global hotkey are enabled
                    val shouldEnable = it.enableStt && it.globalPttHotkeyEnabled
                    handleSettingsUpdate(shouldEnable)
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
                    onHotkeyDown()
                }
            } else if (isPTTActive && !isPartOfHotkey(e)) {
                // PTT is active and user pressed a non-hotkey key -> conflict
                println("[HOTKEY] Modifier conflict detected during PTT (keyCode=${e.keyCode})")
                gestureDetector.setDisabledDueToConflict()
                serviceScope.launch {
                    pttEventRouter.handlePTTEvent(PTTEvent.MODIFIER_CONFLICT)
                }
            }
        }
        
        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Release of ANY key from combination
            if (isPartOfHotkey(e)) {
                serviceScope.launch {
                    onHotkeyUp()
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
                resetGestureState()
                GlobalScreen.removeNativeKeyListener(keyListener)
                GlobalScreen.unregisterNativeHook()
                isRegistered = false
                println("[HOTKEY] Global hotkey service shutdown")
            } catch (ex: NativeHookException) {
                println("[HOTKEY] Failed to unregister native hook: ${ex.message}")
            }
        }
    }
    
    private fun resetGestureState() {
        gestureDetector.resetGestureState()
    }
    
    fun cleanup() {
        shutdown()
        serviceScope.cancel()
    }
}

