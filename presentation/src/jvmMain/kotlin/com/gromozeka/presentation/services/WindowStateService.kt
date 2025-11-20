package com.gromozeka.presentation.services

import com.gromozeka.presentation.ui.UiWindowState
import klog.KLoggers

import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File

@Service
class WindowStateService(
    private val settingsService: SettingsService,
) {
    private val log = KLoggers.logger(this)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val windowStateFile: File by lazy {
        File(settingsService.gromozekaHome, "window-state.json")
    }

    fun loadWindowState(): UiWindowState {
        return if (windowStateFile.exists()) {
            try {
                json.decodeFromString<UiWindowState>(windowStateFile.readText())
            } catch (e: Exception) {
                log.warn("Failed to load window state: ${e.message}")
                UiWindowState() // Return default
            }
        } else {
            log.debug("Window state file not found, using defaults")
            UiWindowState() // Default state
        }
    }

    fun saveWindowState(windowState: UiWindowState) {
        try {
            windowStateFile.writeText(json.encodeToString(windowState))
            log.info("Window state saved to: ${windowStateFile.absolutePath}")
        } catch (e: Exception) {
            log.warn("Failed to save window state: ${e.message}")
        }
    }
}