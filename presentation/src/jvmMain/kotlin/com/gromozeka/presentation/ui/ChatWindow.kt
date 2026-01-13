package com.gromozeka.presentation.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.gromozeka.infrastructure.ai.platform.GlobalHotkeyController
import com.gromozeka.presentation.services.*
import com.gromozeka.infrastructure.ai.service.OllamaModelService
import com.gromozeka.application.service.TabPromptService
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.domain.model.AppMode
import com.gromozeka.presentation.model.Settings
import com.gromozeka.presentation.ui.session.SessionScreen
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.infrastructure.ai.oauth.OAuthService
import com.gromozeka.presentation.ui.agents.AgentConstructorScreen
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    appViewModel: AppViewModel,
    ttsQueueService: TTSQueueService,
    settingsService: SettingsService,
    globalHotkeyController: GlobalHotkeyController,
    pttEventRouter: PTTEventRouter,
    pttService: PTTService,
    windowStateService: WindowStateService,
    uiStateService: UIStateService,
    translationService: TranslationService,
    themeService: ThemeService,
    aiThemeGenerator: AIThemeGenerator,
    logEncryptor: LogEncryptor,
    ollamaModelService: OllamaModelService,
    OAuthService: OAuthService,
    oauthConfigService: com.gromozeka.infrastructure.ai.oauth.OAuthConfigService,
    projectService: ProjectDomainService,
    conversationTreeService: ConversationDomainService,
    conversationSearchViewModel: com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel,
    loadingViewModel: com.gromozeka.presentation.ui.viewmodel.LoadingViewModel,
    tabPromptService: TabPromptService,
    agentService: AgentDomainService,
    promptService: PromptDomainService,
) {
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()

    var initialized by remember { mutableStateOf(false) }
    var isLoadingComplete by remember { mutableStateOf(false) }

    val currentSettings by settingsService.settingsFlow.collectAsState()

    val translation = LocalTranslation.current


    val tabs by appViewModel.tabs.collectAsState()
    val currentTabIndex by appViewModel.currentTabIndex.collectAsState()
    val currentTab by appViewModel.currentTab.collectAsState()
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showPromptsPanel by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameTabIndex by remember { mutableStateOf(-1) }
    var renameCurrentName by remember { mutableStateOf("") }

    var hoveredTabIndex by remember { mutableStateOf(-1) }


    LaunchedEffect(Unit) {
        initialized = true
    }


    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                appViewModel.cleanup()
            }
        }
    }

    val createNewSession: (String) -> Unit = { projectPath ->
        coroutineScope.launch {
            try {
                val tabIndex = appViewModel.createTab(
                    projectPath = projectPath,
                    initiator = ConversationInitiator.User
                )
                appViewModel.selectTab(tabIndex)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val createNewSessionWithMessage: (String, String) -> Unit =
        { projectPath, initialMessage ->
            coroutineScope.launch {
                try {
                    val chatMessage = Conversation.Message(
                        id = Conversation.Message.Id(uuid7()),
                        conversationId = Conversation.Id(""),
                        role = Conversation.Message.Role.USER,
                        content = listOf(Conversation.Message.ContentItem.UserMessage(initialMessage)),
                        createdAt = Clock.System.now(),
                        instructions = listOf(Conversation.Message.Instruction.Source.User)
                    )
                    val tabIndex = appViewModel.createTab(
                        projectPath = projectPath,
                        agent = null,
                        initialMessage = chatMessage,
                        initiator = ConversationInitiator.User
                    )
                    appViewModel.selectTab(tabIndex)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    val isRecording by pttService.recordingState.collectAsState()

    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        settingsService.saveSettings(newSettings)
    }

    val modifierWithPushToTalk = Modifier.advancedPttGestures(pttEventRouter, coroutineScope)

    val keyboardPttGestureDetector = remember {
        UnifiedGestureDetector(pttEventRouter, coroutineScope)
    }

    val savedWindowState = remember { windowStateService.loadWindowState() }

    val windowState = rememberWindowState(
        position = if (savedWindowState.x != -1 && savedWindowState.y != -1) {
            WindowPosition(
                savedWindowState.x.dp,
                savedWindowState.y.dp
            )
        } else WindowPosition.PlatformDefault,
        size = DpSize(
            savedWindowState.width.dp,
            savedWindowState.height.dp
        )
    )


    Window(
        state = windowState,
        alwaysOnTop = currentSettings.alwaysOnTop,
        onCloseRequest = {
            log.info("Application window closing - stopping all sessions...")

            uiStateService.forceSave()

            uiStateService.disableAutoSave()

            coroutineScope.launch {
                try {
                    appViewModel.cleanup()
                    log.info("All sessions stopped and UI state saved")
                } catch (e: Exception) {
                    log.warn(e) { "Error during cleanup: ${e.message}" }
                }
            }

            val newWindowState = UiWindowState(
                x = windowState.position.x.value.toInt(),
                y = windowState.position.y.value.toInt(),
                width = windowState.size.width.value.toInt(),
                height = windowState.size.height.value.toInt(),
                isMaximized = windowState.placement == WindowPlacement.Maximized
            )
            windowStateService.saveWindowState(newWindowState)

            globalHotkeyController.cleanup()
            ttsQueueService.shutdown()
            log.info("Global MCP HTTP server stopped")
            exitApplication()
        },
        title = buildString {
            append(translation.appName)
            if (currentSettings.alwaysOnTop) {
                append(translation.alwaysOnTopSuffix)
            }
            if (settingsService.mode == AppMode.DEV) {
                append(translation.devModeSuffix)
            }
        },
        icon = painterResource("logos/logo-256x256.png")
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = settingsService.settings.uiScale,
                fontScale = settingsService.settings.fontScale,
            ),
        )
        {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .focusTarget()
                    .advancedEscape(pttEventRouter)
                    .onPreviewKeyEvent { event ->

                        when {
                            event.key == Key.T &&
                                    event.isMetaPressed &&
                                    event.type == KeyEventType.KeyDown -> {
                                if (currentTab != null) {
                                    createNewSession(currentTab!!.projectPath)
                                }
                                true
                            }

                            event.utf16CodePoint == 167 -> {
                                when (event.type) {
                                    KeyEventType.KeyDown -> {
                                        coroutineScope.launch {
                                            keyboardPttGestureDetector.onGestureDown()
                                        }
                                    }

                                    KeyEventType.KeyUp -> {
                                        coroutineScope.launch {
                                            keyboardPttGestureDetector.onGestureUp()
                                        }
                                    }
                                }
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                if (initialized) {
                    if (!isLoadingComplete) {
                        LoadingScreen(
                            loadingViewModel = loadingViewModel,
                            onComplete = { isLoadingComplete = true }
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.weight(1f)) {
                                val currentIndex = currentTabIndex
                                val selectedTabIndex = when (currentIndex) {
                                    null -> 0  // Projects tab
                                    -1 -> 1    // Agents tab
                                    else -> currentIndex + 2  // Session tabs (now start at index 2)
                                }
                                val tabRowComponent = @Composable {
                                    CustomTabRow(
                                        selectedTabIndex = selectedTabIndex,
                                        showTabsAtBottom = currentSettings.showTabsAtBottom,
                                        tabs = tabs,
                                        hoveredTabIndex = hoveredTabIndex,
                                        onTabSelect = { tabIndex ->
                                            coroutineScope.launch {
                                                appViewModel.selectTab(tabIndex)
                                            }
                                        },
                                        onTabHover = { index -> hoveredTabIndex = index },
                                        onTabHoverExit = { hoveredTabIndex = -1 },
                                        onRenameTab = { tabIndexToRename, newName ->
                                            coroutineScope.launch {
                                                appViewModel.renameTab(tabIndexToRename, newName)
                                            }
                                        },
                                        coroutineScope = coroutineScope
                                    )
                                }

                                if (!currentSettings.showTabsAtBottom) {
                                    tabRowComponent()
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.weight(1f)) {
                                        Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                if (currentTab != null) {
                                                    currentTab?.let { tabViewModel ->
                                                        val tabUiState by tabViewModel.uiState.collectAsState()

                                                        SessionScreen(
                                                            viewModel = tabViewModel,

                                                            onNewSession = {
                                                                createNewSession(currentTab!!.projectPath)
                                                            },
                                                            onForkSession = {
                                                                coroutineScope.launch {
                                                                    try {
                                                                        val currentConversationId =
                                                                            currentTab!!.uiState.first().conversationId
                                                                        val forkedConversation =
                                                                            conversationTreeService.fork(
                                                                                currentConversationId
                                                                            )

                                                                        val tabIndex = appViewModel.createTab(
                                                                            projectPath = currentTab!!.projectPath,
                                                                            conversationId = forkedConversation.id,
                                                                            initiator = ConversationInitiator.User
                                                                        )
                                                                        appViewModel.selectTab(tabIndex)
                                                                    } catch (e: Exception) {
                                                                        log.warn(e) { "Failed to fork conversation: ${e.message}" }
                                                                        e.printStackTrace()
                                                                    }
                                                                }
                                                            },
                                                            onRestartSession = {
                                                                coroutineScope.launch {
                                                                    val projectPath = currentTab!!.projectPath
                                                                    val oldIndex = currentTabIndex!!

                                                                    appViewModel.createTab(
                                                                        projectPath = projectPath,
                                                                        initiator = ConversationInitiator.User
                                                                    )

                                                                    appViewModel.closeTab(oldIndex)
                                                                }
                                                            },

                                                            onCloseTab = {
                                                                coroutineScope.launch {
                                                                    currentTabIndex?.let { index ->
                                                                        appViewModel.closeTab(index)
                                                                    }
                                                                }
                                                            },

                                                            ttsQueueService = ttsQueueService,
                                                            coroutineScope = coroutineScope,
                                                            modifierWithPushToTalk = modifierWithPushToTalk,
                                                            isRecording = isRecording,

                                                            settings = currentSettings,
                                                            showSettingsPanel = showSettingsPanel,
                                                            onShowSettingsPanelChange = { showSettingsPanel = it },

                                                            onRememberThread = if (currentSettings.vectorStorageEnabled) {
                                                                {
                                                                    coroutineScope.launch {
                                                                        try {
                                                                            appViewModel.rememberCurrentThread()
                                                                            log.info("Remember thread triggered")
                                                                        } catch (e: Exception) {
                                                                            log.warn(e) { "Remember thread failed: ${e.message}" }
                                                                        }
                                                                    }
                                                                }
                                                            } else null,

                                                            onAddToGraph = if (currentSettings.graphStorageEnabled) {
                                                                {
                                                                    coroutineScope.launch {
                                                                        try {
                                                                            appViewModel.addToGraphCurrentThread()
                                                                            log.info("Add to graph triggered")
                                                                        } catch (e: Exception) {
                                                                            log.warn(e) { "Add to graph failed: ${e.message}" }
                                                                        }
                                                                    }
                                                                }
                                                            } else null,

                                                            onIndexDomain = if (currentSettings.graphStorageEnabled) {
                                                                {
                                                                    coroutineScope.launch {
                                                                        try {
                                                                            appViewModel.indexDomainToGraph()
                                                                            log.info("Index domain triggered")
                                                                        } catch (e: Exception) {
                                                                            log.warn(e) { "Index domain failed: ${e.message}" }
                                                                        }
                                                                    }
                                                                }
                                                            } else null,

                                                            onShowPromptsPanelChange = { showPromptsPanel = it },

                                                            isDev = settingsService.mode == AppMode.DEV,
                                                        )
                                                    }
                                                } else {
                                                    Box(modifier = Modifier.padding(16.dp)) {
                                                        when (selectedTabIndex) {
                                                            1 -> {
                                                                // Agents tab
                                                                AgentConstructorScreen(
                                                                    agentService = agentService,
                                                                    promptService = promptService,
                                                                    coroutineScope = coroutineScope,
                                                                    projectPath = currentTab?.uiState?.value?.projectPath
                                                                )
                                                            }

                                                            else -> {
                                                                // Projects tab (index 0)
                                                                SessionListScreen(
                                                                    onConversationSelected = { _, _ ->
                                                                        refreshTrigger++
                                                                    },
                                                                    coroutineScope = coroutineScope,
                                                                    onNewSession = createNewSession,
                                                                    projectService = projectService,
                                                                    conversationTreeService = conversationTreeService,
                                                                    appViewModel = appViewModel,
                                                                    searchViewModel = conversationSearchViewModel,
                                                                    showSettingsPanel = showSettingsPanel,
                                                                    onShowSettingsPanelChange = {
                                                                        showSettingsPanel = it
                                                                    },
                                                                    refreshTrigger = refreshTrigger
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Agent Panel (tab-specific, edge-to-edge under tabs)
                                        currentTab?.let { tabViewModel ->
                                            val tabUiState by tabViewModel.uiState.collectAsState()
                                            val tokenStats by tabViewModel.tokenStats.collectAsState()
                                            AgentPanel(
                                                projectPath = tabUiState.projectPath,
                                                isVisible = showPromptsPanel,
                                                currentAgent = tabUiState.agent,
                                                onAgentChange = { tabViewModel.updateAgent(it) },
                                                onClose = { showPromptsPanel = false },
                                                agentService = agentService,
                                                coroutineScope = coroutineScope,
                                                tokenStats = tokenStats
                                            )
                                        }
                                    }
                                }

                                if (currentSettings.showTabsAtBottom) {
                                    tabRowComponent()
                                }
                            }

                            SettingsPanel(
                                isVisible = showSettingsPanel,
                                settings = currentSettings,
                                onSettingsChange = onSettingsChange,
                                onClose = { showSettingsPanel = false },
                                translationService = translationService,
                                themeService = themeService,
                                aiThemeGenerator = aiThemeGenerator,
                                logEncryptor = logEncryptor,
                                settingsService = settingsService,
                                ollamaModelService = ollamaModelService,
                                oAuthService = OAuthService,
                                oauthConfigService = oauthConfigService,
                                coroutineScope = coroutineScope,
                                onOpenTab = createNewSession,
                                onOpenTabWithMessage = createNewSessionWithMessage
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

    }
}
