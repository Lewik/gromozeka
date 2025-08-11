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
        // Global hotkey: Control only
        private const val HOTKEY_CTRL_KEYCODE = NativeKeyEvent.VC_CONTROL
        private const val HOTKEY_CTRL_MASK = NativeKeyEvent.CTRL_L_MASK
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gestureDetector = UnifiedGestureDetector(pttEventRouter, serviceScope)
    
    private var isRegistered = false
    private var isPTTActive = false
    private var ignoreUntilNextCtrlDown = false
    
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
            // If ignoring all events until next clean Ctrl-Down
            if (ignoreUntilNextCtrlDown) {
                if (e.keyCode == HOTKEY_CTRL_KEYCODE) {
                    // New Ctrl press - reset ignore flag and check if clean
                    println("[HOTKEY] New Ctrl-Down detected, resetting ignore flag")
                    ignoreUntilNextCtrlDown = false
                    
                    // Check if this is a clean Ctrl press (only Ctrl modifier)
                    if (isCleanCtrlPress(e)) {
                        println("[HOTKEY] Clean Ctrl press - starting PTT")
                        serviceScope.launch {
                            onHotkeyDown()
                        }
                    } else {
                        println("[HOTKEY] Ctrl pressed with other modifiers - ignoring until next clean press")
                        ignoreUntilNextCtrlDown = true
                    }
                }
                // Ignore ALL other events (including other keys and Ctrl-Up)
                return
            }
            
            // Debug logging for ctrl key detection
            if (e.keyCode == HOTKEY_CTRL_KEYCODE) {
                println("[HOTKEY] Control key detected: keyCode=${e.keyCode}, modifiers=${e.modifiers}")
            }
            
            if (isTargetHotkey(e)) {
                // Check if this is a clean Ctrl press without conflicting keys
                if (isCleanCtrlPress(e)) {
                    println("[HOTKEY] Clean Control hotkey pressed")
                    serviceScope.launch {
                        onHotkeyDown()
                    }
                } else {
                    println("[HOTKEY] Ctrl pressed with conflicting modifiers - ignoring until next clean press")
                    ignoreUntilNextCtrlDown = true
                }
            } else if (isPTTActive && !isPartOfHotkey(e)) {
                // PTT is active and user pressed a non-hotkey key -> conflict
                println("[HOTKEY] Key conflict detected during PTT (keyCode=${e.keyCode}) - ignoring until next clean Ctrl")
                isPTTActive = false  // Reset PTT state immediately on conflict
                ignoreUntilNextCtrlDown = true
                serviceScope.launch {
                    pttEventRouter.handlePTTEvent(PTTEvent.MODIFIER_CONFLICT)
                }
            }
        }
        
        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // If ignoring events, ignore ALL releases including Ctrl-Up
            if (ignoreUntilNextCtrlDown) {
                return
            }
            
            // Release of ANY key from combination
            if (isPartOfHotkey(e)) {
                serviceScope.launch {
                    onHotkeyUp()
                }
            }
        }
    }
    
    private fun isTargetHotkey(event: NativeKeyEvent): Boolean {
        // Check for Control only (no other modifiers like Shift, Alt, etc.)
        return event.keyCode == HOTKEY_CTRL_KEYCODE && 
               (event.modifiers and HOTKEY_CTRL_MASK) != 0
    }
    
    private fun isPartOfHotkey(event: NativeKeyEvent): Boolean {
        // Only Control key is part of our hotkey
        return event.keyCode == HOTKEY_CTRL_KEYCODE
    }
    
    private fun isCleanCtrlPress(event: NativeKeyEvent): Boolean {
        // Clean Control press: only Ctrl modifier, no Shift/Alt/etc.
        val onlyCtrlModifier = (event.modifiers and HOTKEY_CTRL_MASK) != 0 && 
                               (event.modifiers and HOTKEY_CTRL_MASK.inv()) == 0
        return event.keyCode == HOTKEY_CTRL_KEYCODE && onlyCtrlModifier
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
            println("[HOTKEY] Global hotkey service initialized (Control only)")
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
        ignoreUntilNextCtrlDown = false
        isPTTActive = false
    }
    
    fun cleanup() {
        shutdown()
        serviceScope.cancel()
    }
}

