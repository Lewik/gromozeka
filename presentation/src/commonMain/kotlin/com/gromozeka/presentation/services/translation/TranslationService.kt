package com.gromozeka.presentation.services.translation

import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed class TranslationOverrideResult {
    data class Success(val translation: Translation, val overriddenFields: List<String>) : TranslationOverrideResult()
    data class Failure(val error: String, val fallbackTranslation: Translation) : TranslationOverrideResult()
}

class TranslationService {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _currentTranslation = MutableStateFlow<Translation>(EnglishTranslation())
    private val _lastOverrideResult = MutableStateFlow<TranslationOverrideResult?>(null)

    val currentTranslation: StateFlow<Translation> = _currentTranslation.asStateFlow()
    val lastOverrideResult: StateFlow<TranslationOverrideResult?> = _lastOverrideResult.asStateFlow()

    fun init(settingsService: SettingsService) {
        serviceScope.launch {
            applyLanguageSettings(settingsService.settingsFlow.value)
        }
        serviceScope.launch {
            settingsService.settingsFlow.collectLatest(::applyLanguageSettings)
        }
    }

    fun refreshTranslations() = Unit

    fun exportToFile(): Boolean = false

    private fun applyLanguageSettings(settings: Settings) {
        _currentTranslation.value = Translation.builtIn[settings.userDeviceSettings.uiSettings.languageCode]
            ?: Translation.builtIn[EnglishTranslation.LANGUAGE_CODE]!!
        _lastOverrideResult.value = null
    }
}
