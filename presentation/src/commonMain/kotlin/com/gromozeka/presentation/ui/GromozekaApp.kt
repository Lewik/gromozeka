package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.device.telemetry.DeviceLocationResult
import com.gromozeka.device.telemetry.DeviceLocationSnapshot
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.Settings
import com.gromozeka.presentation.AppComponents
import com.gromozeka.presentation.services.UnifiedGestureDetector
import com.gromozeka.presentation.ui.agents.AgentConstructorScreen
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
    showPromptsPanelInitially: Boolean = true,
    forceCompactLayout: Boolean = false,
) {
    val currentTheme by appComponents.themeService.currentTheme.collectAsState()
    GromozekaTheme(currentTheme = currentTheme) {
        val currentTranslation by appComponents.translationService.currentTranslation.collectAsState()
        TranslationProvider(currentTranslation) {
            GromozekaAppContent(
                appComponents = appComponents,
                skipLoadingScreen = skipLoadingScreen,
                uiScaleMultiplier = uiScaleMultiplier,
                showPromptsPanelInitially = showPromptsPanelInitially,
                forceCompactLayout = forceCompactLayout,
            )
        }
    }
}

@Composable
fun GromozekaAppContent(
    appComponents: AppComponents,
    skipLoadingScreen: Boolean = false,
    uiScaleMultiplier: Float = 1f,
    showPromptsPanelInitially: Boolean = true,
    forceCompactLayout: Boolean = false,
) {
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()

    var initialized by remember { mutableStateOf(false) }
    var isLoadingComplete by remember(skipLoadingScreen) { mutableStateOf(skipLoadingScreen) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showPromptsPanel by remember(showPromptsPanelInitially) { mutableStateOf(showPromptsPanelInitially) }
    var showMemoryTasksPanel by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var hoveredTabIndex by remember { mutableStateOf(-1) }

    val currentSettings by appComponents.settingsService.settingsFlow.collectAsState()
    val remoteClientSettings by appComponents.remoteClientSettingsService.settingsFlow.collectAsState()
    val tabs by appComponents.appViewModel.tabs.collectAsState()
    val currentTabIndex by appComponents.appViewModel.currentTabIndex.collectAsState()
    val currentTab by appComponents.appViewModel.currentTab.collectAsState()
    val isRecording by appComponents.pttService.recordingState.collectAsState()
    val pttStatusMessage by appComponents.pttService.statusMessage.collectAsState()
    val keyboardPttGestureDetector = remember {
        UnifiedGestureDetector(appComponents.pttEventRouter, coroutineScope)
    }

    LaunchedEffect(Unit) {
        initialized = true
    }

    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                appComponents.appViewModel.cleanup()
            }
        }
    }

    val createNewSession: (String) -> Unit = { projectPath ->
        coroutineScope.launch {
            runCatching {
                val tabIndex = appComponents.appViewModel.createTab(
                    projectPath = projectPath,
                    initiator = ConversationInitiator.User
                )
                appComponents.appViewModel.selectTab(tabIndex)
            }.onFailure { it.printStackTrace() }
        }
    }

    val createNewSessionWithMessage: (String, String) -> Unit = { projectPath, initialMessage ->
        coroutineScope.launch {
            runCatching {
                val chatMessage = Conversation.Message(
                    id = Conversation.Message.Id(uuid7()),
                    conversationId = Conversation.Id(""),
                    role = Conversation.Message.Role.USER,
                    content = listOf(Conversation.Message.ContentItem.UserMessage(initialMessage)),
                    createdAt = Clock.System.now(),
                    instructions = listOf(Conversation.Message.Instruction.Source.User)
                )
                val tabIndex = appComponents.appViewModel.createTab(
                    projectPath = projectPath,
                    agent = null,
                    initialMessage = chatMessage,
                    initiator = ConversationInitiator.User
                )
                appComponents.appViewModel.selectTab(tabIndex)
            }.onFailure { it.printStackTrace() }
        }
    }

    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        appComponents.settingsService.saveSettings(newSettings)
    }
    val onRemoteClientSettingsChange = appComponents.remoteClientSettingsService::saveSettings
    val currentUiSettings = currentSettings.userDeviceSettings.uiSettings

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = (currentUiSettings.uiScale * uiScaleMultiplier).coerceIn(0.5f, 3.0f),
            fontScale = currentUiSettings.fontScale,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusTarget()
                .advancedEscape(appComponents.pttEventRouter)
                .testTag(UiTestTag.AppRoot.value)
                .onPreviewKeyEvent { event ->
                    when {
                        event.key == Key.T && event.isMetaPressed && event.type == KeyEventType.KeyDown -> {
                            if (currentTab != null) {
                                createNewSession(currentTab!!.projectPath)
                            }
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
                        val isCompactLayout = forceCompactLayout || maxWidth < 700.dp
                        val contentPadding = if (isCompactLayout) 8.dp else 16.dp
                        val setSettingsPanel: (Boolean) -> Unit = { visible ->
                            showSettingsPanel = visible
                            if (visible && isCompactLayout) {
                                showPromptsPanel = false
                                showMemoryTasksPanel = false
                            }
                        }
                        val setPromptsPanel: (Boolean) -> Unit = { visible ->
                            showPromptsPanel = visible
                            if (visible && isCompactLayout) {
                                showSettingsPanel = false
                                showMemoryTasksPanel = false
                            }
                        }
                        val setMemoryTasksPanel: (Boolean) -> Unit = { visible ->
                            showMemoryTasksPanel = visible
                            if (visible && isCompactLayout) {
                                showSettingsPanel = false
                                showPromptsPanel = false
                            }
                        }

                        LaunchedEffect(isCompactLayout, showSettingsPanel, showPromptsPanel, showMemoryTasksPanel) {
                            if (isCompactLayout) {
                                when {
                                    showSettingsPanel && showPromptsPanel -> showPromptsPanel = false
                                    showSettingsPanel && showMemoryTasksPanel -> showMemoryTasksPanel = false
                                    showPromptsPanel && showMemoryTasksPanel -> showMemoryTasksPanel = false
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
                                        tabs = tabs,
                                        hoveredTabIndex = hoveredTabIndex,
                                        onTabSelect = { tabIndex ->
                                            coroutineScope.launch {
                                                appComponents.appViewModel.selectTab(tabIndex)
                                            }
                                        },
                                        onTabHover = { index -> hoveredTabIndex = index },
                                        onTabHoverExit = { hoveredTabIndex = -1 },
                                        onRenameTab = { tabIndexToRename, newName ->
                                            coroutineScope.launch {
                                                appComponents.appViewModel.renameTab(tabIndexToRename, newName)
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
                                                        onNewSession = { createNewSession(currentTab!!.projectPath) },
                                                        onForkSession = {
                                                            coroutineScope.launch {
                                                                runCatching {
                                                                    val currentConversationId =
                                                                        currentTab!!.uiState.first().conversationId
                                                                    val forkedConversation =
                                                                        appComponents.conversationService.fork(currentConversationId)
                                                                    val tabIndex = appComponents.appViewModel.createTab(
                                                                        projectPath = currentTab!!.projectPath,
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
                                                                val projectPath = currentTab!!.projectPath
                                                                val oldIndex = currentTabIndex!!
                                                                appComponents.appViewModel.createTab(
                                                                    projectPath = projectPath,
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
                                                        isRecording = isRecording,
                                                        pttStatusMessage = pttStatusMessage,
                                                        settings = currentSettings,
                                                        showSettingsPanel = showSettingsPanel,
                                                        onShowSettingsPanelChange = setSettingsPanel,
                                                        showMemoryTasksPanel = showMemoryTasksPanel,
                                                        onShowMemoryTasksPanelChange = setMemoryTasksPanel,
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
                                                        isCompactLayout = isCompactLayout
                                                    )
                                                } else {
                                                    Box(modifier = Modifier.padding(contentPadding)) {
                                                        when (selectedTabIndex) {
                                                            1 -> {
                                                                AgentConstructorScreen(
                                                                    agentService = appComponents.agentService,
                                                                    promptService = appComponents.promptService,
                                                                    coroutineScope = coroutineScope,
                                                                    projectPath = currentTab?.uiState?.value?.projectPath
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
                                                                    onOpenTab = createNewSession,
                                                                    onOpenTabWithMessage = createNewSessionWithMessage,
                                                                    fullScreen = true,
                                                                    contentMode = SettingsPanelContentMode.AiRuntime,
                                                                    showCloseButton = false
                                                                )
                                                            }

                                                            else -> {
                                                                SessionListScreen(
                                                                    onConversationSelected = { _, _ -> refreshTrigger++ },
                                                                    coroutineScope = coroutineScope,
                                                                    onNewSession = createNewSession,
                                                                    projectService = appComponents.projectService,
                                                                    conversationTreeService = appComponents.conversationService,
                                                                    appViewModel = appComponents.appViewModel,
                                                                    searchViewModel = appComponents.conversationSearchViewModel,
                                                                    showSettingsPanel = showSettingsPanel,
                                                                    onShowSettingsPanelChange = setSettingsPanel,
                                                                    refreshTrigger = refreshTrigger
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
                                            Box(modifier = Modifier.testTag(UiTestTag.PromptsPanel.value)) {
                                                AgentPanel(
                                                    projectPath = tabUiState.projectPath,
                                                    isVisible = showPromptsPanel,
                                                    currentAgent = tabUiState.agent,
                                                    onAgentChange = { tabViewModel.updateAgent(it) },
                                                    onClose = { setPromptsPanel(false) },
                                                    agentService = appComponents.agentService,
                                                    coroutineScope = coroutineScope,
                                                    tokenStats = tokenStats
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
                                    onOpenTab = createNewSession,
                                    onOpenTabWithMessage = createNewSessionWithMessage,
                                    contentMode = SettingsPanelContentMode.Quick
                                )
                            }

                            if (!isCompactLayout) currentTab?.let { tabViewModel ->
                                val refreshKey by tabViewModel.memoryTasksRefreshKey.collectAsState()
                                Box(modifier = Modifier.testTag(UiTestTag.MemoryTasksPanel.value)) {
                                    MemoryTasksPanel(
                                        isVisible = showMemoryTasksPanel,
                                        conversationId = tabViewModel.conversationId,
                                        refreshKey = refreshKey,
                                        memoryTaskService = appComponents.memoryTaskService,
                                        coroutineScope = coroutineScope,
                                        onClose = { setMemoryTasksPanel(false) }
                                    )
                                }
                            }
                        }

                        if (isCompactLayout) {
                            currentTab?.let { tabViewModel ->
                                val tabUiState by tabViewModel.uiState.collectAsState()
                                val tokenStats by tabViewModel.tokenStats.collectAsState()
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(2f)
                                        .testTag(UiTestTag.PromptsPanel.value)
                                ) {
                                    AgentPanel(
                                        projectPath = tabUiState.projectPath,
                                        isVisible = showPromptsPanel,
                                        currentAgent = tabUiState.agent,
                                        onAgentChange = { tabViewModel.updateAgent(it) },
                                        onClose = { setPromptsPanel(false) },
                                        agentService = appComponents.agentService,
                                        coroutineScope = coroutineScope,
                                        tokenStats = tokenStats,
                                        fullScreen = true,
                                        slideFromRight = true
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(3f)
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
                                    onOpenTab = createNewSession,
                                    onOpenTabWithMessage = createNewSessionWithMessage,
                                    fullScreen = true,
                                    slideFromRight = true,
                                    contentMode = SettingsPanelContentMode.Quick
                                )
                            }

                            currentTab?.let { tabViewModel ->
                                val refreshKey by tabViewModel.memoryTasksRefreshKey.collectAsState()
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(4f)
                                        .testTag(UiTestTag.MemoryTasksPanel.value)
                                ) {
                                    MemoryTasksPanel(
                                        isVisible = showMemoryTasksPanel,
                                        conversationId = tabViewModel.conversationId,
                                        refreshKey = refreshKey,
                                        memoryTaskService = appComponents.memoryTaskService,
                                        coroutineScope = coroutineScope,
                                        onClose = { setMemoryTasksPanel(false) },
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
