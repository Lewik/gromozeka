package com.gromozeka.presentation

import com.gromozeka.application.service.TabPromptService
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.infrastructure.ai.platform.GlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.service.OllamaModelService
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.PTTEventRouter
import com.gromozeka.presentation.services.PTTService
import com.gromozeka.presentation.services.SettingsService
import com.gromozeka.presentation.services.TTSAutoplayService
import com.gromozeka.presentation.services.TTSQueueService
import com.gromozeka.presentation.services.UIStateService
import com.gromozeka.presentation.services.WindowStateService
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
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
import org.springframework.context.ConfigurableApplicationContext
import java.io.Closeable

data class AppInitializationOptions(
    val initializeUiState: Boolean = true,
    val initializePttRouter: Boolean = true,
    val initializeGlobalHotkeys: Boolean = true,
    val startTtsQueueService: Boolean = true,
    val startTtsAutoplayService: Boolean = true,
)

data class AppBootstrapOptions(
    val modeOverride: String? = null,
    val springProfileOverride: String? = null,
    val logPathOverride: String? = null,
    val systemProperties: Map<String, String> = emptyMap(),
    val additionalSources: List<Class<*>> = emptyList(),
    val initialization: AppInitializationOptions = AppInitializationOptions(),
)

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
    val projectService: ProjectDomainService,
    val conversationService: ConversationDomainService,
    val conversationSearchViewModel: ConversationSearchViewModel,
    val loadingViewModel: LoadingViewModel,
    val tabPromptService: TabPromptService,
    val agentService: AgentDomainService,
    val promptService: PromptDomainService,
)

class StartedApp internal constructor(
    val context: ConfigurableApplicationContext,
    val appComponents: AppComponents,
    private val previousSystemProperties: Map<String, String?>,
) : Closeable {

    override fun close() {
        runCatching { appComponents.uiStateService.forceSave() }
        runCatching { appComponents.uiStateService.disableAutoSave() }
        runCatching { runBlocking { appComponents.appViewModel.cleanup() } }
        runCatching { appComponents.globalHotkeyController.cleanup() }
        runCatching { appComponents.ttsQueueService.shutdown() }
        runCatching { context.close() }

        previousSystemProperties.forEach { (key, value) ->
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
    }
}

object AppBootstrap {
    private val log = KLoggers.logger("AppBootstrap")

    fun start(options: AppBootstrapOptions = AppBootstrapOptions()): StartedApp {
        val modeValue = options.modeOverride ?: readModeOverride()
        val springProfile = options.springProfileOverride ?: resolveSpringProfile(modeValue)
        val logPath = options.logPathOverride ?: determineLogPath(modeValue)

        val systemProperties = buildMap {
            put("logging.file.path", logPath)
            modeValue?.let { put("GROMOZEKA_MODE", it) }
            putAll(options.systemProperties)
        }

        val previousProperties = applySystemProperties(systemProperties)

        return try {
            log.info("Setting Spring profile: $springProfile")
            val context = SpringApplicationBuilder(ChatApplication::class.java)
                .sources(*options.additionalSources.toTypedArray())
                .web(WebApplicationType.NONE)
                .profiles(springProfile)
                .run()

            val appComponents = initializeAppComponents(context, options.initialization)
            StartedApp(context, appComponents, previousProperties)
        } catch (error: Throwable) {
            previousProperties.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
            throw error
        }
    }

    private fun applySystemProperties(properties: Map<String, String>): Map<String, String?> {
        return properties.mapValues { (key, value) ->
            System.getProperty(key).also { System.setProperty(key, value) }
        }
    }

    private fun initializeAppComponents(
        context: ConfigurableApplicationContext,
        options: AppInitializationOptions,
    ): AppComponents {
        log.info("Spring context initialized successfully")

        val settingsService = context.getBean<SettingsService>()
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

        val aiThemeGenerator = AIThemeGenerator(
            screenCaptureController = screenCaptureController,
            settingsService = settingsService
        )

        if (options.startTtsQueueService) {
            ttsQueueService.start()
            log.info("TTS queue service started")
        }

        if (options.startTtsAutoplayService) {
            ttsAutoplayService.start()
            log.info("TTS autoplay service started")
        }

        if (options.initializeGlobalHotkeys) {
            globalHotkeyController.initializeService()
        }

        if (options.initializePttRouter) {
            pttEventRouter.initialize()
        }

        if (options.initializeUiState) {
            val coroutineScope = context.getBean("coroutineScope") as CoroutineScope
            runBlocking {
                uiStateService.initialize(appViewModel)
            }
        }

        log.info("Application initialized successfully")

        return AppComponents(
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
            projectService = context.getBean(),
            conversationService = context.getBean(),
            conversationSearchViewModel = context.getBean(),
            loadingViewModel = context.getBean(),
            tabPromptService = context.getBean(),
            agentService = context.getBean(),
            promptService = context.getBean()
        )
    }

    internal fun readModeOverride(): String? =
        System.getProperty("GROMOZEKA_MODE")
            ?: System.getenv("GROMOZEKA_MODE")

    internal fun resolveSpringProfile(modeValue: String?): String =
        when (modeValue?.lowercase()) {
            "dev", "development" -> "dev"
            "test", "e2e" -> "e2e"
            "prod", "production", null -> "prod"
            else -> throw IllegalArgumentException("GROMOZEKA_MODE value '$modeValue' not supported")
        }

    internal fun determineLogPath(modeValue: String?): String {
        val normalizedMode = modeValue?.lowercase()
        val customHome = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")

        return when (normalizedMode) {
            "dev", "development" -> "logs"
            "test", "e2e" -> customHome?.let { "$it/logs" } ?: "build/test-data/logs"
            else -> platformLogPath()
        }
    }

    private fun platformLogPath(): String {
        val osName = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")

        return when {
            osName.contains("mac") -> "$userHome/Library/Logs/Gromozeka"
            osName.contains("windows") -> "$userHome/AppData/Local/Gromozeka/logs"
            osName.contains("linux") -> "$userHome/.local/share/Gromozeka/logs"
            else -> "logs"
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
        System.setProperty("GROMOZEKA_HOME", settingsService.gromozekaHome.absolutePath)
    }
}
