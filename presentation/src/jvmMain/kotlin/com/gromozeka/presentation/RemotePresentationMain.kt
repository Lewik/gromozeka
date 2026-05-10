package com.gromozeka.presentation

import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import com.gromozeka.infrastructure.ai.platform.NoOpGlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.service.OllamaModelService
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.NoOpPttEventHandler
import com.gromozeka.presentation.services.NoOpPttRecordingService
import com.gromozeka.presentation.services.NoOpSoundNotificationPlayer
import com.gromozeka.presentation.services.NoOpTtsQueue
import com.gromozeka.presentation.services.UIStateService
import com.gromozeka.presentation.services.WindowStateService
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun startRemotePresentation(remoteUrl: String): RemoteStartedApp {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val clientHomeDirectory = System.getProperty("GROMOZEKA_CLIENT_HOME")
        ?: File(System.getProperty("user.home"), ".gromozeka-remote-client").absolutePath
    val remoteServices = GromozekaRemoteServices(
        url = remoteUrl,
        scope = scope,
        clientHomeDirectory = clientHomeDirectory,
    )

    runBlocking {
        remoteServices.initialize()
    }

    File(remoteServices.settingsService.homeDirectory).mkdirs()
    System.setProperty("GROMOZEKA_HOME", remoteServices.settingsService.homeDirectory)

    val screenCaptureController = object : ScreenCaptureController {
        override suspend fun captureWindow(): String? = null
        override suspend fun captureFullScreen(): String? = null
        override suspend fun captureArea(): String? = null
    }

    val appViewModel = AppViewModel(
        conversationRuntimeService = remoteServices.conversationRuntimeService,
        conversationService = remoteServices.conversationService,
        messageSquashGenerationService = remoteServices.messageSquashGenerationService,
        soundNotificationService = NoOpSoundNotificationPlayer,
        settingsService = remoteServices.settingsService,
        scope = scope,
        screenCaptureController = screenCaptureController,
        defaultAgentProvider = remoteServices.defaultAgentProvider,
        tokenStatsService = remoteServices.conversationTokenStatsService,
    )

    val conversationSearchViewModel = ConversationSearchViewModel(remoteServices.conversationNameSearchService, scope)

    val translationService = TranslationService().also { it.init(remoteServices.settingsService) }
    val themeService = ThemeService().also { it.init(remoteServices.settingsService) }
    val windowStateService = WindowStateService(remoteServices.settingsService)
    val uiStateService = UIStateService(remoteServices.settingsService, scope)
    val aiThemeGenerator = AIThemeGenerator(screenCaptureController, remoteServices.settingsService)
    val logEncryptor = LogEncryptor(remoteServices.settingsService)

    runBlocking {
        uiStateService.initialize(appViewModel)
    }

    val components = AppComponents(
        appViewModel = appViewModel,
        ttsQueueService = NoOpTtsQueue(),
        settingsService = remoteServices.settingsService,
        globalHotkeyController = NoOpGlobalHotkeyController(),
        pttEventRouter = NoOpPttEventHandler,
        pttService = NoOpPttRecordingService(),
        windowStateService = windowStateService,
        uiStateService = uiStateService,
        translationService = translationService,
        themeService = themeService,
        aiThemeGenerator = aiThemeGenerator,
        logEncryptor = logEncryptor,
        ollamaModelService = OllamaModelService(),
        projectService = remoteServices.projectService,
        conversationService = remoteServices.conversationService,
        conversationSearchViewModel = conversationSearchViewModel,
        loadingViewModel = LoadingViewModel(McpConfigurationService(remoteServices.settingsService.homeDirectory, scope)),
        tabPromptService = com.gromozeka.application.service.TabPromptService(),
        agentService = remoteServices.agentService,
        promptService = remoteServices.promptService,
    )

    return RemoteStartedApp(components, remoteServices, scope)
}

internal class RemoteStartedApp(
    val components: AppComponents,
    private val remoteServices: GromozekaRemoteServices,
    private val scope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        runCatching { components.uiStateService.forceSave() }
        runCatching { components.uiStateService.disableAutoSave() }
        runCatching { runBlocking { components.appViewModel.cleanup() } }
        runCatching { components.globalHotkeyController.cleanup() }
        runCatching { components.ttsQueueService.shutdown() }
        runCatching { remoteServices.close() }
        scope.cancel()
    }
}
