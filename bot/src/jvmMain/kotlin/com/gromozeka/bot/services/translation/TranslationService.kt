package com.gromozeka.bot.services.translation

import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.services.translation.data.EnglishTranslation
import klog.KLoggers
import com.gromozeka.bot.services.translation.data.HebrewTranslation
import com.gromozeka.bot.services.translation.data.RussianTranslation
import com.gromozeka.bot.services.translation.data.Translation
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
import kotlinx.serialization.json.Json
import java.io.File

sealed class TranslationOverrideResult {
    data class Success(val translation: Translation, val overriddenFields: List<String>) : TranslationOverrideResult()
    data class Failure(val error: String, val fallbackTranslation: Translation) : TranslationOverrideResult()
}

class TranslationService {
    private lateinit var settingsService: SettingsService
    private val log = KLoggers.logger(this)

    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Mutex for thread-safe operations
    private val translationMutex = Mutex()

    // Reactive state for UI
    private val _currentTranslation = MutableStateFlow<Translation>(EnglishTranslation())
    private val _lastOverrideResult = MutableStateFlow<TranslationOverrideResult?>(null)

    val currentTranslation: StateFlow<Translation> = _currentTranslation.asStateFlow()
    val lastOverrideResult: StateFlow<TranslationOverrideResult?> = _lastOverrideResult.asStateFlow()

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
            applyLanguageSettings(currentSettings)
        }

        // Subscribe to settings changes for reactive language switching
        serviceScope.launch {
            settingsService.settingsFlow.collectLatest { settings ->
                applyLanguageSettings(settings)
            }
        }
    }

    // === Translation Management ===

    /**
     * Central method for applying translations - takes language code and optional override file
     * This method handles all translation application logic in one place
     */
    private suspend fun applyTranslation(languageCode: String, overrideFile: File? = null) = translationMutex.withLock {
        if (overrideFile?.exists() == true) {
            setTranslationOverride(languageCode, overrideFile)
        } else {
            _currentTranslation.value = Translation.builtIn[languageCode]
                ?: Translation.builtIn[EnglishTranslation.LANGUAGE_CODE]!!
            _lastOverrideResult.value = null
            log.info(" Switched to builtin language: ${_currentTranslation.value.languageName}")
        }
    }

    private suspend fun applyLanguageSettings(settings: Settings) {
        val overrideFile = File(File(settingsService.gromozekaHome, "translations"), "override.json")
        applyTranslation(settings.currentLanguageCode, overrideFile)
    }

    fun refreshTranslations() {
        // Method for manual refresh from UI - launches in service scope
        serviceScope.launch {
            val currentSettings = settingsService.settingsFlow.value
            applyLanguageSettings(currentSettings)
        }
    }

    fun setTranslationOverride(languageCode: String, overrideFile: File): TranslationOverrideResult {
        val baseTranslation =
            Translation.builtIn[languageCode] ?: Translation.builtIn[EnglishTranslation.LANGUAGE_CODE]!!

        if (!overrideFile.exists()) {
            val result = TranslationOverrideResult.Failure(
                "Override file not found: ${overrideFile.absolutePath}",
                baseTranslation
            )
            _lastOverrideResult.value = result
            _currentTranslation.value = baseTranslation
            return result
        }

        return try {
            val jsonContent = overrideFile.readText()

            // Простая десериализация в нужный data class
            // kotlinx.serialization автоматически применит defaults для пропущенных полей
            val overriddenTranslation = when (languageCode) {
                EnglishTranslation.LANGUAGE_CODE -> json.decodeFromString<EnglishTranslation>(jsonContent)
                RussianTranslation.LANGUAGE_CODE -> json.decodeFromString<RussianTranslation>(jsonContent)
                HebrewTranslation.LANGUAGE_CODE -> json.decodeFromString<HebrewTranslation>(jsonContent)
                else -> json.decodeFromString<EnglishTranslation>(jsonContent) // fallback
            }

            val result = TranslationOverrideResult.Success(overriddenTranslation, listOf("Custom JSON override"))
            _lastOverrideResult.value = result
            _currentTranslation.value = overriddenTranslation

            log.info(" Applied translation override successfully")
            result
        } catch (e: SerializationException) {
            val result = TranslationOverrideResult.Failure("JSON parsing error: ${e.message}", baseTranslation)
            _lastOverrideResult.value = result
            _currentTranslation.value = baseTranslation
            log.info(" Translation override failed: ${e.message}")
            result
        } catch (e: Exception) {
            val result = TranslationOverrideResult.Failure("File error: ${e.message}", baseTranslation)
            _lastOverrideResult.value = result
            _currentTranslation.value = baseTranslation
            log.info(" Translation override failed: ${e.message}")
            result
        }
    }


    // === Export/Import functionality ===

    fun exportToFile(): Boolean {
        val overrideFile = File(File(settingsService.gromozekaHome, "translations"), "override.json")
        val currentLanguageCode = _currentTranslation.value.languageCode

        // Export builtin translation by language code
        val builtinTranslation = Translation.builtIn[currentLanguageCode]
            ?: Translation.builtIn[EnglishTranslation.LANGUAGE_CODE]!!
        val jsonContent = json.encodeToString<Translation>(builtinTranslation)

        return try {
            overrideFile.parentFile?.mkdirs()
            overrideFile.writeText(jsonContent)
            log.info(" Successfully exported builtin translation to: ${overrideFile.absolutePath}")
            true
        } catch (e: Exception) {
            log.info(" Failed to save translation: ${e.message}")
            false
        }
    }

}