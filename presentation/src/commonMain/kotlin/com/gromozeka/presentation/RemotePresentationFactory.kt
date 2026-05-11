package com.gromozeka.presentation

import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.presentation.services.ClientAudioRecorder
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.NoOpGlobalHotkeyController
import com.gromozeka.presentation.services.NoOpClientAudioRecorder
import com.gromozeka.presentation.services.NoOpSoundNotificationPlayer
import com.gromozeka.presentation.services.NoOpTtsQueue
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.presentation.services.RemotePttController
import com.gromozeka.presentation.services.ScreenCaptureController
import com.gromozeka.presentation.services.TabPromptService
import com.gromozeka.presentation.services.UIStateService
import com.gromozeka.presentation.services.UIStateStore
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import kotlinx.coroutines.CoroutineScope

suspend fun createRemoteAppComponents(
    remoteUrl: String,
    scope: CoroutineScope,
    clientHomeDirectory: String,
    uiStateStore: UIStateStore = InMemoryUIStateStore(),
    audioRecorder: ClientAudioRecorder = NoOpClientAudioRecorder,
): RemoteAppComponents {
    val remoteServices = GromozekaRemoteServices(
        url = remoteUrl,
        scope = scope,
        clientHomeDirectory = clientHomeDirectory,
    )

    remoteServices.initialize()

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

    val uiStateService = UIStateService(scope, uiStateStore)
    uiStateService.initialize(appViewModel)

    val translationService = TranslationService().also { it.init(remoteServices.settingsService) }
    val themeService = ThemeService().also { it.init(remoteServices.settingsService) }
    val pttController = RemotePttController(
        appViewModel = appViewModel,
        audioRecorder = audioRecorder,
        audioTranscriptionService = remoteServices.audioTranscriptionService,
        scope = scope
    )

    return RemoteAppComponents(
        components = AppComponents(
            appViewModel = appViewModel,
            ttsQueueService = NoOpTtsQueue(),
            settingsService = remoteServices.settingsService,
            globalHotkeyController = NoOpGlobalHotkeyController,
            pttEventRouter = pttController,
            pttService = pttController,
            uiStateService = uiStateService,
            translationService = translationService,
            themeService = themeService,
            aiThemeGenerator = AIThemeGenerator(),
            logEncryptor = LogEncryptor(),
            ollamaModelService = OllamaModelService(),
            projectService = remoteServices.projectService,
            conversationService = remoteServices.conversationService,
            conversationSearchViewModel = ConversationSearchViewModel(remoteServices.conversationNameSearchService, scope),
            loadingViewModel = LoadingViewModel(),
            tabPromptService = TabPromptService(remoteServices.promptService),
            agentService = remoteServices.agentService,
            promptService = remoteServices.promptService,
        ),
        remoteServices = remoteServices,
    )
}

class RemoteAppComponents(
    val components: AppComponents,
    private val remoteServices: GromozekaRemoteServices,
) : AutoCloseable {
    override fun close() {
        runCatching { components.uiStateService.forceSave() }
        runCatching { components.uiStateService.disableAutoSave() }
        runCatching { components.globalHotkeyController.cleanup() }
        runCatching { components.ttsQueueService.shutdown() }
        runCatching { remoteServices.close() }
    }
}
