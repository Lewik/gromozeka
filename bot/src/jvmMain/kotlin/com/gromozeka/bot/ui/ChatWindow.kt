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
import com.gromozeka.shared.domain.Conversation
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
    ollamaModelService: OllamaModelService,
    contextExtractionService: ContextExtractionService,
    contextFileService: ContextFileService,
    projectService: ProjectService,
    conversationTreeService: com.gromozeka.shared.services.ConversationService,
    conversationSearchViewModel: com.gromozeka.bot.ui.viewmodel.ConversationSearchViewModel,
    loadingViewModel: com.gromozeka.bot.ui.viewmodel.LoadingViewModel,
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
    var isLoadingComplete by remember { mutableStateOf(false) }

    val currentSettings by settingsService.settingsFlow.collectAsState()

    val translation = LocalTranslation.current


    val tabs by appViewModel.tabs.collectAsState()
    val currentTabIndex by appViewModel.currentTabIndex.collectAsState()
    val currentTab by appViewModel.currentTab.collectAsState()
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showContextsPanel by remember { mutableStateOf(false) }
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
                        id = Conversation.Message.Id(UUID.randomUUID().toString()),
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

                            if (!currentSettings.showTabsAtBottom) {
                                tabRowComponent()
                            }

                            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (currentTab != null) {
                                        currentTab?.let { tabViewModel ->
                                            SessionScreen(
                                                viewModel = tabViewModel,

                                                onNewSession = {
                                                    createNewSession(currentTab!!.projectPath)
                                                },
                                                onForkSession = {
                                                    coroutineScope.launch {
                                                        try {
                                                            val currentConversationId = currentTab!!.uiState.first().conversationId
                                                            val forkedConversation = conversationTreeService.fork(currentConversationId)
                                                            
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

                                                onShowContextsPanel = { showContextsPanel = true },

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
                            coroutineScope = coroutineScope,
                            onOpenTab = createNewSession,
                            onOpenTabWithMessage = createNewSessionWithMessage
                        )

                        ContextsPanel(
                            isVisible = showContextsPanel,
                            onClose = { showContextsPanel = false },
                            viewModel = contextsPanelViewModel
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
