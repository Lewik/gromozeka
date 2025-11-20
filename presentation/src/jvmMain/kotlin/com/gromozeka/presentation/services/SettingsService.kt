package com.gromozeka.presentation.services

import com.gromozeka.presentation.model.Settings
import com.gromozeka.domain.service.AIProvider
import com.gromozeka.domain.service.AppMode
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.shared.utils.findRandomAvailablePort
import klog.KLoggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.springframework.beans.factory.annotation.Value
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path

class SettingsService : SettingsProvider {

    private val log = KLoggers.logger("SettingsService")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override val mode: AppMode = determineMode()

    val gromozekaHome: File = determineGromozekaHome()

    val mcpConfigFile = File(gromozekaHome, "mcp-sse-config.json")
    val mcpPort = findRandomAvailablePort()

    @Value("\${logging.file.path}")
    private lateinit var logPath: String

    private val settingsFile: File = File(gromozekaHome, "settings.json")

    private val _settingsFlow = MutableStateFlow(Settings())
    val settingsFlow: StateFlow<Settings> = _settingsFlow.asStateFlow()

    // SettingsProvider implementation - delegate to current settings
    override val sttMainLanguage: String get() = settings.sttMainLanguage
    override val ttsModel: String get() = settings.ttsModel
    override val ttsVoice: String get() = settings.ttsVoice
    override val ttsSpeed: Float get() = settings.ttsSpeed
    override val aiProvider: AIProvider get() = settings.defaultAiProvider
    override val homeDirectory: String get() = gromozekaHome.absolutePath

    /**
     * Initialize the service automatically after Spring bean creation
     */
    fun initialize() {
        if (gromozekaHome.mkdirs()) {
            log.info("Created gromozeka home directory: ${gromozekaHome.absolutePath}")
        }

        _settingsFlow.value = loadSettings()

        generateMcpConfigFile()

        log.info("Initialized with mode: ${mode.name}")
        log.info("Gromozeka home: ${gromozekaHome.absolutePath}")
    }

    private fun determineMode(): AppMode {
        val modeEnv = System.getenv("GROMOZEKA_MODE")
        log.info("Environment variable GROMOZEKA_MODE = $modeEnv")

        return when (modeEnv?.lowercase()) {
            "dev", "development" -> AppMode.DEV
            "prod", "production" -> AppMode.PRODUCTION
            null -> AppMode.PRODUCTION
            else -> throw IllegalArgumentException("GROMOZEKA_MODE value '$modeEnv' not supported")
        }
    }

