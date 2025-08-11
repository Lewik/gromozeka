package com.gromozeka.bot.services

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.springframework.stereotype.Service
import java.util.logging.Level
import java.util.logging.Logger

@Service
class GlobalHotkeyService(
    private val settingsService: SettingsService,
    private val pttEventRouter: PTTEventRouter,
) {

    companion object {
        // Global hotkey: Section/Paragraph key (§) remapped to F13 via hidutil
        // Original § rawCode = 10, but we remap it to F13 
        // JNativeHook reports keyCode=91, rawCode=105 for remapped F13
        private const val HOTKEY_F13_KEYCODE = 91  // Actual keyCode JNativeHook sees for F13
        private const val HOTKEY_F13_RAWCODE = 105  // Actual rawCode JNativeHook sees for F13
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
                // Global hotkey should work only if both STT and global hotkey are enabled
                val shouldEnable = settings.enableStt && settings.globalPttHotkeyEnabled
                handleSettingsUpdate(shouldEnable)
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
            // Check for F13 key (which is remapped from § via hidutil)
            if (e.keyCode == HOTKEY_F13_KEYCODE) {
                println("[HOTKEY] PTT key pressed")
                isPTTActive = true
                serviceScope.launch {
                    onHotkeyDown()
                }
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Check for F13 key (which is remapped from § via hidutil) release
            if (e.keyCode == HOTKEY_F13_KEYCODE && isPTTActive) {
                println("[HOTKEY] PTT key released")
                isPTTActive = false
                serviceScope.launch {
                    onHotkeyUp()
                }
            }
        }
    }

    private fun setupKeyRemapping() {
        try {
            // Remap § key to F13 to prevent character typing
            val command = listOf(
                "hidutil", "property", "--set",
                """{"UserKeyMapping":[{"HIDKeyboardModifierMappingSrc":0x700000064,"HIDKeyboardModifierMappingDst":0x700000068}]}"""
            )
            ProcessBuilder(command).start().waitFor()
            println("[HOTKEY] Remapped § key to F13 via hidutil")
        } catch (e: Exception) {
            println("[HOTKEY] Warning: Could not remap § key: ${e.message}")
        }
    }
    
    private fun clearKeyRemapping() {
        try {
            // Clear all key remappings
            val command = listOf("hidutil", "property", "--set", """{"UserKeyMapping":[]}""")
            ProcessBuilder(command).start().waitFor()
            println("[HOTKEY] Cleared key remapping via hidutil")
        } catch (e: Exception) {
            println("[HOTKEY] Warning: Could not clear key remapping: ${e.message}")
        }
    }
    
    private fun initialize(): Boolean {
        try {
            // § key is remapped to F13 via hidutil to prevent typing
            setupKeyRemapping()
            
            Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
                level = Level.WARNING
                useParentHandlers = false
            }

            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(keyListener)
            isRegistered = true
            println("[HOTKEY] Global hotkey service initialized (Section § key via rawCode)")
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
                clearKeyRemapping()
                println("[HOTKEY] Global hotkey service shutdown")
            } catch (ex: NativeHookException) {
                println("[HOTKEY] Failed to unregister native hook: ${ex.message}")
            }
        }
    }

    private fun resetGestureState() {
        gestureDetector.resetGestureState()
        isPTTActive = false
    }

    fun cleanup() {
        shutdown()
        serviceScope.cancel()
    }
}

