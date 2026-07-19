package com.gromozeka.application.service

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import jakarta.annotation.PostConstruct
import klog.KLoggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path

@Service
class SettingsService : com.gromozeka.domain.service.SettingsService {

    private val log = KLoggers.logger("SettingsService")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override val mode: AppMode = determineMode()

    val gromozekaHome: File = determineGromozekaHome()

    @Value("\${logging.file.path}")
    private lateinit var logPath: String

    private val settingsFile: File = File(gromozekaHome, "settings.json")

    private val _settingsFlow = MutableStateFlow(Settings())
    override val settingsFlow: StateFlow<Settings> = _settingsFlow.asStateFlow()

    override val userProfile get() = settings.userProfile
    override val userDeviceSettings get() = settings.userDeviceSettings
    override val homeDirectory: String get() = gromozekaHome.absolutePath

    override fun resolveSecret(secretRef: SecretRef?): String? = when (secretRef) {
        null -> null
        is SecretRef.Inline -> secretRef.value
        is SecretRef.EnvironmentVariable -> System.getenv(secretRef.name)?.takeIf { it.isNotBlank() }
    }

    @PostConstruct
    fun initialize() {
        if (gromozekaHome.mkdirs()) {
            log.info("Created gromozeka home directory: ${gromozekaHome.absolutePath}")
        }

        _settingsFlow.value = loadSettings()

        log.info("Initialized with mode: ${mode.name}")
        log.info("Gromozeka home: ${gromozekaHome.absolutePath}")
    }

    private fun determineMode(): AppMode {
        val modeEnv = System.getProperty("GROMOZEKA_MODE")
            ?: System.getenv("GROMOZEKA_MODE")
        log.info("Configured GROMOZEKA_MODE = $modeEnv")

        return when (modeEnv?.lowercase()) {
            "dev", "development" -> AppMode.DEV
            "test", "e2e" -> AppMode.TEST
            "prod", "production" -> AppMode.PRODUCTION
            null -> AppMode.PRODUCTION
            else -> throw IllegalArgumentException("GROMOZEKA_MODE value '$modeEnv' not supported")
        }
    }

    private fun determineGromozekaHome(): File {
        val customPath = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")

        return when {
            customPath != null -> {
                val customDir = File(customPath)
                log.info("Using custom gromozeka home: ${customDir.absolutePath}")
                customDir
            }

            mode == AppMode.DEV -> {
                val projectRoot = System.getProperty("gromozeka.project.root")
                    ?: error(
                        "DEV mode requires 'gromozeka.project.root' system property. " +
                            "Ensure application is started via Gradle."
                    )

                val devDataDir = File(projectRoot, "dev-data/client/.gromozeka")
                log.info("DEV mode - project root: $projectRoot")
                log.info("DEV mode - using dev-data: ${devDataDir.absolutePath}")
                devDataDir
            }

            mode == AppMode.TEST -> {
                val testDir = File("build/test-data/.gromozeka")
                log.info("TEST mode - using test data directory: ${testDir.absolutePath}")
                testDir
            }

            else -> {
                val prodDir = File(System.getProperty("user.home"), ".gromozeka")
                log.info("PROD mode - using user home directory: ${prodDir.absolutePath}")
                prodDir
            }
        }
    }

    private fun loadSettings(): Settings = if (settingsFile.exists()) {
        try {
            val settings = json.decodeFromString<Settings>(settingsFile.readText())
            validateSettings(settings)
            settings
        } catch (e: Exception) {
            log.info("Failed to load settings: ${e.message}")
            createDefaultSettings()
        }
    } else {
        log.info("Settings file not found, creating defaults")
        createDefaultSettings()
    }

    private fun createDefaultSettings(): Settings {
        val defaults = Settings(
            userProfile = UserProfile(
                speechSettings = UserProfile.SpeechSettings(
                    speechToText = UserProfile.SpeechSettings.SpeechToText(enabled = true),
                    textToSpeech = UserProfile.SpeechSettings.TextToSpeech(enabled = true),
                ),
            ),
            userDeviceSettings = UserDeviceSettings.Desktop(
                uiSettings = UserDeviceSettings.UiSettings(),
                inputSettings = UserDeviceSettings.DesktopInputSettings(autoSend = true),
            ),
        )

        settingsFile.writeText(json.encodeToString(defaults))
        log.info("Created default settings file: ${settingsFile.absolutePath}")

        return defaults
    }

    override fun saveSettings(settings: Settings) {
        validateSettings(settings)
        settingsFile.writeText(json.encodeToString(settings))
        _settingsFlow.value = settings
        log.info("Settings saved to: ${settingsFile.absolutePath}")
    }

    override fun saveSettings(block: Settings.() -> Settings) {
        val updated = settings.block()
        saveSettings(updated)
    }

    override fun reloadSettings() {
        _settingsFlow.value = loadSettings()
        log.info("Settings reloaded from file")
    }

    override val settings: Settings
        get() = _settingsFlow.value

    fun getLogsDir(): File {
        return File(gromozekaHome, "logs").apply { mkdirs() }
    }

    fun getCacheDir(): File {
        return File(gromozekaHome, "cache").apply { mkdirs() }
    }

    fun getSessionsDir(): File {
        return File(gromozekaHome, "sessions").apply { mkdirs() }
    }

    fun getLogsDirectory(): Path = Path(logPath)

    private fun validateSettings(settings: Settings) {
        validateLanguageCode(settings.userProfile.speechSettings.speechToText.mainLanguageCode)
        validateTtsSpeed(settings.userProfile.speechSettings.textToSpeech.speed)
    }

    private fun validateLanguageCode(languageCode: String) {
        require(languageCode.isNotBlank()) {
            "STT language code cannot be blank"
        }

        val isValidIso6391 = try {
            val locale = Locale.forLanguageTag(languageCode)
            locale.language.isNotBlank() && locale.language != "und"
        } catch (e: Exception) {
            false
        }

        val isValidLength = languageCode.length in 2..3
        val isAlphabetic = languageCode.all { it.isLetter() }

        require(isValidIso6391 && isValidLength && isAlphabetic) {
            "Invalid STT language code: '$languageCode'. " +
                "Must be a valid ISO 639-1 (2-letter) or ISO 639-3 (3-letter) code. " +
                "Examples: 'en', 'ru', 'zh', 'es', 'fra', 'deu'"
        }
    }

    private fun validateTtsSpeed(speed: Float) {
        require(speed in 0.25f..4.0f) {
            "Invalid TTS speed: $speed. Must be between 0.25 (slowest) and 4.0 (fastest). Default: 1.0"
        }
    }

}
