package com.gromozeka.presentation.services.theming

import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.theming.data.DarkTheme
import com.gromozeka.presentation.services.theming.data.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
)

class ThemeService {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _currentTheme = MutableStateFlow<Theme>(DarkTheme())
    private val _lastOverrideResult = MutableStateFlow<ThemeOverrideResult?>(null)
    private val _availableThemes = MutableStateFlow(Theme.builtIn.toThemeInfoMap())

    val currentTheme: StateFlow<Theme> = _currentTheme.asStateFlow()
    val lastOverrideResult: StateFlow<ThemeOverrideResult?> = _lastOverrideResult.asStateFlow()
    val availableThemes: StateFlow<Map<String, ThemeInfo>> = _availableThemes.asStateFlow()

    fun init(settingsService: SettingsService) {
        serviceScope.launch {
            applyThemeSettings(settingsService.settingsFlow.value)
        }
        serviceScope.launch {
            settingsService.settingsFlow.collectLatest(::applyThemeSettings)
        }
    }

    fun refreshThemes() {
        _availableThemes.value = Theme.builtIn.toThemeInfoMap()
    }

    fun exportToFile(): Boolean = false

    fun removeOverrideFile(): Boolean = true

    private fun applyThemeSettings(settings: Settings) {
        _currentTheme.value = Theme.builtIn[settings.currentThemeId]
            ?: Theme.builtIn[DarkTheme.THEME_ID]!!
        _lastOverrideResult.value = null
    }
}

private fun Map<String, Theme>.toThemeInfoMap(): Map<String, ThemeInfo> =
    mapValues { (themeId, theme) ->
        ThemeInfo(
            themeId = themeId,
            themeName = theme.themeName,
            isBuiltIn = true,
            isValid = true,
        )
    }
