package com.gromozeka.bot.services

import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File

@Service
class SettingsService {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    val mode: AppMode = determineMode()

    val gromozekaHome: File = determineGromozekaHome()

    val claudeHome: File = System.getProperty("claude.home")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".claude")

    private val settingsFile: File = File(gromozekaHome, "settings.json")

    private val _settingsFlow = MutableStateFlow<Settings?>(null)
    val settingsFlow: StateFlow<Settings?> = _settingsFlow.asStateFlow()

    private var initialized = false

    /**
     * Initialize the service - must be called explicitly after Spring context creation
     */
    fun initialize() {
        if (initialized) {
            throw IllegalStateException("SettingsService already initialized")
        }

        if (gromozekaHome.mkdirs()) {
            println("[SettingsService] Created gromozeka home directory: ${gromozekaHome.absolutePath}")
        }

        // Load settings
        _settingsFlow.value = loadSettings()

        initialized = true
        println("[SettingsService] Initialized with mode: ${mode.name}")
        println("[SettingsService] Gromozeka home: ${gromozekaHome.absolutePath}")
    }

    private fun determineMode(): AppMode {
        val modeEnv = System.getenv("GROMOZEKA_MODE")
        println("[SettingsService] Environment variable GROMOZEKA_MODE = $modeEnv")

        return when (modeEnv?.lowercase()) {
            "dev", "development" -> AppMode.DEV
            "prod", "production" -> AppMode.PROD
            null -> AppMode.PROD
            else -> throw IllegalArgumentException("GROMOZEKA_MODE value '$modeEnv' not supported")
        }
    }

    private fun determineGromozekaHome(): File {
        val customPath = System.getenv("GROMOZEKA_HOME")

        return when {
            customPath != null -> {
                val customDir = File(customPath)
                println("[SettingsService] Using custom gromozeka home from system property: ${customDir.absolutePath}")
                customDir
            }

            mode == AppMode.DEV -> {
                // Use project directory for dev mode - more reliable than resources
                val projectDir = File(System.getProperty("user.dir"))
                val devDataDir = File(projectDir, "bot/dev-data/.gromozeka")
                println("[SettingsService] DEV mode - using project dev-data: ${devDataDir.absolutePath}")
                devDataDir
            }

            else -> {
                val prodDir = File(System.getProperty("user.home"), ".gromozeka")
                println("[SettingsService] PROD mode - using user home directory: ${prodDir.absolutePath}")
                prodDir
            }
        }
    }

    private fun loadSettings(): Settings = if (settingsFile.exists()) {
        try {
            json.decodeFromString<Settings>(settingsFile.readText())
        } catch (e: Exception) {
            println("[SettingsService] Failed to load settings: ${e.message}")
            createDefaultSettings()
        }
    } else {
        println("[SettingsService] Settings file not found, creating defaults")
        createDefaultSettings()
    }

    private fun createDefaultSettings(): Settings {
        val defaults = Settings(
            enableTts = true,
            enableStt = true,
            autoSend = true
        )

        settingsFile.writeText(json.encodeToString(defaults))
        println("[SettingsService] Created default settings file: ${settingsFile.absolutePath}")

        return defaults
    }

    fun saveSettings(settings: Settings) {
        requireInitialized()
        // Always save to file in both modes
        settingsFile.writeText(json.encodeToString(settings))
        _settingsFlow.value = settings
        println("[SettingsService] Settings saved to: ${settingsFile.absolutePath}")
    }

    fun saveSettings(block: Settings.() -> Settings) {
        val updated = settings.block()
        saveSettings(updated)
    }

    fun reloadSettings() {
        requireInitialized()
        _settingsFlow.value = loadSettings()
        println("[SettingsService] Settings reloaded from file")
    }

    // Utility methods
    val settings: Settings
        get() = _settingsFlow.value
            ?: throw IllegalStateException("SettingsService not initialized. Call initialize() first.")

    // Utility methods for common paths
    fun getLogsDir(): File {
        requireInitialized()
        return File(gromozekaHome, "logs").apply { mkdirs() }
    }

    fun getCacheDir(): File {
        requireInitialized()
        return File(gromozekaHome, "cache").apply { mkdirs() }
    }

    fun getSessionsDir(): File {
        requireInitialized()
        return File(gromozekaHome, "sessions").apply { mkdirs() }
    }

    // Claude specific paths
    fun getClaudeProjectsDir(): File {
        requireInitialized()
        return File(claudeHome, "projects")
    }

    fun getClaudeConfigDir(): File {
        requireInitialized()
        return File(claudeHome, "config")
    }

    private fun requireInitialized() {
        if (!initialized) {
            throw IllegalStateException("SettingsService not initialized. Call initialize() first.")
        }
    }
}