package com.gromozeka.presentation.services.theming

import com.gromozeka.presentation.services.SettingsService
import com.gromozeka.presentation.services.theming.data.*
import com.gromozeka.presentation.model.Settings
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
import kotlinx.serialization.json.Json
import java.io.File

sealed class ThemeOverrideResult {
    data class Success(val theme: Theme, val overriddenFields: List<String>) : ThemeOverrideResult()
    data class Failure(val error: String, val fallbackTheme: Theme) : ThemeOverrideResult()
}

data class ThemeInfo(
    val themeId: String,
    val themeName: String,
    val isBuiltIn: Boolean,
    val isValid: Boolean,
    val errorMessage: String? = null,
    val file: File? = null,
)

class ThemeService {
    private lateinit var settingsService: SettingsService

    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Mutex for thread-safe operations
    private val themeMutex = Mutex()

    // Reactive state for UI
    private val _currentTheme = MutableStateFlow<Theme>(DarkTheme())
    private val _lastOverrideResult = MutableStateFlow<ThemeOverrideResult?>(null)
    private val _availableThemes = MutableStateFlow<Map<String, ThemeInfo>>(emptyMap())

    val currentTheme: StateFlow<Theme> = _currentTheme.asStateFlow()
    val lastOverrideResult: StateFlow<ThemeOverrideResult?> = _lastOverrideResult.asStateFlow()
    val availableThemes: StateFlow<Map<String, ThemeInfo>> = _availableThemes.asStateFlow()

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

        // Scan for available themes first
        serviceScope.launch {
            scanAvailableThemes()
        }

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
            // Try to load as built-in theme first
            val builtInTheme = Theme.builtIn[themeId]
            if (builtInTheme != null) {
                _currentTheme.value = builtInTheme
                _lastOverrideResult.value = null
                println("[ThemeService] Switched to builtin theme: ${_currentTheme.value.themeName}")
            } else {
                // Try to load as AI-generated theme
                val themeInfo = _availableThemes.value[themeId]
                if (themeInfo != null && themeInfo.isValid && themeInfo.file != null) {
                    loadAiThemeAndApply(themeInfo.file)
                } else {
                    // Fallback to dark theme
                    _currentTheme.value = Theme.builtIn[DarkTheme.THEME_ID]!!
                    _lastOverrideResult.value = null
                    println("[ThemeService] Theme '$themeId' not found, falling back to dark theme")
                }
            }
        }
    }

    private suspend fun loadAiThemeAndApply(themeFile: File) {
        try {
            val jsonContent = themeFile.readText()
            val theme = json.decodeFromString<AIGeneratedTheme>(jsonContent)
            _currentTheme.value = theme
            _lastOverrideResult.value = null
            println("[ThemeService] Switched to AI-generated theme: ${theme.themeName}")
        } catch (e: Exception) {
            _currentTheme.value = Theme.builtIn[DarkTheme.THEME_ID]!!
            _lastOverrideResult.value = ThemeOverrideResult.Failure(
                "Failed to load AI theme: ${e.message}",
                _currentTheme.value
            )
            println("[ThemeService] Failed to load AI theme: ${e.message}")
        }
    }

    private suspend fun applyThemeSettings(settings: Settings) {
        val overrideFile = File(File(settingsService.gromozekaHome, "themes"), "override.json")
        val shouldUseOverride = settings.themeOverrideEnabled && overrideFile.exists()
        applyTheme(settings.currentThemeId, if (shouldUseOverride) overrideFile else null)
    }

    fun refreshThemes() {
        // Method for manual refresh from UI - launches in service scope
        serviceScope.launch {
            scanAvailableThemes()
            val currentSettings = settingsService.settingsFlow.value
            applyThemeSettings(currentSettings)
        }
    }

    // === Theme Scanning ===

    private suspend fun scanAvailableThemes() = themeMutex.withLock {
        val allThemes = mutableMapOf<String, ThemeInfo>()

        // Add built-in themes
        Theme.builtIn.forEach { (themeId, theme) ->
            allThemes[themeId] = ThemeInfo(
                themeId = themeId,
                themeName = theme.themeName,
                isBuiltIn = true,
                isValid = true
            )
        }

        // Scan AI-generated themes from themes directory
        val themesDir = File(settingsService.gromozekaHome, "themes")
        if (themesDir.exists() && themesDir.isDirectory) {
            themesDir.listFiles { file ->
                file.isFile && file.extension == "json" && file.name.startsWith("ai_generated_")
            }?.forEach { themeFile ->
                val aiThemeInfo = loadAiTheme(themeFile)
                allThemes[aiThemeInfo.themeId] = aiThemeInfo
            }
        }

        _availableThemes.value = allThemes
        println("[ThemeService] Scanned ${allThemes.size} available themes (${allThemes.count { it.value.isValid }} valid)")
    }

    private fun loadAiTheme(themeFile: File): ThemeInfo {
        return try {
            val jsonContent = themeFile.readText()
            val theme = json.decodeFromString<AIGeneratedTheme>(jsonContent)

            ThemeInfo(
                themeId = theme.themeId,
                themeName = theme.themeName,
                isBuiltIn = false,
                isValid = true,
                file = themeFile
            )
        } catch (e: SerializationException) {
            ThemeInfo(
                themeId = themeFile.nameWithoutExtension,
                themeName = "${themeFile.nameWithoutExtension} (Invalid)",
                isBuiltIn = false,
                isValid = false,
                errorMessage = "Failed to deserialize: ${e.message}", // Will be localized in UI
                file = themeFile
            )
        } catch (e: Exception) {
            ThemeInfo(
                themeId = themeFile.nameWithoutExtension,
                themeName = "${themeFile.nameWithoutExtension} (Error)",
                isBuiltIn = false,
                isValid = false,
                errorMessage = "File error: ${e.message}", // Will be localized in UI
                file = themeFile
            )
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

    fun removeOverrideFile(): Boolean {
        val overrideFile = File(File(settingsService.gromozekaHome, "themes"), "override.json")
        return try {
            if (overrideFile.exists()) {
                overrideFile.delete()
                println("[ThemeService] Successfully removed override file")
                // Refresh theme to apply removal
                serviceScope.launch {
                    val currentSettings = settingsService.settingsFlow.value
                    applyThemeSettings(currentSettings)
                }
            }
            true
        } catch (e: Exception) {
            println("[ThemeService] Failed to remove override file: ${e.message}")
            false
        }
    }
}