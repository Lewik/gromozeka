package com.gromozeka.bot.platform

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import klog.KLoggers

import com.gromozeka.bot.services.PTTEventRouter
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.services.UnifiedGestureDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.springframework.stereotype.Service
import java.util.logging.Level
import java.util.logging.Logger

@Service
class MacOSGlobalHotkeyController(
    private val settingsService: SettingsService,
    private val pttEventRouter: PTTEventRouter,
) : GlobalHotkeyController {
    private val log = KLoggers.logger(this)

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
    private var isKeyRemapped = false

    override fun initializeService() {
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
            log.info("Global hotkey service enabled via settings")
        } else if (!enabled && isRegistered) {
            shutdown()
            log.info("Global hotkey service disabled via settings")
        }
    }

    private val keyListener = object : NativeKeyListener {
        override fun nativeKeyPressed(e: NativeKeyEvent) {
            // Check for F13 key (which is remapped from § via hidutil)
            if (e.keyCode == HOTKEY_F13_KEYCODE) {
                isPTTActive = true
                serviceScope.launch {
                    onHotkeyDown()
                }
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Check for F13 key (which is remapped from § via hidutil) release
            if (e.keyCode == HOTKEY_F13_KEYCODE && isPTTActive) {
                isPTTActive = false
                serviceScope.launch {
                    onHotkeyUp()
                }
            }
        }
    }

    private fun setupKeyRemapping() {
        if (isKeyRemapped) {
            log.debug("Key remapping already active, skipping")
            return
        }
        
        try {
            // Remap § key to F13 to prevent character typing
            val command = listOf(
                "hidutil", "property", "--set",
                """{"UserKeyMapping":[{"HIDKeyboardModifierMappingSrc":0x700000064,"HIDKeyboardModifierMappingDst":0x700000068}]}"""
            )
            ProcessBuilder(command).start().waitFor()
            isKeyRemapped = true
            log.info("Remapped § key to F13 via hidutil")
        } catch (e: Exception) {
            log.warn("Could not remap § key: ${e.message}")
        }
    }

    private fun clearKeyRemapping() {
        if (!isKeyRemapped) {
            return
        }
        
        try {
            // Clear all key remappings
            val command = listOf("hidutil", "property", "--set", """{"UserKeyMapping":[]}""")
            ProcessBuilder(command).start().waitFor()
            isKeyRemapped = false
            log.info("Cleared key remapping via hidutil")
        } catch (e: Exception) {
            log.warn("Could not clear key remapping: ${e.message}")
        }
    }

    private fun initialize(): Boolean {
        // Prevent double initialization
        if (isRegistered) {
            log.debug("Global hotkey service already initialized, skipping")
            return true
        }
        
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
            log.info("Global hotkey service initialized (Section § key via rawCode)")
            return true
        } catch (ex: NativeHookException) {
            log.error("Failed to register native hook: ${ex.message}")
            log.error("Make sure Input Monitoring permission is granted in System Settings")
            
            // Clear remapping if registration failed
            clearKeyRemapping()
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
                log.info("Global hotkey service shutdown")
            } catch (ex: NativeHookException) {
                log.error("Failed to unregister native hook: ${ex.message}")
            }
        }
    }

    private fun resetGestureState() {
        gestureDetector.resetGestureState()
        isPTTActive = false
    }

    override fun cleanup() {
        shutdown()
        serviceScope.cancel()
    }
}

