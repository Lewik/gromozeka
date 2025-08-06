package com.gromozeka.bot.services

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.springframework.stereotype.Service
import java.util.logging.Level
import java.util.logging.Logger

@Service
class GlobalHotkeyService {
    
    companion object {
        // Хардкод hotkey: Left Shift + ` (backtick)
        private const val HOTKEY_LEFT_SHIFT_KEYCODE = NativeKeyEvent.VC_SHIFT
        private const val HOTKEY_BACKTICK_KEYCODE = 41
        private const val HOTKEY_LEFT_SHIFT_MASK = NativeKeyEvent.SHIFT_L_MASK
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gestureDetector: PTTGestureDetector? = null
    
    private val _gestureEvents = MutableSharedFlow<PTTGestureEvent>()
    val gestureEvents: SharedFlow<PTTGestureEvent> = _gestureEvents
    
    private var isRegistered = false
    
    fun setGestureHandler(handler: PTTGestureHandler) {
        gestureDetector = PTTGestureDetector(handler, serviceScope)
    }
    
    private val keyListener = object : NativeKeyListener {
        override fun nativeKeyPressed(e: NativeKeyEvent) {
            if (isTargetHotkey(e)) {
                serviceScope.launch {
                    gestureDetector?.onKeyDown()
                }
            }
        }
        
        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Отпускание ЛЮБОЙ клавиши из комбинации
            if (isPartOfHotkey(e)) {
                serviceScope.launch {
                    gestureDetector?.onKeyUp()
                }
            }
        }
    }
    
    private fun isTargetHotkey(event: NativeKeyEvent): Boolean {
        val leftShiftPressed = (event.modifiers and HOTKEY_LEFT_SHIFT_MASK) != 0
        return leftShiftPressed && event.keyCode == HOTKEY_BACKTICK_KEYCODE
    }
    
    private fun isPartOfHotkey(event: NativeKeyEvent): Boolean {
        // Любая из клавиш hotkey комбинации: Left Shift или backtick
        return event.keyCode == HOTKEY_LEFT_SHIFT_KEYCODE || event.keyCode == HOTKEY_BACKTICK_KEYCODE
    }
    
    
    fun initialize(): Boolean {
        try {
            Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
                level = Level.WARNING
                useParentHandlers = false
            }
            
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(keyListener)
            isRegistered = true
            println("[HOTKEY] Global hotkey service initialized (Left Shift + `)")
            return true
        } catch (ex: NativeHookException) {
            println("[HOTKEY] Failed to register native hook: ${ex.message}")
            println("[HOTKEY] Make sure Input Monitoring permission is granted in System Settings")
            return false
        }
    }
    
    fun shutdown() {
        if (isRegistered) {
            try {
                gestureDetector?.reset()
                GlobalScreen.removeNativeKeyListener(keyListener)
                GlobalScreen.unregisterNativeHook()
                isRegistered = false
                serviceScope.cancel()
                println("[HOTKEY] Global hotkey service shutdown")
            } catch (ex: NativeHookException) {
                println("[HOTKEY] Failed to unregister native hook: ${ex.message}")
            }
        }
    }
}

sealed class PTTGestureEvent {
    object SingleClick : PTTGestureEvent()
    object DoubleClick : PTTGestureEvent()
    data class PTTStart(val mode: PTTMode) : PTTGestureEvent()
    data class PTTStop(val mode: PTTMode) : PTTGestureEvent()
}