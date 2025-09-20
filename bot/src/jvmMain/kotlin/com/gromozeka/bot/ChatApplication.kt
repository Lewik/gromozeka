package com.gromozeka.bot

import androidx.compose.ui.window.application
import com.gromozeka.bot.platform.GlobalHotkeyController
import com.gromozeka.bot.services.*
import com.gromozeka.bot.services.theming.AIThemeGenerator
import com.gromozeka.bot.services.theming.ThemeService
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.ui.ChatWindow
import com.gromozeka.bot.ui.GromozekaTheme
import com.gromozeka.bot.ui.TranslationProvider
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.springframework.ai.model.openai.autoconfigure.*
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.File

/**
 * Format folder name to human readable format
 * Converts: kebab-case, snake_case, camelCase, PascalCase to "Capitalized Words"
 * Also handles parent folder in the format "Parent / Child"
 */
fun formatProjectName(projectPath: String): String {
    val projectFile = File(projectPath)
    val projectName = projectFile.name.takeIf { it.isNotBlank() } ?: return "Unknown Project"
    val parentName = projectFile.parentFile?.name?.takeIf { it.isNotBlank() }

    fun formatFolderName(name: String): String {
        return name
            // Replace hyphens and underscores with spaces
            .replace(Regex("[-_]"), " ")
            // Split camelCase and PascalCase (insert space before uppercase letters)
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            // Split sequences of digits from letters
            .replace(Regex("(?<=[a-zA-Z])(?=\\d)|(?<=\\d)(?=[a-zA-Z])"), " ")
            // Split multiple words, normalize whitespace
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            // Capitalize each word
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    val formattedProject = formatFolderName(projectName)
    val formattedParent = parentName?.let { formatFolderName(it) }

    return if (formattedParent != null && formattedParent != formattedProject) {
        "$formattedParent / $formattedProject"
    } else {
        formattedProject
    }
}

/**
 * Get display name for a tab based on custom name or formatted project path
 */
fun getTabDisplayName(tabUiState: UIState.Tab, index: Int): String {
    return tabUiState.customName?.takeIf { it.isNotBlank() }
        ?: formatProjectName(tabUiState.projectPath)
}

fun main() {
    val log = KLoggers.logger("ChatApplication")
    System.setProperty("java.awt.headless", "false")

    // Check Claude Code is installed before starting
    val claudeProjectsDir = File(System.getProperty("user.home"), ".claude/projects")
    if (!claudeProjectsDir.exists()) {
        throw IllegalStateException("Claude Code not installed - directory does not exist: ${claudeProjectsDir.absolutePath}")
    }
    log.info("Claude Code installation verified")

    // Clean up old stream logs on startup
    log.info("Cleaning up old stream logs...")
    StreamLogger.cleanupOldLogs()

    log.info("Initializing Spring context...")

    // Determine app mode to set Spring profile
    val modeEnv = System.getenv("GROMOZEKA_MODE")
    val springProfile = when (modeEnv?.lowercase()) {
        "dev", "development" -> "dev"
        "prod", "production" -> "prod"
        null -> "prod" // Default to prod when not specified
        else -> throw IllegalArgumentException("GROMOZEKA_MODE value '$modeEnv' not supported")
    }
    log.info("Setting Spring profile: $springProfile")

    // Set logging path BEFORE Spring initialization
    val logPath = determineLogPath(modeEnv)
    System.setProperty("logging.file.path", logPath)

    val context = SpringApplicationBuilder(ChatApplication::class.java)
        .web(WebApplicationType.NONE)
        .profiles(springProfile)
        .run()
    log.info("Spring context initialized successfully")

    // Initialize JAR resources (extract MCP proxy JAR to Gromozeka home)
    val settingsService = context.getBean<SettingsService>()
    log.info("Initializing JAR resources...")
    log.info("JAR resources initialized successfully")
    log.info("Starting application in ${settingsService.mode.name} mode...")

    val ttsQueueService = context.getBean<TTSQueueService>()
    val ttsAutoplayService = context.getBean<TTSAutoplayService>()
    val sessionJsonlService = context.getBean<SessionJsonlService>()
    val sessionSearchService = context.getBean<SessionSearchService>()
    val sessionManager = context.getBean<SessionManager>()
    val globalHotkeyController = context.getBean<GlobalHotkeyController>()
    val pttEventRouter = context.getBean<PTTEventRouter>()
    val pttService = context.getBean<PTTService>()
    val windowStateService = context.getBean<WindowStateService>()
    val uiStateService = context.getBean<UIStateService>()
    val appViewModel = context.getBean<AppViewModel>()
    val translationService = context.getBean<TranslationService>()
    val themeService = context.getBean<ThemeService>()
    val screenCaptureController = context.getBean<com.gromozeka.bot.platform.ScreenCaptureController>()
    val mcpHttpServer = context.getBean<McpHttpServer>()
    val contextExtractionService = context.getBean<ContextExtractionService>()
    val contextFileService = context.getBean<ContextFileService>()
    val hookPermissionService = context.getBean<HookPermissionService>()
    val logEncryptor = context.getBean<LogEncryptor>()

    // Create AI theme generator
    val aiThemeGenerator = AIThemeGenerator(
        screenCaptureController = screenCaptureController,
        sessionManager = sessionManager,
        settingsService = settingsService
    )

    // Explicit startup of TTS services
    ttsQueueService.start()
    log.info("TTS queue service started")

    ttsAutoplayService.start()
    log.info("TTS autoplay service started")

    // Start global MCP HTTP server
    mcpHttpServer.start()
    log.info("Global MCP HTTP server started")

    // Initialize JAR resources (copy from resources to Gromozeka home)
    log.info("MCP proxy JAR initialized")

    // Initialize services
    globalHotkeyController.initializeService()
    pttEventRouter.initialize()

    // Initialize HookPermissionService actor
    val coroutineScope = context.getBean("coroutineScope") as CoroutineScope
    hookPermissionService.initializeActor(coroutineScope)
    log.info("HookPermissionService actor initialized")

    // Initialize UIStateService (loads state, restores sessions, starts subscription)
    runBlocking {
        uiStateService.initialize(appViewModel)
    }

    // TranslationService automatically initializes via @Bean creation and subscribes to settings

    log.info("Starting Compose Desktop UI...")
    application {
        GromozekaTheme(
            themeService = themeService
        ) {
            TranslationProvider(translationService) {
                ChatWindow(
                    appViewModel,
                    ttsQueueService,
                    settingsService,
                    sessionJsonlService,
                    sessionSearchService,
                    sessionManager,
                    globalHotkeyController,
                    pttEventRouter,
                    pttService,
                    windowStateService,
                    uiStateService,
                    translationService,
                    themeService,
                    aiThemeGenerator,
                    logEncryptor,
                    mcpHttpServer,
                    context.getBean("hookPermissionService") as HookPermissionService,
                    contextExtractionService,
                    contextFileService
                )
            }  // TranslationProvider
        }  // GromozekaTheme
    }
}

/**
 * Determines the appropriate log directory based on application mode and platform.
 * Must be called BEFORE Spring initialization to set system properties early.
 */
private fun determineLogPath(modeEnv: String?): String {
    val mode = modeEnv?.lowercase() ?: "prod"
    val osName = System.getProperty("os.name").lowercase()
    val userHome = System.getProperty("user.home")

    return when (mode) {
        "dev", "development" -> "logs"
        else -> when {
            osName.contains("mac") -> "$userHome/Library/Logs/Gromozeka"
            osName.contains("windows") -> "$userHome/AppData/Local/Gromozeka/logs"
            osName.contains("linux") -> "$userHome/.local/share/Gromozeka/logs"
            else -> "logs" // Fallback for unknown platforms
        }
    }
}

@SpringBootApplication(
    exclude = [
        OpenAiEmbeddingAutoConfiguration::class,
        OpenAiImageAutoConfiguration::class,
        OpenAiChatAutoConfiguration::class,
        OpenAiAudioTranscriptionAutoConfiguration::class,
        OpenAiAudioSpeechAutoConfiguration::class,
        OpenAiModerationAutoConfiguration::class
    ]
)
class ChatApplication