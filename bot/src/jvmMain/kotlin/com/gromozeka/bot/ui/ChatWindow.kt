package com.gromozeka.bot.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.gromozeka.bot.model.AgentDefinition
import com.gromozeka.bot.model.HookDecision
import com.gromozeka.bot.platform.GlobalHotkeyController
import com.gromozeka.bot.services.*
import com.gromozeka.bot.services.theming.AIThemeGenerator
import com.gromozeka.bot.services.theming.ThemeService
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.session.SessionScreen
import com.gromozeka.bot.ui.state.ConversationInitiator
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.bot.ui.viewmodel.ContextsPanelViewModel
import com.gromozeka.bot.ui.viewmodel.SessionSearchViewModel
import com.gromozeka.shared.domain.message.ChatMessage
import klog.KLoggers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Clock

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    appViewModel: AppViewModel,
    ttsQueueService: TTSQueueService,
    settingsService: SettingsService,
    sessionJsonlService: SessionJsonlService,
    sessionSearchService: SessionSearchService,
    sessionManager: SessionManager,
    globalHotkeyController: GlobalHotkeyController,
    pttEventRouter: PTTEventRouter,
    pttService: PTTService,
    windowStateService: WindowStateService,
    uiStateService: UIStateService,
    translationService: TranslationService,
    themeService: ThemeService,
    aiThemeGenerator: AIThemeGenerator,
    mcpHttpServer: McpHttpServer,
    hookPermissionService: HookPermissionService,
    contextExtractionService: ContextExtractionService,
    contextFileService: ContextFileService,
) {
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()
    val sessionSearchViewModel = remember {
        SessionSearchViewModel(sessionSearchService, coroutineScope)
    }

    val contextsPanelViewModel = remember {
        ContextsPanelViewModel(
            contextFileService = contextFileService,
            contextExtractionService = contextExtractionService,
            appViewModel = appViewModel,
            scope = coroutineScope
        )
    }

    var initialized by remember { mutableStateOf(false) }


    // Reactive settings state - single source of truth
    val currentSettings by settingsService.settingsFlow.collectAsState()

    // Get current translation for UI strings
    val translation = LocalTranslation.current


    val tabs by appViewModel.tabs.collectAsState()
    val currentTabIndex by appViewModel.currentTabIndex.collectAsState()
    val currentTab by appViewModel.currentTab.collectAsState()
    val currentSession by appViewModel.currentSession.collectAsState()
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showContextsPanel by remember { mutableStateOf(false) }
    var sessionListRefreshTrigger by remember { mutableStateOf(0) }

    // State for rename dialog
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameTabIndex by remember { mutableStateOf(-1) }
    var renameCurrentName by remember { mutableStateOf("") }

    // State for tab hover
    var hoveredTabIndex by remember { mutableStateOf(-1) }


    // Initialization
    LaunchedEffect(Unit) {
        initialized = true
    }


    // Cleanup when composable is disposed
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

    val createNewSessionWithMessage: (String, String, AgentDefinition) -> Unit =
        { projectPath, initialMessage, agentDefinition ->
            coroutineScope.launch {
                try {
                    // Create ChatMessage from user input
                    val chatMessage = ChatMessage(
                        role = ChatMessage.Role.USER,
                        content = listOf(ChatMessage.ContentItem.UserMessage(initialMessage)),
                        instructions = listOf(ChatMessage.Instruction.Source.User),
                        uuid = UUID.randomUUID().toString(),
                        timestamp = Clock.System.now(),
                        llmSpecificMetadata = null
                    )
                    val tabIndex = appViewModel.createTab(
                        projectPath = projectPath,
                        agentDefinition = agentDefinition,
                        initialMessage = chatMessage,
                        initiator = ConversationInitiator.User
                    )
                    appViewModel.selectTab(tabIndex)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    val createForkSession: () -> Unit = {
        coroutineScope.launch {
            try {
                val currentClaudeSessionId = currentSession?.claudeSessionId?.first()?.value
                if (currentClaudeSessionId != null) {
                    val tabIndex = appViewModel.createTab(
                        projectPath = currentSession!!.projectPath,
                        resumeSessionId = currentClaudeSessionId,
                        initialMessage = null, // No initial message for fork
                        initiator = ConversationInitiator.User
                    )
                    appViewModel.selectTab(tabIndex)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Get recording state from service
    val isRecording by pttService.recordingState.collectAsState()

    // Settings update handler
    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        // Save to service (this will update the reactive state flow)
        // All dependent services will react automatically via their subscriptions
        settingsService.saveSettings(newSettings)

        // Note: Use "Check Override" button in settings to update translations

        // Response format is now automatically updated in StreamToChatMessageMapper via settings subscription
    }

    // Create modifier with PTT event router
    val modifierWithPushToTalk = Modifier.advancedPttGestures(pttEventRouter, coroutineScope)
    
    // Local keyboard PTT gesture detector
    val keyboardPttGestureDetector = remember { 
        UnifiedGestureDetector(pttEventRouter, coroutineScope)
    }

    // Load window state
    val savedWindowState = remember { windowStateService.loadWindowState() }

    // Window state for position tracking
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

            // Force save current state before cleanup
            uiStateService.forceSave()

            // Disable auto-save during cleanup to prevent saving empty state
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
            mcpHttpServer.stop()
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
                    .onKeyEvent { event ->
                        
                        when {
                            // Cmd+T - новая сессия
                            event.key == Key.T &&
                            event.isMetaPressed &&
                            event.type == KeyEventType.KeyDown -> {
                                // Cmd+T работает только на экране сессии (не на экране проектов)
                                val selectedTabIndex = currentTabIndex?.plus(1) ?: 0
                                if (selectedTabIndex > 0 && currentSession != null) {
                                    createNewSession(currentSession!!.projectPath)
                                }
                                true
                            }
                            
                            // Backtick клавиша для PTT (тестирование)
                            event.key == Key.Grave -> { // ` backtick для PTT
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
                    // Root layout: Row with main area and settings panel
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Main area with tabs and content
                        Column(modifier = Modifier.weight(1f)) {
                            // Tab Row - position based on showTabsAtBottom setting
                            val selectedTabIndex = currentTabIndex?.plus(1) ?: 0
                            val tabRowComponent = @Composable {
                                CustomTabRow(
                                    selectedTabIndex = selectedTabIndex,
                                    showTabsAtBottom = currentSettings.showTabsAtBottom,
                                    tabs = tabs,
                                    hoveredTabIndex = hoveredTabIndex,
                                    onTabSelect = { tabIndex ->
                                        if (tabIndex == null) {
                                            coroutineScope.launch {
                                                appViewModel.selectTab(null)
                                                // Trigger refresh when switching to projects tab
                                                sessionListRefreshTrigger++
                                            }
                                        } else {
                                            coroutineScope.launch {
                                                appViewModel.selectTab(tabIndex)
                                            }
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

                            // Conditional layout based on tab position setting
                            if (!currentSettings.showTabsAtBottom) {
                                // Tabs at top: show TabRow then content
                                tabRowComponent()
                            }

                            // Content area with global 16dp padding according to design system
                            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                                // Tab Content - All tabs exist in parallel, only selected is visible
                                Box(modifier = Modifier.weight(1f)) {
                                    // SessionListScreen tab - always exists
                                    val isSessionListVisible = selectedTabIndex == 0
                                    Box(
                                        modifier = Modifier.fillMaxSize()
                                            .alpha(if (isSessionListVisible) 1f else 0f)
                                    ) {
                                        SessionListScreen(
                                            onSessionMetadataSelected = { session ->
                                                // Session and ViewModel already created in SessionListScreen
                                                // Tab UI will automatically switch to the new session
                                            },
                                            coroutineScope = coroutineScope,
                                            onNewSession = createNewSession,
                                            sessionJsonlService = sessionJsonlService,
                                            sessionManager = sessionManager,
                                            appViewModel = appViewModel,
                                            searchViewModel = sessionSearchViewModel,
                                            showSettingsPanel = showSettingsPanel,
                                            onShowSettingsPanelChange = { showSettingsPanel = it },
                                            refreshTrigger = sessionListRefreshTrigger
                                        )
                                    }

                                    // Only render SessionScreen for current tab
                                    if (currentTab != null && currentSession != null) {
                                        currentTab?.let { tabViewModel ->
                                            SessionScreen(
                                                viewModel = tabViewModel,

                                                // Navigation callbacks - modified to not stop sessions
                                                onNewSession = {
                                                    createNewSession(currentSession!!.projectPath)
                                                },
                                                onForkSession = createForkSession,
                                                // Reuse the same function for opening tabs
                                                // For AI theme generation with initial message

                                                // Close session callback - removes session and stops it
                                                onCloseTab = {
                                                    coroutineScope.launch {
                                                        currentTabIndex?.let { index ->
                                                            appViewModel.closeTab(index)
                                                        }
                                                    }
                                                },

                                                // Services
                                                ttsQueueService = ttsQueueService,
                                                coroutineScope = coroutineScope,
                                                modifierWithPushToTalk = modifierWithPushToTalk,
                                                isRecording = isRecording,

                                                // Settings
                                                settings = currentSettings,
                                                showSettingsPanel = showSettingsPanel,
                                                onShowSettingsPanelChange = { showSettingsPanel = it },

                                                // Context extraction
                                                onExtractContexts = {
                                                    coroutineScope.launch {
                                                        try {
                                                            contextExtractionService.extractContextsFromTab(tabViewModel.uiState.first().tabId)
                                                            log.info("Context extraction")
                                                        } catch (e: Exception) {
                                                            log.warn(e) { "Context extraction failed: ${e.message}" }
                                                        }
                                                    }
                                                },

                                                // Context panel
                                                onShowContextsPanel = { showContextsPanel = true },

                                                // Dev mode
                                                isDev = settingsService.mode == AppMode.DEV,
                                            )
                                        }
                                    }
                                }
                            }

                            // Conditional layout based on tab position setting
                            if (currentSettings.showTabsAtBottom) {
                                // Tabs at bottom: show TabRow after content
                                tabRowComponent()
                            }
                        }

                        // Settings Panel - now at the root level, outside of the tab area
                        SettingsPanel(
                            isVisible = showSettingsPanel,
                            settings = currentSettings,
                            onSettingsChange = onSettingsChange,
                            onClose = { showSettingsPanel = false },
                            translationService = translationService,
                            themeService = themeService,
                            aiThemeGenerator = aiThemeGenerator,
                            coroutineScope = coroutineScope,
                            onOpenTab = createNewSession,
                            onOpenTabWithMessage = createNewSessionWithMessage
                        )

                        // Contexts Panel - positioned at the root level for full height overlay
                        ContextsPanel(
                            isVisible = showContextsPanel,
                            onClose = { showContextsPanel = false },
                            viewModel = contextsPanelViewModel
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        // Hook permission dialog - session-aware через TabViewModel
        val currentTabHookPayload by (currentTab?.claudeHookPayload ?: flowOf(null)).collectAsState(initial = null)
        ClaudeHookPermissionDialog(
            hookPayload = currentTabHookPayload,
            onDecision = { decision ->
                currentTabHookPayload?.let { payload ->
                    coroutineScope.launch {
                        hookPermissionService.sendCommand(
                            HookPermissionService.Command.ResolveRequest(payload.session_id, decision)
                        )
                    }
                }
            },
            onDismiss = {
                currentTabHookPayload?.let { payload ->
                    coroutineScope.launch {
                        hookPermissionService.sendCommand(
                            HookPermissionService.Command.ResolveRequest(
                                sessionId = payload.session_id,
                                decision = HookDecision(
                                    allow = false,
                                    reason = "User dismissed the dialog"
                                )
                            )
                        )
                    }
                }
            }
        )
    }
}