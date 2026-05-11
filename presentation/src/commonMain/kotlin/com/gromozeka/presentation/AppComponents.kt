package com.gromozeka.presentation

import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.presentation.services.GlobalHotkeyController
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

data class AppComponents(
    val appViewModel: AppViewModel,
    val ttsQueueService: TtsQueue,
    val settingsService: com.gromozeka.domain.service.SettingsService,
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
    val conversationService: ConversationDomainService,
    val conversationSearchViewModel: ConversationSearchViewModel,
    val loadingViewModel: LoadingViewModel,
    val tabPromptService: TabPromptService,
    val agentService: AgentDomainService,
    val promptService: PromptDomainService,
)
