package com.gromozeka.presentation

import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.client.InMemoryRemoteClientSettingsStore
import com.gromozeka.client.RemoteClientSettingsStore
import com.gromozeka.device.telemetry.DeviceLocationService
import com.gromozeka.device.telemetry.NoOpDeviceLocationService
import com.gromozeka.presentation.services.ClientAudioPlayer
import com.gromozeka.presentation.services.ClientAudioRecorder
import com.gromozeka.presentation.services.ClientSideSpeechToTextService
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.NoOpGlobalHotkeyController
import com.gromozeka.presentation.services.NoOpClientAudioPlayer
import com.gromozeka.presentation.services.NoOpClientAudioRecorder
import com.gromozeka.presentation.services.NoOpClientSideSpeechToTextService
import com.gromozeka.presentation.services.NoOpSoundNotificationPlayer
import com.gromozeka.presentation.services.NoOpSystemAudioMuteService
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.presentation.services.RemotePttController
import com.gromozeka.presentation.services.RemoteTtsQueue
import com.gromozeka.presentation.services.RollingClientLiveAudioStreamer
import com.gromozeka.presentation.services.ScreenCaptureController
import com.gromozeka.presentation.services.TabPromptService
import com.gromozeka.presentation.services.TTSAutoplayService
import com.gromozeka.presentation.services.UIStateService
import com.gromozeka.presentation.services.UIStateStore
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.SystemAudioMuteService
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.CoroutineScope

suspend fun createRemoteAppComponents(
    remoteUrl: String,
    scope: CoroutineScope,
    clientHomeDirectory: String,
    uiStateStore: UIStateStore = InMemoryUIStateStore(),
    remoteClientSettingsStore: RemoteClientSettingsStore = InMemoryRemoteClientSettingsStore(),
    audioRecorder: ClientAudioRecorder = NoOpClientAudioRecorder,
    audioPlayer: ClientAudioPlayer = NoOpClientAudioPlayer,
    systemAudioMuteService: SystemAudioMuteService = NoOpSystemAudioMuteService,
    clientSideSpeechToTextServiceFactory: (SettingsService) -> ClientSideSpeechToTextService = {
        NoOpClientSideSpeechToTextService
    },
    deviceLocationService: DeviceLocationService = NoOpDeviceLocationService,
): RemoteAppComponents {
    val remoteServices = GromozekaRemoteServices(
        url = remoteUrl,
        scope = scope,
        clientHomeDirectory = clientHomeDirectory,
        clientSettingsStore = remoteClientSettingsStore,
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
    val clientSideSpeechToTextService = clientSideSpeechToTextServiceFactory(remoteServices.settingsService)
    val ttsQueue = RemoteTtsQueue(remoteServices.speechSynthesisService, audioPlayer)
    val pttController = RemotePttController(
        appViewModel = appViewModel,
        audioRecorder = audioRecorder,
        audioTranscriptionService = remoteServices.audioTranscriptionService,
        clientSideSpeechToTextService = clientSideSpeechToTextService,
        ttsQueue = ttsQueue,
        systemAudioMuteService = systemAudioMuteService,
        settingsService = remoteServices.settingsService,
        scope = scope
    )
    val ttsAutoplayService = TTSAutoplayService(
        appViewModel = appViewModel,
        ttsQueueService = ttsQueue,
        settingsService = remoteServices.settingsService,
        scope = scope,
    )
    ttsAutoplayService.start()

    return RemoteAppComponents(
        components = AppComponents(
            appViewModel = appViewModel,
            ttsQueueService = ttsQueue,
            settingsService = remoteServices.settingsService,
            remoteClientSettingsService = remoteServices.clientSettingsService,
            remoteConnectionState = remoteServices.connectionState,
            memoryActionItemService = remoteServices.memoryActionItemService,
            liveInterpreterService = remoteServices.liveInterpreterService,
            clientSideSpeechToTextService = clientSideSpeechToTextService,
            liveAudioStreamer = RollingClientLiveAudioStreamer(audioRecorder) {
                remoteServices.settingsService.userProfile.speechSettings.speechToText.localWhisper.liveStreaming
            },
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
            workspaceCatalogService = remoteServices.workspaceCatalogService,
            conversationService = remoteServices.conversationService,
            conversationSearchViewModel = ConversationSearchViewModel(remoteServices.conversationNameSearchService, scope),
            loadingViewModel = LoadingViewModel(),
            tabPromptService = TabPromptService(remoteServices.promptService),
            agentService = remoteServices.agentService,
            promptService = remoteServices.promptService,
            deviceLocationService = deviceLocationService,
        ),
        remoteServices = remoteServices,
        ttsAutoplayService = ttsAutoplayService,
    )
}

class RemoteAppComponents(
    val components: AppComponents,
    private val remoteServices: GromozekaRemoteServices,
    private val ttsAutoplayService: TTSAutoplayService,
) : AutoCloseable {
    override fun close() {
        runCatching { components.uiStateService.forceSave() }
        runCatching { components.uiStateService.disableAutoSave() }
        runCatching { components.globalHotkeyController.cleanup() }
        runCatching { ttsAutoplayService.shutdown() }
        runCatching { components.ttsQueueService.shutdown() }
        runCatching { remoteServices.close() }
    }
}
