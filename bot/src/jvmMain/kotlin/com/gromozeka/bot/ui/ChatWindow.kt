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
import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.services.ProjectService
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
    globalHotkeyController: GlobalHotkeyController,
    pttEventRouter: PTTEventRouter,
    pttService: PTTService,
    windowStateService: WindowStateService,
    uiStateService: UIStateService,
    translationService: TranslationService,
    themeService: ThemeService,
    aiThemeGenerator: AIThemeGenerator,
    logEncryptor: LogEncryptor,
    contextExtractionService: ContextExtractionService,
    contextFileService: ContextFileService,
    projectService: ProjectService,
    conversationTreeService: com.gromozeka.shared.services.ConversationTreeService,
    conversationSearchViewModel: com.gromozeka.bot.ui.viewmodel.ConversationSearchViewModel,
) {
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()

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
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showContextsPanel by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

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

    val createNewSessionWithMessage: (String, String) -> Unit =
        { projectPath, initialMessage ->
            coroutineScope.launch {
                try {
                    // Create ChatMessage from user input
                    val chatMessage = ConversationTree.Message(
                        role = ConversationTree.Message.Role.USER,
                        content = listOf(ConversationTree.Message.ContentItem.UserMessage(initialMessage)),
                        instructions = listOf(ConversationTree.Message.Instruction.Source.User),
                        id = ConversationTree.Message.Id(UUID.randomUUID().toString()),
                        timestamp = Clock.System.now()
                    )
                    val tabIndex = appViewModel.createTab(
                        projectPath = projectPath,
                        agent = null, // Use default agent
                        initialMessage = chatMessage,
                        initiator = ConversationInitiator.User
                    )
                    appViewModel.selectTab(tabIndex)
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
                            // Cmd+T - новая сессия
                            event.key == Key.T &&
                                    event.isMetaPressed &&
                                    event.type == KeyEventType.KeyDown -> {
                                // Cmd+T создает новую сессию для текущего проекта
                                if (currentTab != null) {
                                    createNewSession(currentTab!!.projectPath)
                                }
                                true
                            }

                            // Символ § используется как PTT хоткей  
                            event.utf16CodePoint == 167 -> { // § параграф
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
                            // currentTabIndex is null when showing Projects tab (visual index 0)
                            // Otherwise it's the index in tabs array, so add 1 for visual offset
                            val currentIndex = currentTabIndex
                            val selectedTabIndex = if (currentIndex == null) 0 else (currentIndex + 1)
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

                            // Conditional layout based on tab position setting
                            if (!currentSettings.showTabsAtBottom) {
                                // Tabs at top: show TabRow then content
                                tabRowComponent()
                            }

                            // Content area with global 16dp padding according to design system
                            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                                // Tab Content - render SessionScreen for current tab or SessionListScreen
                                Box(modifier = Modifier.weight(1f)) {
                                    if (currentTab != null) {
                                        currentTab?.let { tabViewModel ->
                                            SessionScreen(
                                                viewModel = tabViewModel,

                                                // Navigation callbacks - modified to not stop sessions
                                                onNewSession = {
                                                    createNewSession(currentTab!!.projectPath)
                                                },
                                                onForkSession = {
                                                    // Fork creates new tab with same project
                                                    createNewSession(currentTab!!.projectPath)
                                                },

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
                                    } else {
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
                                            onShowSettingsPanelChange = { showSettingsPanel = it },
                                            refreshTrigger = refreshTrigger
                                        )
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
                            logEncryptor = logEncryptor,
                            settingsService = settingsService,
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

    }
}