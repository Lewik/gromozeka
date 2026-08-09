package com.gromozeka.presentation

import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.client.InMemoryRemoteClientSettingsStore
import com.gromozeka.client.RemoteClientSettingsStore
import com.gromozeka.device.telemetry.DeviceLocationService
import com.gromozeka.device.telemetry.NoOpDeviceLocationService
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.presentation.services.AssistantAudioPresentationService
import com.gromozeka.presentation.services.ClientAudioPlayer
import com.gromozeka.presentation.services.ClientAudioRecorder
import com.gromozeka.presentation.services.ClientSideSpeechToTextService
import com.gromozeka.presentation.services.ClientFeedbackService
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.LocalWorkerController
import com.gromozeka.presentation.services.NoOpGlobalHotkeyController
import com.gromozeka.presentation.services.NoOpClientAudioPlayer
import com.gromozeka.presentation.services.NoOpClientAudioRecorder
import com.gromozeka.presentation.services.NoOpClientSideSpeechToTextService
import com.gromozeka.presentation.services.NoOpSystemAudioMuteService
import com.gromozeka.presentation.services.UnsupportedLocalWorkerController
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.presentation.services.RemotePttController
import com.gromozeka.presentation.services.ResourceSoundNotificationPlayer
import com.gromozeka.presentation.services.RemoteTtsQueue
import com.gromozeka.presentation.services.RollingClientLiveAudioStreamer
import com.gromozeka.presentation.services.AttachmentAcquisitionController
import com.gromozeka.presentation.services.AttachmentAcquisitionEvent
import com.gromozeka.presentation.services.NoOpAttachmentAcquisitionController
import com.gromozeka.presentation.services.TabPromptService
import com.gromozeka.presentation.services.UIStateService
import com.gromozeka.presentation.services.UIStateStore
import com.gromozeka.presentation.services.UiFeedbackController
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.SystemAudioMuteService
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.remote.protocol.AuthenticatedUserView
import com.gromozeka.remote.protocol.RemoteClientPlatform
import com.gromozeka.domain.service.SettingsService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