    private fun determineGromozekaHome(): File {
        val customPath = System.getenv("GROMOZEKA_HOME")

        return when {
            customPath != null -> {
                val customDir = File(customPath)
                log.info("Using custom gromozeka home from system property: ${customDir.absolutePath}")
                customDir
            }

            mode == AppMode.DEV -> {
                // Use project directory for dev mode - more reliable than resources
                val projectDir = File(System.getProperty("user.dir"))
                val devDataDir = if (projectDir.name == "bot") {
                    // Running from bot/ subdirectory (gradlew from bot/)
                    File(projectDir, "dev-data/.gromozeka")
                } else {
                    // Running from project root
                    File(projectDir, "bot/dev-data/.gromozeka")
                }
                log.info("DEV mode - using project dev-data: ${devDataDir.absolutePath}")
                devDataDir
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
        val detectedScale = detectOptimalUIScale()

        val defaults = Settings(
            enableTts = true,
            enableStt = true,
            autoSend = true,
            enableErrorSounds = false,
            enableMessageSounds = false,
            enableReadySounds = false,
            soundVolume = 1.0f,
            uiScale = detectedScale // Auto-detect once on first launch, then user controls manually
        )

        settingsFile.writeText(json.encodeToString(defaults))
        log.info("Created default settings file: ${settingsFile.absolutePath}")

        return defaults
    }

    fun saveSettings(settings: Settings) {
        validateSettings(settings) // Fail fast on invalid settings
        // Always save to file in both modes
        settingsFile.writeText(json.encodeToString(settings))
        _settingsFlow.value = settings
        log.info("Settings saved to: ${settingsFile.absolutePath}")
    }

    fun saveSettings(block: Settings.() -> Settings) {
        val updated = settings.block()
        saveSettings(updated)
    }

    fun reloadSettings() {
        _settingsFlow.value = loadSettings()
        log.info("Settings reloaded from file")
    }

    // Utility methods
    val settings: Settings
        get() = _settingsFlow.value

    // Utility methods for common paths
    fun getLogsDir(): File {
        return File(gromozekaHome, "logs").apply { mkdirs() }
    }

    fun getCacheDir(): File {
        return File(gromozekaHome, "cache").apply { mkdirs() }
    }

    fun getSessionsDir(): File {
        return File(gromozekaHome, "sessions").apply { mkdirs() }
    }

    // Log directory paths (single source of truth)
    fun getLogsDirectory(): Path = Path(logPath)

    /**
     * Validates settings for correctness and fail-fast behavior
     */
    private fun validateSettings(settings: Settings) {
        validateLanguageCode(settings.sttMainLanguage)
        validateTtsSpeed(settings.ttsSpeed)
    }

    /**
     * Validates ISO 639-1 and 639-3 language codes
     * Fail fast if invalid - OpenAI models won't understand invalid codes
     */
    private fun validateLanguageCode(languageCode: String) {
        require(languageCode.isNotBlank()) {
            "STT language code cannot be blank"
        }

        // Check if it's a valid ISO language code using Java's Locale
        val isValidIso639_1 = try {
            val locale = Locale.forLanguageTag(languageCode)
            locale.language.isNotBlank() && locale.language != "und"
        } catch (e: Exception) {
            false
        }

        // Additional check for 2-letter and 3-letter codes (ISO 639-1 and 639-3)
        val isValidLength = languageCode.length in 2..3
        val isAlphabetic = languageCode.all { it.isLetter() }

        require(isValidIso639_1 && isValidLength && isAlphabetic) {
            "Invalid STT language code: '$languageCode'. " +
                    "Must be a valid ISO 639-1 (2-letter) or ISO 639-3 (3-letter) code. " +
                    "Examples: 'en', 'ru', 'zh', 'es', 'fra', 'deu'"
        }

    }

    /**
     * Validates TTS speed parameter for OpenAI API compatibility
     */
    private fun validateTtsSpeed(speed: Float) {
        require(speed in 0.25f..4.0f) {
            "Invalid TTS speed: $speed. Must be between 0.25 (slowest) and 4.0 (fastest). Default: 1.0"
        }

    }

    /**
     * Auto-detect optimal UI scale based on platform and screen characteristics
     */
    private fun detectOptimalUIScale(): Float {
        return try {
            val toolkit = Toolkit.getDefaultToolkit()
            val screenResolution = toolkit.screenResolution // DPI

            // Get system scaling from Java 2D
            val systemScale = try {
                val gfxEnv = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val defaultScreen = gfxEnv.defaultScreenDevice
                val defaultConfig = defaultScreen.defaultConfiguration
                defaultConfig.defaultTransform.scaleX.toFloat()
            } catch (e: Exception) {
                1.0f
            }

            val osName = System.getProperty("os.name").lowercase()
            val detectedScale = when {
                // macOS - handle retina displays
                osName.contains("mac") -> when {
                    systemScale >= 2.0f -> 1.5f  // Retina: scale down from 2x
                    screenResolution >= 150 -> 1.3f  // High DPI
                    else -> 1.0f  // Standard DPI
                }

                // Windows - handle high DPI scaling  
                osName.contains("windows") -> when {
                    systemScale >= 2.0f -> 1.7f  // 200% scaling
                    systemScale >= 1.5f -> 1.4f  // 150% scaling  
                    systemScale >= 1.25f -> 1.2f // 125% scaling
                    else -> 1.0f  // 100% scaling
                }

                // Linux - conservative scaling
                osName.contains("linux") -> when {
                    screenResolution >= 180 -> 1.3f  // High DPI
                    screenResolution >= 120 -> 1.1f  // Medium DPI
                    else -> 1.0f  // Standard DPI
                }

                // Other platforms - safe default
                else -> 1.0f
            }

            log.info("Auto-detected UI scale: $detectedScale (OS: $osName, DPI: $screenResolution, SystemScale: $systemScale)")
            detectedScale

        } catch (e: Exception) {
            log.info("Failed to auto-detect UI scale, using default: ${e.message}")
            1.0f  // Safe fallback
        }
    }

    fun generateMcpConfigFile() {
        val url = "http://localhost:$mcpPort/sse"

        val configContent = """
        {
          "mcpServers": {
            "gromozeka": {
              "type": "sse",
              "url": "$url"
            }
          }
        }
        """.trimIndent()

        mcpConfigFile.writeText(configContent)

        log.info("Generated global config: ${mcpConfigFile.absolutePath}")
    }

}