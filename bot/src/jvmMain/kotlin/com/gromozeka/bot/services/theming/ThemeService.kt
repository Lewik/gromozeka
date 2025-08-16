package com.gromozeka.bot.services.theming

import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.services.theming.data.*
import com.gromozeka.bot.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

sealed class ThemeOverrideResult {
    data class Success(val theme: Theme, val overriddenFields: List<String>) : ThemeOverrideResult()
    data class Failure(val error: String, val fallbackTheme: Theme) : ThemeOverrideResult()
}

class ThemeService {
    private lateinit var settingsService: SettingsService

    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Mutex for thread-safe operations
    private val themeMutex = Mutex()

    // Reactive state for UI
    private val _currentTheme = MutableStateFlow<Theme>(DarkTheme())
    private val _lastOverrideResult = MutableStateFlow<ThemeOverrideResult?>(null)

    val currentTheme: StateFlow<Theme> = _currentTheme.asStateFlow()
    val lastOverrideResult: StateFlow<ThemeOverrideResult?> = _lastOverrideResult.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        allowStructuredMapKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun init(settingsService: SettingsService) {
        this.settingsService = settingsService

        // Apply current settings immediately on startup
        serviceScope.launch {
            val currentSettings = settingsService.settingsFlow.value
            applyThemeSettings(currentSettings)
        }

        // Subscribe to settings changes for reactive theme switching
        serviceScope.launch {
            settingsService.settingsFlow.collectLatest { settings ->
                applyThemeSettings(settings)
            }
        }
    }

    // === Theme Management ===

    /**
     * Central method for applying themes - takes theme ID and optional override file
     * This method handles all theme application logic in one place
     */
    private suspend fun applyTheme(themeId: String, overrideFile: File? = null) = themeMutex.withLock {
        if (overrideFile?.exists() == true) {
            setThemeOverride(themeId, overrideFile)
        } else {
            _currentTheme.value = Theme.builtIn[themeId]
                ?: Theme.builtIn[DarkTheme.THEME_ID]!!
            _lastOverrideResult.value = null
            println("[ThemeService] Switched to builtin theme: ${_currentTheme.value.themeName}")
        }
    }

    private suspend fun applyThemeSettings(settings: Settings) {
        val overrideFile = File(File(settingsService.gromozekaHome, "themes"), "override.json")
        applyTheme(settings.currentThemeId, overrideFile)
    }

    fun refreshThemes() {
        // Method for manual refresh from UI - launches in service scope
        serviceScope.launch {
            val currentSettings = settingsService.settingsFlow.value
            applyThemeSettings(currentSettings)
        }
    }

    fun setThemeOverride(themeId: String, overrideFile: File): ThemeOverrideResult {
        val baseTheme = Theme.builtIn[themeId] ?: Theme.builtIn[DarkTheme.THEME_ID]!!

        if (!overrideFile.exists()) {
            val result = ThemeOverrideResult.Failure(
                "Override file not found: ${overrideFile.absolutePath}",
                baseTheme
            )
            _lastOverrideResult.value = result
            _currentTheme.value = baseTheme
            return result
        }

        return try {
            val jsonContent = overrideFile.readText()

            // Простая десериализация в нужный data class
            // kotlinx.serialization автоматически применит defaults для пропущенных полей
            val overriddenTheme = when (themeId) {
                DarkTheme.THEME_ID -> json.decodeFromString<DarkTheme>(jsonContent)
                LightTheme.THEME_ID -> json.decodeFromString<LightTheme>(jsonContent)
                GromozekaTheme.THEME_ID -> json.decodeFromString<GromozekaTheme>(jsonContent)
                else -> json.decodeFromString<DarkTheme>(jsonContent) // fallback
            }

            val result = ThemeOverrideResult.Success(overriddenTheme, listOf("Custom JSON override"))
            _lastOverrideResult.value = result
            _currentTheme.value = overriddenTheme

            println("[ThemeService] Applied theme override successfully")
            result
        } catch (e: SerializationException) {
            val result = ThemeOverrideResult.Failure("JSON parsing error: ${e.message}", baseTheme)
            _lastOverrideResult.value = result
            _currentTheme.value = baseTheme
            println("[ThemeService] Theme override failed: ${e.message}")
            result
        } catch (e: Exception) {
            val result = ThemeOverrideResult.Failure("File error: ${e.message}", baseTheme)
            _lastOverrideResult.value = result
            _currentTheme.value = baseTheme
            println("[ThemeService] Theme override failed: ${e.message}")
            result
        }
    }

    // === Export/Import functionality ===

    fun exportToFile(): Boolean {
        val overrideFile = File(File(settingsService.gromozekaHome, "themes"), "override.json")
        val currentThemeId = _currentTheme.value.themeId

        // Export builtin theme by theme ID
        val builtinTheme = Theme.builtIn[currentThemeId] ?: Theme.builtIn[DarkTheme.THEME_ID]!!
        val jsonContent = json.encodeToString<Theme>(builtinTheme)

        return try {
            overrideFile.parentFile?.mkdirs()
            overrideFile.writeText(jsonContent)
            println("[ThemeService] Successfully exported builtin theme to: ${overrideFile.absolutePath}")
            true
        } catch (e: Exception) {
            println("[ThemeService] Failed to save theme: ${e.message}")
            false
        }
    }
}