suspend fun createRemoteAppComponents(
    remoteUrl: String,
    authenticatedUser: AuthenticatedUserView,
    scope: CoroutineScope,
    clientHomeDirectory: String,
    clientPlatform: ClientPlatform,
    uiStateStore: UIStateStore = InMemoryUIStateStore(),
    remoteClientSettingsStore: RemoteClientSettingsStore = InMemoryRemoteClientSettingsStore(),
    audioRecorder: ClientAudioRecorder = NoOpClientAudioRecorder,
    audioPlayer: ClientAudioPlayer = NoOpClientAudioPlayer,
    systemAudioMuteService: SystemAudioMuteService = NoOpSystemAudioMuteService,
    clientSideSpeechToTextServiceFactory: (SettingsService) -> ClientSideSpeechToTextService = {
        NoOpClientSideSpeechToTextService
    },
    deviceLocationService: DeviceLocationService = NoOpDeviceLocationService,
    attachmentAcquisitionController: AttachmentAcquisitionController = NoOpAttachmentAcquisitionController,
    localWorkerController: LocalWorkerController = UnsupportedLocalWorkerController,
    httpClient: HttpClient? = null,
): RemoteAppComponents {
    val remoteServices = GromozekaRemoteServices(
        url = remoteUrl,
        scope = scope,
        clientHomeDirectory = clientHomeDirectory,
        clientPlatform = clientPlatform.toRemoteClientPlatform(),
        clientSettingsStore = remoteClientSettingsStore,
        httpClient = httpClient,
    )

    try {
        remoteServices.initialize()
    } catch (error: Throwable) {
        remoteServices.close()
        throw error
    }

    val uiFeedbackController = UiFeedbackController()
    val ttsQueue = RemoteTtsQueue(remoteServices.speechSynthesisService, audioPlayer)
    val soundNotificationPlayer = ResourceSoundNotificationPlayer(
        audioPlayer = audioPlayer,
        settingsService = remoteServices.settingsService,
        isTtsPlaying = { ttsQueue.isPlaying.value },
    )
    val messageInputClientPlatform = clientPlatform.toMessageInputClientPlatform()

    val appViewModel = AppViewModel(
        conversationRuntimeService = remoteServices.conversationRuntimeService,
        conversationService = remoteServices.conversationService,
        messageSquashGenerationService = remoteServices.messageSquashGenerationService,
        settingsService = remoteServices.settingsService,
        aiConfigurationProvider = remoteServices.aiConfigurationService,
        scope = scope,
        attachmentAcquisitionController = attachmentAcquisitionController,
        artifactTransferService = remoteServices.artifactTransferService,
        defaultAgentProvider = remoteServices.defaultAgentProvider,
        agentService = remoteServices.agentService,
        tokenStatsService = remoteServices.conversationTokenStatsService,
        conversationTabLayoutService = remoteServices.conversationTabLayoutService,
        messageInputClientPlatform = messageInputClientPlatform,
    )
    val externalAttachmentJob = scope.launch {
        attachmentAcquisitionController.externalEvents.collect { event ->
            when (event) {
                is AttachmentAcquisitionEvent.Acquired ->
                    appViewModel.currentTab.value?.addAttachments(event.uploads)

                is AttachmentAcquisitionEvent.Failed ->
                    appViewModel.currentTab.value?.reportAttachmentError(event.message)
            }
        }
    }

    val uiStateService = UIStateService(scope, remoteServices.conversationTabLayoutService, uiStateStore)
    uiStateService.initialize(appViewModel)

    val translationService = TranslationService().also { it.init(remoteServices.settingsService) }
    val themeService = ThemeService().also { it.init(remoteServices.settingsService) }
    val clientSideSpeechToTextService = clientSideSpeechToTextServiceFactory(remoteServices.settingsService)
    val pttController = RemotePttController(
        appViewModel = appViewModel,
        audioRecorder = audioRecorder,
        audioTranscriptionService = remoteServices.audioTranscriptionService,
        clientSideSpeechToTextService = clientSideSpeechToTextService,
        ttsQueue = ttsQueue,
        systemAudioMuteService = systemAudioMuteService,
        settingsService = remoteServices.settingsService,
        uiFeedbackController = uiFeedbackController,
        messageInputClientPlatform = messageInputClientPlatform,
        scope = scope
    )
    val assistantAudioPresentationService = AssistantAudioPresentationService(
        clientPresentationService = remoteServices.clientPresentationService,
        ttsQueueService = ttsQueue,
        settingsService = remoteServices.settingsService,
        soundNotificationPlayer = soundNotificationPlayer,
        pttState = pttController.state,
        scope = scope,
    )
    assistantAudioPresentationService.start()
    val clientFeedbackService = ClientFeedbackService(
        clientPresentationService = remoteServices.clientPresentationService,
        soundNotificationPlayer = soundNotificationPlayer,
        uiFeedbackController = uiFeedbackController,
        activeConversationId = { appViewModel.currentTab.value?.conversationId },
        scope = scope,
    )
    clientFeedbackService.start()

    return RemoteAppComponents(
        components = AppComponents(
            authenticatedUser = authenticatedUser,
            appViewModel = appViewModel,
            ttsQueueService = ttsQueue,
            settingsService = remoteServices.settingsService,
            aiConfigurationService = remoteServices.aiConfigurationService,
            runtimeCatalogTemplateService = remoteServices.runtimeCatalogTemplateService,
            remoteClientSettingsService = remoteServices.clientSettingsService,
            remoteConnectionState = remoteServices.connectionState,
            clientPresentationService = remoteServices.clientPresentationService,
            distributionService = remoteServices.distributionService,
            deviceConnectionService = remoteServices.deviceConnectionService,
            memoryActionItemService = remoteServices.memoryActionItemService,
            mcpServerService = remoteServices.mcpServerService,
            personalAccessTokenService = remoteServices.personalAccessTokenService,
            aiUserCredentialService = remoteServices.aiUserCredentialService,
            userAdministrationService = remoteServices.userAdministrationService,
            securityAuditService = remoteServices.securityAuditService,
            userDirectoryService = remoteServices.userDirectoryService,
            projectMembershipService = remoteServices.projectMembershipService,
            liveInterpreterService = remoteServices.liveInterpreterService,
            clientSideSpeechToTextService = clientSideSpeechToTextService,
            liveAudioStreamer = RollingClientLiveAudioStreamer(audioRecorder) {
                remoteServices.settingsService.userProfile.speechSettings.speechToText.localWhisper.liveStreaming
            },
            globalHotkeyController = NoOpGlobalHotkeyController,
            pttEventRouter = pttController,
            pttService = pttController,
            uiFeedbackController = uiFeedbackController,
            uiStateService = uiStateService,
            translationService = translationService,
            themeService = themeService,
            aiThemeGenerator = AIThemeGenerator(),
            logEncryptor = LogEncryptor(),
            localWorkerController = localWorkerController,
            ollamaModelService = OllamaModelService(),
            projectService = remoteServices.projectService,
            workspaceCatalogService = remoteServices.workspaceCatalogService,
            workspaceManagementService = remoteServices.workspaceManagementService,
            workerCatalogService = remoteServices.workerCatalogService,
            conversationService = remoteServices.conversationService,
            conversationSearchViewModel = ConversationSearchViewModel(remoteServices.conversationNameSearchService, scope),
            loadingViewModel = LoadingViewModel(),
            tabPromptService = TabPromptService(remoteServices.promptService),
            agentService = remoteServices.agentService,
            agentSkillService = remoteServices.agentSkillService,
            promptService = remoteServices.promptService,
            deviceLocationService = deviceLocationService,
        ),
        remoteServices = remoteServices,
        assistantAudioPresentationService = assistantAudioPresentationService,
        clientFeedbackService = clientFeedbackService,
        attachmentAcquisitionController = attachmentAcquisitionController,
        externalAttachmentJob = externalAttachmentJob,
    )
}

