package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.device.telemetry.DeviceLocationResult
import com.gromozeka.device.telemetry.DeviceLocationSnapshot
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Settings
import com.gromozeka.presentation.AppComponents
import com.gromozeka.presentation.services.UnifiedGestureDetector
import com.gromozeka.presentation.ui.agents.AgentConstructorScreen
import com.gromozeka.presentation.ui.session.ConversationRuntimePanel
import com.gromozeka.presentation.ui.session.SessionScreen
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@Composable
fun GromozekaApp(
    appComponents: AppComponents,
    skipLoadingScreen: Boolean = false,
    uiScaleMultiplier: Float = 1f,
    showRuntimePanelInitially: Boolean = true,
    forceCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
) {
    val currentTheme by appComponents.themeService.currentTheme.collectAsState()
    GromozekaTheme(currentTheme = currentTheme) {
        val currentTranslation by appComponents.translationService.currentTranslation.collectAsState()
        TranslationProvider(currentTranslation) {
            GromozekaAppContent(
                appComponents = appComponents,
                skipLoadingScreen = skipLoadingScreen,
                uiScaleMultiplier = uiScaleMultiplier,
                showRuntimePanelInitially = showRuntimePanelInitially,
                forceCompactLayout = forceCompactLayout,
                clientPlatform = clientPlatform,
            )
        }
    }
}

