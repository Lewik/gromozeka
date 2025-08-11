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
        // Global hotkey: Section/Paragraph key (§) on MacBook - uses rawCode since keyCode is 0
        private const val HOTKEY_SECTION_RAWCODE = 10  // rawCode for § key on macOS
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
            // Check for Section key (§) using rawCode since keyCode is 0 for this key
            if (e.rawCode == HOTKEY_SECTION_RAWCODE) {
                println("[HOTKEY] Section key (§) pressed - starting PTT")
                // Note: § character typing is disabled via hidutil on macOS
                isPTTActive = true
                serviceScope.launch {
                    onHotkeyDown()
                }
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Check for Section key (§) release using rawCode
            if (e.rawCode == HOTKEY_SECTION_RAWCODE && isPTTActive) {
                println("[HOTKEY] Section key (§) released - stopping PTT")
                // Note: § character typing is disabled via hidutil on macOS
                isPTTActive = false
                serviceScope.launch {
                    onHotkeyUp()
                }
            }
        }
    }

    private fun initialize(): Boolean {
        try {
            // Disable § key from typing on macOS using hidutil
            disableSectionKeyTyping()
            
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

    private fun disableSectionKeyTyping() {
        try {
            // Disable § key from producing character output on macOS
            // 0x700000064 = § key, 0x700000000 = no output
            val command = listOf(
                "hidutil", "property", "--set",
                """{"UserKeyMapping":[{"HIDKeyboardModifierMappingSrc":0x700000064,"HIDKeyboardModifierMappingDst":0x700000000}]}"""
            )
            ProcessBuilder(command).start().waitFor()
            println("[HOTKEY] Disabled § key typing via hidutil")
        } catch (e: Exception) {
            println("[HOTKEY] Warning: Could not disable § key typing: ${e.message}")
        }
    }
    
    private fun restoreSectionKeyTyping() {
        try {
            // Restore default key mapping
            val command = listOf("hidutil", "property", "--set", """{"UserKeyMapping":[]}""")
            ProcessBuilder(command).start().waitFor()
            println("[HOTKEY] Restored § key typing via hidutil")
        } catch (e: Exception) {
            println("[HOTKEY] Warning: Could not restore § key typing: ${e.message}")
        }
    }

    private fun shutdown() {
        if (isRegistered) {
            try {
                resetGestureState()
                GlobalScreen.removeNativeKeyListener(keyListener)
                GlobalScreen.unregisterNativeHook()
                isRegistered = false
                restoreSectionKeyTyping() // Restore key mapping on shutdown
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
        restoreSectionKeyTyping() // Ensure key mapping is restored even on forced cleanup
    }
}