private fun ClientPlatform.toRemoteClientPlatform(): RemoteClientPlatform =
    when (this) {
        ClientPlatform.DESKTOP -> RemoteClientPlatform.DESKTOP
        ClientPlatform.ANDROID -> RemoteClientPlatform.ANDROID
        ClientPlatform.IOS -> RemoteClientPlatform.IOS
        ClientPlatform.WEB_DESKTOP -> RemoteClientPlatform.WEB_DESKTOP
        ClientPlatform.WEB_TOUCH -> RemoteClientPlatform.WEB_TOUCH
    }

private fun ClientPlatform.toMessageInputClientPlatform(): MessageInputContext.ClientPlatform =
    when (this) {
        ClientPlatform.DESKTOP -> MessageInputContext.ClientPlatform.DESKTOP
        ClientPlatform.ANDROID -> MessageInputContext.ClientPlatform.ANDROID
        ClientPlatform.IOS -> MessageInputContext.ClientPlatform.IOS
        ClientPlatform.WEB_DESKTOP -> MessageInputContext.ClientPlatform.WEB_DESKTOP
        ClientPlatform.WEB_TOUCH -> MessageInputContext.ClientPlatform.WEB_TOUCH
    }

class RemoteAppComponents(
    val components: AppComponents,
    private val remoteServices: GromozekaRemoteServices,
    private val assistantAudioPresentationService: AssistantAudioPresentationService,
    private val clientFeedbackService: ClientFeedbackService,
    private val attachmentAcquisitionController: AttachmentAcquisitionController,
    private val externalAttachmentJob: Job,
) : AutoCloseable {
    override fun close() {
        runCatching { externalAttachmentJob.cancel() }
        runCatching { attachmentAcquisitionController.close() }
        runCatching { components.uiStateService.forceSave() }
        runCatching { components.uiStateService.disableAutoSave() }
        runCatching { components.globalHotkeyController.cleanup() }
        runCatching { assistantAudioPresentationService.shutdown() }
        runCatching { clientFeedbackService.shutdown() }
        runCatching { components.ttsQueueService.shutdown() }
        runCatching { remoteServices.close() }
    }
}
