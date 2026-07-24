package com.gromozeka.presentation

import com.gromozeka.client.RemoteClientSettingsService
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.client.RemoteClientPresentationService
import com.gromozeka.client.RemoteLiveInterpreterService
import com.gromozeka.client.RemoteMemoryActionItemService
import com.gromozeka.device.telemetry.DeviceLocationService
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
import com.gromozeka.presentation.services.GlobalHotkeyController
import com.gromozeka.presentation.services.ClientLiveAudioStreamer
import com.gromozeka.presentation.services.ClientSideSpeechToTextService
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttRecordingService
import com.gromozeka.presentation.services.TabPromptService
import com.gromozeka.presentation.services.TtsQueue
import com.gromozeka.presentation.services.UIStateService
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import kotlinx.coroutines.flow.StateFlow

data class AppComponents(
    val appViewModel: AppViewModel,
    val ttsQueueService: TtsQueue,
    val settingsService: com.gromozeka.domain.service.SettingsService,
    val remoteClientSettingsService: RemoteClientSettingsService,
    val remoteConnectionState: StateFlow<RemoteConnectionState>,
    val clientPresentationService: RemoteClientPresentationService,
    val memoryActionItemService: RemoteMemoryActionItemService,
    val liveInterpreterService: RemoteLiveInterpreterService,
    val clientSideSpeechToTextService: ClientSideSpeechToTextService,
    val liveAudioStreamer: ClientLiveAudioStreamer,
    val globalHotkeyController: GlobalHotkeyController,
    val pttEventRouter: PttEventHandler,
    val pttService: PttRecordingService,
    val uiStateService: UIStateService,
    val translationService: TranslationService,
    val themeService: ThemeService,
    val aiThemeGenerator: AIThemeGenerator,
    val logEncryptor: LogEncryptor,
    val ollamaModelService: OllamaModelService,
    val projectService: ProjectDomainService,
    val workspaceCatalogService: WorkspaceCatalogService,
    val workspaceManagementService: WorkspaceManagementService,
    val conversationService: ConversationDomainService,
    val conversationSearchViewModel: ConversationSearchViewModel,
    val loadingViewModel: LoadingViewModel,
    val tabPromptService: TabPromptService,
    val agentService: AgentDomainService,
    val agentSkillService: AgentSkillDomainService,
    val promptService: PromptDomainService,
    val deviceLocationService: DeviceLocationService,
)
