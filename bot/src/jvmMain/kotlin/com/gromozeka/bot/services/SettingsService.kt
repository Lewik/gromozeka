package com.gromozeka.bot.services

import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.utils.findRandomAvailablePort
import klog.KLoggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.util.*

class SettingsService {

    private val log = KLoggers.logger("SettingsService")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val mode: AppMode = determineMode()

    val gromozekaHome: File = determineGromozekaHome()

    val claudeHome: File = System.getProperty("claude.home")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".claude")

    val mcpConfigFile = File(gromozekaHome, "mcp-sse-config.json")
    val mcpPort = findRandomAvailablePort()

    private val settingsFile: File = File(gromozekaHome, "settings.json")

    private val _settingsFlow = MutableStateFlow(Settings())
    val settingsFlow: StateFlow<Settings> = _settingsFlow.asStateFlow()

    /**
     * Initialize the service automatically after Spring bean creation
     */
    fun initialize() {
        if (gromozekaHome.mkdirs()) {
            log.info("Created gromozeka home directory: ${gromozekaHome.absolutePath}")
        }

        _settingsFlow.value = loadSettings()

        generateMcpConfigFile()
        generateHooksConfig()

        log.info("Initialized with mode: ${mode.name}")
        log.info("Gromozeka home: ${gromozekaHome.absolutePath}")
    }

    private fun determineMode(): AppMode {
        val modeEnv = System.getenv("GROMOZEKA_MODE")
        log.info("Environment variable GROMOZEKA_MODE = $modeEnv")

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
        val detectedClaudePath = ClaudeCodeStreamingWrapper.detectClaudePath()

        val defaults = Settings(
            enableTts = true,
            enableStt = true,
            autoSend = true,
            enableErrorSounds = false,
            enableMessageSounds = false,
            enableReadySounds = false,
            soundVolume = 1.0f,
            uiScale = detectedScale, // Auto-detect once on first launch, then user controls manually
            claudeCliPath = detectedClaudePath // Auto-detect Claude CLI path
        )

        if (detectedClaudePath != null) {
            log.info("Auto-detected Claude CLI path: $detectedClaudePath")
        } else {
            log.warn("Could not auto-detect Claude CLI path, will use fallback")
        }

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

    /**
     * Generate official Anthropic Claude Code CLI hooks configuration
     * Uses jq for JSON processing (mandatory requirement)
     * No fallback - jq must be installed
     */
    fun generateHooksConfig() {
        val claudeSettingsFile = File(System.getProperty("user.home"), ".claude/settings.json")

        try {
            // Read existing global settings or create empty object
            val existingContent = if (claudeSettingsFile.exists()) {
                Json.parseToJsonElement(claudeSettingsFile.readText()).jsonObject
            } else {
                buildJsonObject {}
            }

            // Official Anthropic hook command: jq passes stdin JSON directly to HTTP endpoint
            val jqHookCommand = buildString {
                append("jq -c '.' | ")
                append("curl -X POST http://localhost:$mcpPort/hook-permission ")
                append("-H 'Content-Type: application/json' ")
                append("--max-time 30 --silent ")
                append("-d @-")
            }

            // Official hooks configuration format
            val hooksConfig = buildJsonObject {
                put("PreToolUse", buildJsonArray {
                    add(buildJsonObject {
                        put("matcher", "*")  // Match all tools
                        put("hooks", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "command")
                                put("command", jqHookCommand)
                            })
                        })
                    })
                })
            }

            // Merge with existing settings
            val updatedContent = buildJsonObject {
                existingContent.forEach { (key, value) ->
                    if (key != "hooks") {  // Don't duplicate hooks
                        put(key, value)
                    }
                }
                put("hooks", hooksConfig)
            }

            // Write back to file with pretty formatting
            claudeSettingsFile.parentFile?.mkdirs()
            claudeSettingsFile.writeText(Json { prettyPrint = true }.encodeToString(updatedContent))

            log.info("Generated official Anthropic hooks config (jq required) with port $mcpPort: ${claudeSettingsFile.absolutePath}")
            log.info("IMPORTANT: jq must be installed for hooks to work")

        } catch (e: Exception) {
            log.error(e, "Failed to generate hooks config")
        }
    }
}