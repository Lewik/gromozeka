package com.gromozeka.presentation

import androidx.compose.ui.window.application
import com.gromozeka.application.service.TabPromptService
import com.gromozeka.domain.repository.AgentDomainService
import com.gromozeka.domain.repository.ConversationDomainService
import com.gromozeka.domain.repository.ProjectDomainService
import com.gromozeka.domain.repository.PromptDomainService
import com.gromozeka.infrastructure.ai.oauth.OAuthConfigService
import com.gromozeka.infrastructure.ai.oauth.OAuthService
import com.gromozeka.infrastructure.ai.platform.GlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.service.OllamaModelService
import com.gromozeka.presentation.services.*
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.ChatWindow
import com.gromozeka.presentation.ui.ErrorDialog
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.TranslationProvider
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import jakarta.annotation.PostConstruct
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.system.exitProcess

/**
 * Get display name for a tab based on custom name or agent name
 */
fun getTabDisplayName(tabUiState: UIState.Tab, index: Int): String {
    return tabUiState.customName?.takeIf { it.isNotBlank() }
        ?: tabUiState.agent.name
}

fun main() {
    val log = KLoggers.logger("ChatApplication")
    System.setProperty("java.awt.headless", "false")

    var initializationError: Throwable? = null
    var appComponents: AppComponents? = null

    try {
        log.info("Initializing Spring context...")

        // Determine app mode to set Spring profile
        val modeEnv = System.getenv("GROMOZEKA_MODE")
        val springProfile = when (modeEnv?.lowercase()) {
            "dev", "development" -> "dev"
            "prod", "production" -> "prod"
            null -> "prod"
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

        // Initialize JAR resources
        val settingsService = context.getBean<SettingsService>()
        log.info("Initializing JAR resources...")
        log.info("JAR resources initialized successfully")
        log.info("Starting application in ${settingsService.mode.name} mode...")

        val ttsQueueService = context.getBean<TTSQueueService>()
        val ttsAutoplayService = context.getBean<TTSAutoplayService>()
        val globalHotkeyController = context.getBean<GlobalHotkeyController>()
        val pttEventRouter = context.getBean<PTTEventRouter>()
        val pttService = context.getBean<PTTService>()
        val windowStateService = context.getBean<WindowStateService>()
        val uiStateService = context.getBean<UIStateService>()
        val appViewModel = context.getBean<AppViewModel>()
        val translationService = context.getBean<TranslationService>()
        val themeService = context.getBean<ThemeService>()
        val screenCaptureController = context.getBean<ScreenCaptureController>()
        val logEncryptor = context.getBean<LogEncryptor>()

        // Create AI theme generator
        val aiThemeGenerator = AIThemeGenerator(
            screenCaptureController = screenCaptureController,
            settingsService = settingsService
        )

        // Explicit startup of TTS services
        ttsQueueService.start()
        log.info("TTS queue service started")

        ttsAutoplayService.start()
        log.info("TTS autoplay service started")

        log.info("MCP proxy JAR initialized")

        // Initialize services
        globalHotkeyController.initializeService()
        pttEventRouter.initialize()

        // Initialize UIStateService
        val coroutineScope = context.getBean("coroutineScope") as CoroutineScope
        runBlocking {
            uiStateService.initialize(appViewModel)
        }

        log.info("Application initialized successfully")

        appComponents = AppComponents(
            appViewModel = appViewModel,
            ttsQueueService = ttsQueueService,
            settingsService = settingsService,
            globalHotkeyController = globalHotkeyController,
            pttEventRouter = pttEventRouter,
            pttService = pttService,
            windowStateService = windowStateService,
            uiStateService = uiStateService,
            translationService = translationService,
            themeService = themeService,
            aiThemeGenerator = aiThemeGenerator,
            logEncryptor = logEncryptor,
            ollamaModelService = context.getBean(),
            oAuthService = context.getBean(),
            oauthConfigService = context.getBean(),
            projectService = context.getBean(),
            conversationService = context.getBean(),
            conversationSearchViewModel = context.getBean(),
            loadingViewModel = context.getBean(),
            tabPromptService = context.getBean(),
            agentService = context.getBean(),
            promptService = context.getBean()
        )

    } catch (e: Throwable) {
        log.error("Failed to initialize application: ${e.message}")
        e.printStackTrace()
        initializationError = e
    }

    log.info("Starting Compose Desktop UI...")
    application {
        if (initializationError != null) {
            ErrorDialog(
                error = initializationError,
                onClose = { exitProcess(1) }
            )
        } else if (appComponents != null) {
            GromozekaTheme(
                themeService = appComponents.themeService
            ) {
                TranslationProvider(appComponents.translationService) {
                    ChatWindow(
                        appComponents.appViewModel,
                        appComponents.ttsQueueService,
                        appComponents.settingsService,
                        appComponents.globalHotkeyController,
                        appComponents.pttEventRouter,
                        appComponents.pttService,
                        appComponents.windowStateService,
                        appComponents.uiStateService,
                        appComponents.translationService,
                        appComponents.themeService,
                        appComponents.aiThemeGenerator,
                        appComponents.logEncryptor,
                        appComponents.ollamaModelService,
                        appComponents.oAuthService,
                        appComponents.oauthConfigService,
                        appComponents.projectService,
                        appComponents.conversationService,
                        appComponents.conversationSearchViewModel,
                        appComponents.loadingViewModel,
                        appComponents.tabPromptService,
                        appComponents.agentService,
                        appComponents.promptService
                    )
                }
            }
        }
    }
}

data class AppComponents(
    val appViewModel: AppViewModel,
    val ttsQueueService: TTSQueueService,
    val settingsService: SettingsService,
    val globalHotkeyController: GlobalHotkeyController,
    val pttEventRouter: PTTEventRouter,
    val pttService: PTTService,
    val windowStateService: WindowStateService,
    val uiStateService: UIStateService,
    val translationService: TranslationService,
    val themeService: ThemeService,
    val aiThemeGenerator: AIThemeGenerator,
    val logEncryptor: LogEncryptor,
    val ollamaModelService: OllamaModelService,
    val oAuthService: OAuthService,
    val oauthConfigService: OAuthConfigService,
    val projectService: ProjectDomainService,
    val conversationService: ConversationDomainService,
    val conversationSearchViewModel: ConversationSearchViewModel,
    val loadingViewModel: LoadingViewModel,
    val tabPromptService: TabPromptService,
    val agentService: AgentDomainService,
    val promptService: PromptDomainService,
)

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
    scanBasePackages = [
        "com.gromozeka.presentation",
        "com.gromozeka.application",
        "com.gromozeka.infrastructure.db",
        "com.gromozeka.infrastructure.ai"
    ],
    exclude = [
        JdbcTemplateAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
    ]
)
class ChatApplication(
    private val settingsService: SettingsService,
) {

    @PostConstruct
    fun setupEnvironment() {
        // Set GROMOZEKA_HOME system property for Spring configuration resolution
        // This ensures application.yaml can resolve ${GROMOZEKA_HOME} placeholder
        System.setProperty("GROMOZEKA_HOME", settingsService.gromozekaHome.absolutePath)
    }
}