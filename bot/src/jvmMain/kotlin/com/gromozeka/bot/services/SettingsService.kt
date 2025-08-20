package com.gromozeka.bot.services

import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.util.*

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

    private val _settingsFlow = MutableStateFlow<Settings>(Settings())
    val settingsFlow: StateFlow<Settings> = _settingsFlow.asStateFlow()

    /**
     * Initialize the service automatically after Spring bean creation
     */
    fun initialize() {
        if (gromozekaHome.mkdirs()) {
            println("[SettingsService] Created gromozeka home directory: ${gromozekaHome.absolutePath}")
        }

        // Load settings
        _settingsFlow.value = loadSettings()

        // Generate MCP config
        generateMcpConfig()

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
                val devDataDir = if (projectDir.name == "bot") {
                    // Running from bot/ subdirectory (gradlew from bot/)
                    File(projectDir, "dev-data/.gromozeka")
                } else {
                    // Running from project root
                    File(projectDir, "bot/dev-data/.gromozeka")
                }
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
            val settings = json.decodeFromString<Settings>(settingsFile.readText())
            validateSettings(settings)
            settings
        } catch (e: Exception) {
            println("[SettingsService] Failed to load settings: ${e.message}")
            createDefaultSettings()
        }
    } else {
        println("[SettingsService] Settings file not found, creating defaults")
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
        println("[SettingsService] Created default settings file: ${settingsFile.absolutePath}")

        return defaults
    }

    fun saveSettings(settings: Settings) {
        validateSettings(settings) // Fail fast on invalid settings
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
        _settingsFlow.value = loadSettings()
        println("[SettingsService] Settings reloaded from file")
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

    // Claude specific paths
    fun getClaudeProjectsDir(): File {
        return File(claudeHome, "projects")
    }

    fun getClaudeConfigDir(): File {
        return File(claudeHome, "config")
    }

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

            println("[SettingsService] Auto-detected UI scale: $detectedScale (OS: $osName, DPI: $screenResolution, SystemScale: $systemScale)")
            detectedScale

        } catch (e: Exception) {
            println("[SettingsService] Failed to auto-detect UI scale, using default: ${e.message}")
            1.0f  // Safe fallback
        }
    }

    /**
     * Generate MCP configuration file for Claude Code CLI integration
     */
    private fun generateMcpConfig() {
        try {
            // Read template from resources
            val templateStream = this::class.java.getResourceAsStream("/mcp-config-template.json")
                ?: throw IllegalStateException("MCP config template not found in resources")
            
            val template = templateStream.bufferedReader().use { it.readText() }
            
            // Determine JAR path - always use absolute path to built JAR
            val jarPath = JarResourceManager.getMcpProxyJarPath(this)
            
            // Replace template placeholder
            val mcpConfig = template.replace("{{JAR_PATH}}", jarPath)
            
            // Write to gromozeka home
            val mcpConfigFile = File(gromozekaHome, "mcp-config.json")
            mcpConfigFile.writeText(mcpConfig)
            
            println("[SettingsService] Generated MCP config: ${mcpConfigFile.absolutePath}")
            println("[SettingsService] MCP JAR path: $jarPath")
            
        } catch (e: Exception) {
            println("[SettingsService] Failed to generate MCP config: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Get path to MCP config file for Claude Code CLI
     */
    fun getMcpConfigPath(): String = File(gromozekaHome, "mcp-sse-config.json").absolutePath


}