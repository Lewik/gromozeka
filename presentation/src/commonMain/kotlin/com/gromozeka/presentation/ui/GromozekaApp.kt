package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
) {
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()

    var initialized by remember { mutableStateOf(false) }
    var isLoadingComplete by remember(skipLoadingScreen) { mutableStateOf(skipLoadingScreen) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showPromptsPanel by remember(showPromptsPanelInitially) { mutableStateOf(showPromptsPanelInitially) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var hoveredTabIndex by remember { mutableStateOf(-1) }

    val currentSettings by appComponents.settingsService.settingsFlow.collectAsState()
    val tabs by appComponents.appViewModel.tabs.collectAsState()
    val currentTabIndex by appComponents.appViewModel.currentTabIndex.collectAsState()
    val currentTab by appComponents.appViewModel.currentTab.collectAsState()
    val isRecording by appComponents.pttService.recordingState.collectAsState()
    val modifierWithPushToTalk = Modifier.advancedPttGestures(appComponents.pttEventRouter, coroutineScope)
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

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = (currentSettings.uiScale * uiScaleMultiplier).coerceIn(0.5f, 3.0f),
            fontScale = currentSettings.fontScale,
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
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f)) {
                            val selectedTabIndex = when (val currentIndex = currentTabIndex) {
                                null -> 0
                                -1 -> 1
                                else -> currentIndex + 2
                            }

                            val tabRowComponent = @Composable {
                                CustomTabRow(
                                    selectedTabIndex = selectedTabIndex,
                                    showTabsAtBottom = currentSettings.showTabsAtBottom,
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

                            if (!currentSettings.showTabsAtBottom) {
                                tabRowComponent()
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            if (currentTab != null) {
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
                                                    modifierWithPushToTalk = modifierWithPushToTalk,
                                                    isRecording = isRecording,
                                                    settings = currentSettings,
                                                    showSettingsPanel = showSettingsPanel,
                                                    onShowSettingsPanelChange = { showSettingsPanel = it },
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
                                                    onShowPromptsPanelChange = { showPromptsPanel = it },
                                                    isDev = appComponents.settingsService.mode == AppMode.DEV
                                                )
                                            } else {
                                                Box(modifier = Modifier.padding(16.dp)) {
                                                    when (selectedTabIndex) {
                                                        1 -> {
                                                            AgentConstructorScreen(
                                                                agentService = appComponents.agentService,
                                                                promptService = appComponents.promptService,
                                                                coroutineScope = coroutineScope,
                                                                projectPath = currentTab?.uiState?.value?.projectPath
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
                                                                onShowSettingsPanelChange = { showSettingsPanel = it },
                                                                refreshTrigger = refreshTrigger
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    currentTab?.let { tabViewModel ->
                                        val tabUiState by tabViewModel.uiState.collectAsState()
                                        val tokenStats by tabViewModel.tokenStats.collectAsState()
                                        Box(modifier = Modifier.testTag(UiTestTag.PromptsPanel.value)) {
                                            AgentPanel(
                                                projectPath = tabUiState.projectPath,
                                                isVisible = showPromptsPanel,
                                                currentAgent = tabUiState.agent,
                                                onAgentChange = { tabViewModel.updateAgent(it) },
                                                onClose = { showPromptsPanel = false },
                                                agentService = appComponents.agentService,
                                                coroutineScope = coroutineScope,
                                                tokenStats = tokenStats
                                            )
                                        }
                                    }
                                }
                            }

                            if (currentSettings.showTabsAtBottom) {
                                tabRowComponent()
                            }
                        }

                        Box(modifier = Modifier.testTag(UiTestTag.SettingsPanel.value)) {
                            SettingsPanel(
                                isVisible = showSettingsPanel,
                                settings = currentSettings,
                                onSettingsChange = onSettingsChange,
                                onClose = { showSettingsPanel = false },
                                translationService = appComponents.translationService,
                                themeService = appComponents.themeService,
                                aiThemeGenerator = appComponents.aiThemeGenerator,
                                logEncryptor = appComponents.logEncryptor,
                                settingsService = appComponents.settingsService,
                                ollamaModelService = appComponents.ollamaModelService,
                                coroutineScope = coroutineScope,
                                onOpenTab = createNewSession,
                                onOpenTabWithMessage = createNewSessionWithMessage
                            )
                        }
                    }
                }
            }
        }
    }
}