@Composable
fun GromozekaAppContent(
    appComponents: AppComponents,
    skipLoadingScreen: Boolean = false,
    uiScaleMultiplier: Float = 1f,
    showRuntimePanelInitially: Boolean = true,
    forceCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
) {
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()

    var initialized by remember { mutableStateOf(false) }
    var isLoadingComplete by remember(skipLoadingScreen) { mutableStateOf(skipLoadingScreen) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showPromptsPanel by remember { mutableStateOf(false) }
    var showRuntimePanel by remember(showRuntimePanelInitially) { mutableStateOf(showRuntimePanelInitially) }
    var showMemoryActionItemsPanel by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var hoveredTabIndex by remember { mutableStateOf(-1) }
    var projectArea by remember { mutableStateOf(ProjectArea.CONVERSATIONS) }
    var workspaceManagerProjectId by remember { mutableStateOf<Project.Id?>(null) }

    val currentSettings by appComponents.settingsService.settingsFlow.collectAsState()
    val remoteClientSettings by appComponents.remoteClientSettingsService.settingsFlow.collectAsState()
    val remoteConnectionState by appComponents.remoteConnectionState.collectAsState()
    val tabs by appComponents.appViewModel.tabs.collectAsState()
    val conversations by appComponents.appViewModel.conversations.collectAsState()
    val currentTabIndex by appComponents.appViewModel.currentTabIndex.collectAsState()
    val currentTab by appComponents.appViewModel.currentTab.collectAsState()
    val pttState by appComponents.pttService.state.collectAsState()
    val pttStatusMessage by appComponents.pttService.statusMessage.collectAsState()
    val keyboardPttGestureDetector = remember {
        UnifiedGestureDetector(appComponents.pttEventRouter, coroutineScope)
    }
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    val reportsComposeWindowFocus =
        clientPlatform != ClientPlatform.WEB_DESKTOP &&
            clientPlatform != ClientPlatform.WEB_TOUCH

    LaunchedEffect(Unit) {
        initialized = true
    }

    LaunchedEffect(isWindowFocused, reportsComposeWindowFocus) {
        if (reportsComposeWindowFocus && isWindowFocused) {
            appComponents.clientPresentationService.reportWindowFocused()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                appComponents.appViewModel.cleanup()
            }
        }
    }

    val createSession: (Project.Id, AgentDefinition?, String?) -> Unit = { projectId, agent, initialText ->
        coroutineScope.launch {
            runCatching {
                val initialMessage = initialText?.let { text ->
                    Conversation.Message(
                        id = Conversation.Message.Id(uuid7()),
                        conversationId = Conversation.Id(""),
                        role = Conversation.Message.Role.USER,
                        content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                        createdAt = Clock.System.now(),
                        instructions = listOf(Conversation.Message.Instruction.Source.User),
                    )
                }
                val tabIndex = appComponents.appViewModel.createTab(
                    projectId = projectId,
                    agent = agent,
                    initialMessage = initialMessage,
                    initiator = ConversationInitiator.User,
                )
                appComponents.appViewModel.selectTab(tabIndex)
            }.onFailure { it.printStackTrace() }
        }
    }

    val createNewSessionForProject: (Project) -> Unit = { project ->
        createSession(project.id, null, null)
    }

    val createNewSessionInCurrentProject: () -> Unit = {
        val tab = currentTab
        if (tab == null) {
            log.warn { "Cannot create a session without an explicitly selected project" }
        } else {
            createSession(tab.projectId, tab.uiState.value.agent, null)
        }
    }

    val createNewSessionWithMessage: (String) -> Unit = { initialMessage ->
        val tab = currentTab
        if (tab == null) {
            log.warn { "Cannot create a session without an explicitly selected project" }
        } else {
            createSession(tab.projectId, tab.uiState.value.agent, initialMessage)
        }
    }

    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        appComponents.settingsService.saveSettings(newSettings)
    }
    val onRemoteClientSettingsChange = appComponents.remoteClientSettingsService::saveSettings
    val currentUiSettings = currentSettings.userDeviceSettings.uiSettings
    val platformDensity = LocalDensity.current
    val effectiveUiScale = (currentUiSettings.uiScale * uiScaleMultiplier).coerceIn(0.5f, 3.0f)
    val baseDensity = if (clientPlatform.usePlatformDensity) platformDensity.density else 1.0f
    val baseFontScale = if (clientPlatform.usePlatformDensity) platformDensity.fontScale else 1.0f

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = baseDensity * effectiveUiScale,
            fontScale = baseFontScale * currentUiSettings.fontScale,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusTarget()
                .pointerInput(appComponents.clientPresentationService) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        appComponents.clientPresentationService.reportUserInteraction()
                    }
                }
                .advancedEscape(appComponents.pttEventRouter)
                .testTag(UiTestTag.AppRoot.value)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        appComponents.clientPresentationService.reportUserInteraction()
                    }
                    when {
                        event.key == Key.T && event.isMetaPressed && event.type == KeyEventType.KeyDown -> {
                            createNewSessionInCurrentProject()
                            true
                        }

                        event.utf16CodePoint == 167 -> {
                            when (event.type) {
                                KeyEventType.KeyDown -> {
                                    coroutineScope.launch { keyboardPttGestureDetector.onGestureDown() }
                                }

                                KeyEventType.KeyUp -> {
                                    coroutineScope.launch { keyboardPttGestureDetector.onGestureUp() }
                                }
                            }
                            true
                        }

                        else -> false
                    }
                }
        ) {
            when {
                !initialized -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                !isLoadingComplete -> {
                    LoadingScreen(
                        loadingViewModel = appComponents.loadingViewModel,
                        onComplete = { isLoadingComplete = true }
                    )
                }

                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val scaledDensity = LocalDensity.current
                        val baseMaxWidth = if (clientPlatform.usePlatformDensity) {
                            with(platformDensity) {
                                with(scaledDensity) { maxWidth.toPx() }.toDp()
                            }
                        } else {
                            maxWidth
                        }
                        val isCompactLayout = forceCompactLayout || baseMaxWidth < 700.dp
                        val contentPadding = if (isCompactLayout) 8.dp else 16.dp
                        val setSettingsPanel: (Boolean) -> Unit = { visible ->
                            showSettingsPanel = visible
                            if (visible && isCompactLayout) {
                                showPromptsPanel = false
                                showRuntimePanel = false
                                showMemoryActionItemsPanel = false
                            }
                        }
                        val setPromptsPanel: (Boolean) -> Unit = { visible ->
                            showPromptsPanel = visible
                            if (visible && isCompactLayout) {
                                showSettingsPanel = false
                                showRuntimePanel = false
                                showMemoryActionItemsPanel = false
                            }
                        }
                        val setRuntimePanel: (Boolean) -> Unit = { visible ->
                            showRuntimePanel = visible
                            if (visible && isCompactLayout) {
                                showSettingsPanel = false
                                showPromptsPanel = false
                                showMemoryActionItemsPanel = false
                            }
                        }
                        val setMemoryActionItemsPanel: (Boolean) -> Unit = { visible ->
                            showMemoryActionItemsPanel = visible
                            if (visible && isCompactLayout) {
                                showSettingsPanel = false
                                showPromptsPanel = false
                                showRuntimePanel = false
                            }
                        }

                        LaunchedEffect(
                            isCompactLayout,
                            showSettingsPanel,
                            showPromptsPanel,
                            showRuntimePanel,
                            showMemoryActionItemsPanel,
                        ) {
                            if (isCompactLayout) {
                                when {
                                    showSettingsPanel && showPromptsPanel -> showPromptsPanel = false
                                    showSettingsPanel && showRuntimePanel -> showRuntimePanel = false
                                    showSettingsPanel && showMemoryActionItemsPanel -> showMemoryActionItemsPanel = false
                                    showPromptsPanel && showRuntimePanel -> showRuntimePanel = false
                                    showPromptsPanel && showMemoryActionItemsPanel -> showMemoryActionItemsPanel = false
                                    showRuntimePanel && showMemoryActionItemsPanel -> showMemoryActionItemsPanel = false
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.weight(1f)) {
                                val selectedTabIndex = when (val currentIndex = currentTabIndex) {
                                    null -> 0
                                    -1 -> 1
                                    -2 -> 2
                                    -3 -> 3
                                    else -> currentIndex + 4
                                }

                                val tabRowComponent = @Composable {
                                    CustomTabRow(
                                        selectedTabIndex = selectedTabIndex,
                                        showTabsAtBottom = currentUiSettings.showTabsAtBottom,
                                        isCompactLayout = isCompactLayout,
                                        tabs = tabs,
                                        conversations = conversations,
                                        hoveredTabIndex = hoveredTabIndex,
                                        onTabSelect = { tabIndex ->
                                            coroutineScope.launch {
                                                appComponents.appViewModel.selectTab(tabIndex)
                                            }
                                        },
                                        onTabHover = { index -> hoveredTabIndex = index },
                                        onTabHoverExit = { hoveredTabIndex = -1 },
                                        onRenameConversation = { conversationId, newName ->
                                            coroutineScope.launch {
                                                appComponents.appViewModel.renameConversation(conversationId, newName)
                                            }
                                        },
                                        coroutineScope = coroutineScope
                                    )
                                }

                                if (!currentUiSettings.showTabsAtBottom) {
                                    tabRowComponent()
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.weight(1f)) {
                                        Column(modifier = Modifier.weight(1f).padding(contentPadding)) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                if (currentTabIndex == -3) {
                                                    LiveInterpreterScreen(
                                                        settings = currentSettings,
                                                        liveInterpreterService = appComponents.liveInterpreterService,
                                                        liveAudioStreamer = appComponents.liveAudioStreamer,
                                                        clientSideSpeechToTextService = appComponents.clientSideSpeechToTextService,
                                                        coroutineScope = coroutineScope,
                                                        isCompactLayout = isCompactLayout,
                                                    )
                                                } else if (currentTab != null) {
                                                    val tabViewModel = currentTab!!
                                                    SessionScreen(
                                                        viewModel = tabViewModel,
                                                        onNewSession = createNewSessionInCurrentProject,
                                                        onForkSession = {
                                                            coroutineScope.launch {
                                                                runCatching {
                                                                    val currentConversationId =
                                                                        currentTab!!.uiState.first().conversationId
                                                                    val forkedConversation =
                                                                        appComponents.conversationService.fork(currentConversationId)
                                                                    val tabIndex = appComponents.appViewModel.createTab(
                                                                        projectId = forkedConversation.projectId,
                                                                        conversationId = forkedConversation.id,
                                                                        initiator = ConversationInitiator.User
                                                                    )
                                                                    appComponents.appViewModel.selectTab(tabIndex)
                                                                }.onFailure { error ->
                                                                    log.warn(error) { "Failed to fork conversation: ${error.message}" }
                                                                }
                                                            }
                                                        },
                                                        onRestartSession = {
                                                            coroutineScope.launch {
                                                                val projectId = currentTab!!.projectId
                                                                val oldIndex = currentTabIndex!!
                                                                appComponents.appViewModel.createTab(
                                                                    projectId = projectId,
                                                                    agent = currentTab!!.uiState.value.agent,
                                                                    initiator = ConversationInitiator.User
                                                                )
                                                                appComponents.appViewModel.closeTab(oldIndex)
                                                            }
                                                        },
                                                        onCloseTab = {
                                                            coroutineScope.launch {
                                                                currentTabIndex?.let { appComponents.appViewModel.closeTab(it) }
                                                            }
                                                        },
                                                        ttsQueueService = appComponents.ttsQueueService,
                                                        coroutineScope = coroutineScope,
                                                        pttEventHandler = appComponents.pttEventRouter,
                                                        pttState = pttState,
                                                        settings = currentSettings,
                                                        showSettingsPanel = showSettingsPanel,
                                                        onShowSettingsPanelChange = setSettingsPanel,
                                                        showMemoryActionItemsPanel = showMemoryActionItemsPanel,
                                                        onShowMemoryActionItemsPanelChange = setMemoryActionItemsPanel,
                                                        showRuntimePanel = showRuntimePanel,
                                                        onShowRuntimePanelChange = setRuntimePanel,
                                                        onRememberThread = {
                                                            coroutineScope.launch {
                                                                runCatching { appComponents.appViewModel.rememberCurrentThread() }
                                                                    .onFailure { error ->
                                                                        log.warn(error) { "Remember thread failed: ${error.message}" }
                                                                    }
                                                            }
                                                        },
                                                        onConsolidateMemory = {
                                                            coroutineScope.launch {
                                                                runCatching { appComponents.appViewModel.consolidateCurrentMemory() }
                                                                    .onFailure { error ->
                                                                        log.warn(error) { "Memory consolidation failed: ${error.message}" }
                                                                    }
                                                            }
                                                        },
                                                        onRepairMemory = {
                                                            coroutineScope.launch {
                                                                runCatching { appComponents.appViewModel.repairCurrentMemory() }
                                                                    .onFailure { error ->
                                                                        log.warn(error) { "Memory repair failed: ${error.message}" }
                                                                    }
                                                            }
                                                        },
                                                        onMaintainMemoryEntities = {
                                                            coroutineScope.launch {
                                                                runCatching { appComponents.appViewModel.maintainMemoryEntities() }
                                                                    .onFailure { error ->
                                                                        log.warn(error) { "Memory entity maintenance failed: ${error.message}" }
                                                                    }
                                                            }
                                                        },
                                                        onApplyMemoryRetention = {
                                                            coroutineScope.launch {
                                                                runCatching { appComponents.appViewModel.applyCurrentMemoryRetention() }
                                                                    .onFailure { error ->
                                                                        log.warn(error) { "Memory retention failed: ${error.message}" }
                                                                    }
                                                            }
                                                        },
                                                        onInsertCurrentLocation = if (appComponents.deviceLocationService.isSupported) {
                                                            {
                                                                coroutineScope.launch {
                                                                    val locationText = appComponents.deviceLocationService
                                                                        .getCurrentLocation()
                                                                        .toInputText()
                                                                    val currentInput = tabViewModel.userInput.trimEnd()
                                                                    tabViewModel.updateUserInput(
                                                                        if (currentInput.isBlank()) {
                                                                            locationText
                                                                        } else {
                                                                            "$currentInput\n\n$locationText"
                                                                        }
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                        onShowPromptsPanelChange = setPromptsPanel,
                                                        isDev = appComponents.settingsService.mode == AppMode.DEV,
                                                        isCompactLayout = isCompactLayout,
                                                        clientPlatform = clientPlatform,
                                                    )
                                                } else {
                                                    Box(modifier = Modifier.padding(contentPadding)) {
                                                        when (selectedTabIndex) {
                                                            1 -> {
                                                                AgentConstructorScreen(
                                                                    projectId = tabs.firstOrNull()?.projectId,
                                                                    agentService = appComponents.agentService,
                                                                    agentSkillService = appComponents.agentSkillService,
                                                                    promptService = appComponents.promptService,
                                                                    settingsService = appComponents.settingsService,
                                                                    coroutineScope = coroutineScope,
                                                                )
                                                            }

                                                            2 -> {
                                                                SettingsPanel(
                                                                    isVisible = true,
                                                                    settings = currentSettings,
                                                                    onSettingsChange = onSettingsChange,
                                                                    remoteClientSettings = remoteClientSettings,
                                                                    onRemoteClientSettingsChange = onRemoteClientSettingsChange,
                                                                    onClose = {},
                                                                    translationService = appComponents.translationService,
                                                                    themeService = appComponents.themeService,
                                                                    aiThemeGenerator = appComponents.aiThemeGenerator,
                                                                    logEncryptor = appComponents.logEncryptor,
                                                                    settingsService = appComponents.settingsService,
                                                                    ollamaModelService = appComponents.ollamaModelService,
                                                                    coroutineScope = coroutineScope,
                                                                    onOpenTab = createNewSessionInCurrentProject,
                                                                    onOpenTabWithMessage = createNewSessionWithMessage,
                                                                    fullScreen = true,
                                                                    contentMode = SettingsPanelContentMode.AiRuntime,
                                                                    showCloseButton = false
                                                                )
                                                            }

                                                            else -> when (projectArea) {
                                                                ProjectArea.CONVERSATIONS -> SessionListScreen(
                                                                    onConversationSelected = { _, _ -> refreshTrigger++ },
                                                                    coroutineScope = coroutineScope,
                                                                    onNewSession = createNewSessionForProject,
                                                                    projectService = appComponents.projectService,
                                                                    conversationTreeService = appComponents.conversationService,
                                                                    appViewModel = appComponents.appViewModel,
                                                                    searchViewModel = appComponents.conversationSearchViewModel,
                                                                    showSettingsPanel = showSettingsPanel,
                                                                    onShowSettingsPanelChange = setSettingsPanel,
                                                                    onManageProjects = { projectArea = ProjectArea.PROJECTS },
                                                                    onManageWorkspaces = {
                                                                        workspaceManagerProjectId = null
                                                                        projectArea = ProjectArea.WORKSPACES
                                                                    },
                                                                    refreshTrigger = refreshTrigger,
                                                                )

                                                                ProjectArea.PROJECTS -> ProjectManagerScreen(
                                                                    projectService = appComponents.projectService,
                                                                    onBack = { projectArea = ProjectArea.CONVERSATIONS },
                                                                    onManageWorkspaces = { projectId ->
                                                                        workspaceManagerProjectId = projectId
                                                                        projectArea = ProjectArea.WORKSPACES
                                                                    },
                                                                    onChanged = { refreshTrigger++ },
                                                                )

                                                                ProjectArea.WORKSPACES -> WorkspaceManagerScreen(
                                                                    initialProjectId = workspaceManagerProjectId,
                                                                    projectService = appComponents.projectService,
                                                                    workspaceCatalogService = appComponents.workspaceCatalogService,
                                                                    workspaceManagementService = appComponents.workspaceManagementService,
                                                                    onBack = { projectArea = ProjectArea.CONVERSATIONS },
                                                                    onManageProjects = { projectArea = ProjectArea.PROJECTS },
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (!isCompactLayout) currentTab?.let { tabViewModel ->
                                            val tabUiState by tabViewModel.uiState.collectAsState()
                                            val tokenStats by tabViewModel.tokenStats.collectAsState()
                                            val isWaitingForResponse by tabViewModel.isWaitingForResponse.collectAsState()
                                            val executionPauseRequested by tabViewModel.executionPauseRequested.collectAsState()
                                            val pendingMessages by tabViewModel.pendingMessages.collectAsState()
                                            val runtimeSnapshot by tabViewModel.runtimeSnapshot.collectAsState()

                                            Box(modifier = Modifier.testTag(UiTestTag.RuntimePanel.value)) {
                                                ConversationRuntimePanel(
                                                    isVisible = showRuntimePanel,
                                                    currentAgent = tabUiState.agent,
                                                    settings = currentSettings,
                                                    tokenStats = tokenStats,
                                                    isWaitingForResponse = isWaitingForResponse,
                                                    executionPauseRequested = executionPauseRequested,
                                                    pttState = pttState,
                                                    pttStatusMessage = pttStatusMessage,
                                                    pendingMessages = pendingMessages,
                                                    runtimeSnapshot = runtimeSnapshot,
                                                    remoteConnectionState = remoteConnectionState,
                                                    onPause = tabViewModel::pauseExecution,
                                                    onResume = tabViewModel::resumeExecution,
                                                    onStop = tabViewModel::stopExecution,
                                                    onCancelCommandTask = tabViewModel::cancelCommandTask,
                                                    onSendInCurrentTurn = tabViewModel::sendPendingMessageInCurrentTurn,
                                                    onEditPendingMessage = tabViewModel::editPendingMessage,
                                                    onCancelPendingMessage = tabViewModel::cancelPendingMessage,
                                                    onClose = { setRuntimePanel(false) },
                                                )
                                            }

                                            Box(modifier = Modifier.testTag(UiTestTag.PromptsPanel.value)) {
                                                AgentPanel(
                                                    isVisible = showPromptsPanel,
                                                    projectId = tabViewModel.projectId,
                                                    currentAgent = tabUiState.agent,
                                                    onAgentChange = { tabViewModel.updateAgent(it) },
                                                    onClose = { setPromptsPanel(false) },
                                                    agentService = appComponents.agentService,
                                                    coroutineScope = coroutineScope,
                                                )
                                            }
                                        }
                                    }
                                }

                                if (currentUiSettings.showTabsAtBottom) {
                                    tabRowComponent()
                                }
                            }

                            if (!isCompactLayout) Box(modifier = Modifier.testTag(UiTestTag.SettingsPanel.value)) {
                                SettingsPanel(
                                    isVisible = showSettingsPanel,
                                    settings = currentSettings,
                                    onSettingsChange = onSettingsChange,
                                    remoteClientSettings = remoteClientSettings,
                                    onRemoteClientSettingsChange = onRemoteClientSettingsChange,
                                    onClose = { setSettingsPanel(false) },
                                    translationService = appComponents.translationService,
                                    themeService = appComponents.themeService,
                                    aiThemeGenerator = appComponents.aiThemeGenerator,
                                    logEncryptor = appComponents.logEncryptor,
                                    settingsService = appComponents.settingsService,
                                    ollamaModelService = appComponents.ollamaModelService,
                                    coroutineScope = coroutineScope,
                                    onOpenTab = createNewSessionInCurrentProject,
                                    onOpenTabWithMessage = createNewSessionWithMessage,
                                    contentMode = SettingsPanelContentMode.Quick
                                )
                            }

                            if (!isCompactLayout) currentTab?.let { tabViewModel ->
                                val refreshKey by tabViewModel.memoryActionItemsRefreshKey.collectAsState()
                                Box(modifier = Modifier.testTag(UiTestTag.MemoryActionItemsPanel.value)) {
                                    MemoryActionItemsPanel(
                                        isVisible = showMemoryActionItemsPanel,
                                        conversationId = tabViewModel.conversationId,
                                        refreshKey = refreshKey,
                                        memoryActionItemService = appComponents.memoryActionItemService,
                                        coroutineScope = coroutineScope,
                                        onClose = { setMemoryActionItemsPanel(false) }
                                    )
                                }
                            }
                        }

                        if (isCompactLayout) {
                            currentTab?.let { tabViewModel ->
                                val tabUiState by tabViewModel.uiState.collectAsState()
                                val tokenStats by tabViewModel.tokenStats.collectAsState()
                                val isWaitingForResponse by tabViewModel.isWaitingForResponse.collectAsState()
                                val executionPauseRequested by tabViewModel.executionPauseRequested.collectAsState()
                                val pendingMessages by tabViewModel.pendingMessages.collectAsState()
                                val runtimeSnapshot by tabViewModel.runtimeSnapshot.collectAsState()

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(2f)
                                        .testTag(UiTestTag.RuntimePanel.value)
                                ) {
                                    ConversationRuntimePanel(
                                        isVisible = showRuntimePanel,
                                        currentAgent = tabUiState.agent,
                                        settings = currentSettings,
                                        tokenStats = tokenStats,
                                        isWaitingForResponse = isWaitingForResponse,
                                        executionPauseRequested = executionPauseRequested,
                                        pttState = pttState,
                                        pttStatusMessage = pttStatusMessage,
                                        pendingMessages = pendingMessages,
                                        runtimeSnapshot = runtimeSnapshot,
                                        remoteConnectionState = remoteConnectionState,
                                        onPause = tabViewModel::pauseExecution,
                                        onResume = tabViewModel::resumeExecution,
                                        onStop = tabViewModel::stopExecution,
                                        onCancelCommandTask = tabViewModel::cancelCommandTask,
                                        onSendInCurrentTurn = tabViewModel::sendPendingMessageInCurrentTurn,
                                        onEditPendingMessage = tabViewModel::editPendingMessage,
                                        onCancelPendingMessage = tabViewModel::cancelPendingMessage,
                                        onClose = { setRuntimePanel(false) },
                                        fullScreen = true,
                                        slideFromRight = true,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(3f)
                                        .testTag(UiTestTag.PromptsPanel.value)
                                ) {
                                    AgentPanel(
                                        isVisible = showPromptsPanel,
                                        projectId = tabViewModel.projectId,
                                        currentAgent = tabUiState.agent,
                                        onAgentChange = { tabViewModel.updateAgent(it) },
                                        onClose = { setPromptsPanel(false) },
                                        agentService = appComponents.agentService,
                                        coroutineScope = coroutineScope,
                                        fullScreen = true,
                                        slideFromRight = true
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(4f)
                                    .testTag(UiTestTag.SettingsPanel.value)
                            ) {
                                SettingsPanel(
                                    isVisible = showSettingsPanel,
                                    settings = currentSettings,
                                    onSettingsChange = onSettingsChange,
                                    remoteClientSettings = remoteClientSettings,
                                    onRemoteClientSettingsChange = onRemoteClientSettingsChange,
                                    onClose = { setSettingsPanel(false) },
                                    translationService = appComponents.translationService,
                                    themeService = appComponents.themeService,
                                    aiThemeGenerator = appComponents.aiThemeGenerator,
                                    logEncryptor = appComponents.logEncryptor,
                                    settingsService = appComponents.settingsService,
                                    ollamaModelService = appComponents.ollamaModelService,
                                    coroutineScope = coroutineScope,
                                    onOpenTab = createNewSessionInCurrentProject,
                                    onOpenTabWithMessage = createNewSessionWithMessage,
                                    fullScreen = true,
                                    slideFromRight = true,
                                    contentMode = SettingsPanelContentMode.Quick
                                )
                            }

                            currentTab?.let { tabViewModel ->
                                val refreshKey by tabViewModel.memoryActionItemsRefreshKey.collectAsState()
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(5f)
                                        .testTag(UiTestTag.MemoryActionItemsPanel.value)
                                ) {
                                    MemoryActionItemsPanel(
                                        isVisible = showMemoryActionItemsPanel,
                                        conversationId = tabViewModel.conversationId,
                                        refreshKey = refreshKey,
                                        memoryActionItemService = appComponents.memoryActionItemService,
                                        coroutineScope = coroutineScope,
                                        onClose = { setMemoryActionItemsPanel(false) },
                                        fullScreen = true,
                                        slideFromRight = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ProjectArea {
    CONVERSATIONS,
    PROJECTS,
    WORKSPACES,
}

private fun DeviceLocationResult.toInputText(): String =
    when (this) {
        is DeviceLocationResult.Available -> snapshot.toInputText()
        is DeviceLocationResult.Unavailable -> buildString {
            append("Device location unavailable: ")
            append(reason.name.lowercase().replace('_', ' '))
            message?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        }
    }

private fun DeviceLocationSnapshot.toInputText(): String =
    buildString {
        append("Current device location: ")
        append("latitude=$latitude, longitude=$longitude")
        accuracyMeters?.let { append(", accuracyMeters=$it") }
        altitudeMeters?.let { append(", altitudeMeters=$it") }
        provider?.let { append(", provider=$it") }
        append(", capturedAt=$capturedAt")
    }
