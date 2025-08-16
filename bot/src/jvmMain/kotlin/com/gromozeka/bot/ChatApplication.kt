package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.gromozeka.bot.platform.GlobalHotkeyController
import com.gromozeka.bot.services.*
import com.gromozeka.bot.services.theming.ThemeService
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.*
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.File

/**
 * Format folder name to human readable format
 * Converts: kebab-case, snake_case, camelCase, PascalCase to "Capitalized Words"
 * Also handles parent folder in the format "Parent / Child"
 */
fun formatProjectName(projectPath: String): String {
    val projectFile = File(projectPath)
    val projectName = projectFile.name.takeIf { it.isNotBlank() } ?: return "Unknown Project"
    val parentName = projectFile.parentFile?.name?.takeIf { it.isNotBlank() }

    fun formatFolderName(name: String): String {
        return name
            // Replace hyphens and underscores with spaces
            .replace(Regex("[-_]"), " ")
            // Split camelCase and PascalCase (insert space before uppercase letters)
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            // Split sequences of digits from letters
            .replace(Regex("(?<=[a-zA-Z])(?=\\d)|(?<=\\d)(?=[a-zA-Z])"), " ")
            // Split multiple words, normalize whitespace
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            // Capitalize each word
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    val formattedProject = formatFolderName(projectName)
    val formattedParent = parentName?.let { formatFolderName(it) }

    return if (formattedParent != null && formattedParent != formattedProject) {
        "$formattedParent / $formattedProject"
    } else {
        formattedProject
    }
}

/**
 * Get display name for a tab based on custom name or formatted project path
 */
fun getTabDisplayName(tabUiState: UIState.Tab, index: Int): String {
    return tabUiState.customName?.takeIf { it.isNotBlank() }
        ?: formatProjectName(tabUiState.projectPath)
}

fun main() {
    System.setProperty("java.awt.headless", "false")

    // Check Claude Code is installed before starting
    val claudeProjectsDir = File(System.getProperty("user.home"), ".claude/projects")
    if (!claudeProjectsDir.exists()) {
        throw IllegalStateException("Claude Code not installed - directory does not exist: ${claudeProjectsDir.absolutePath}")
    }
    println("[GROMOZEKA] Claude Code installation verified")

    // Clean up old stream logs on startup
    println("[GROMOZEKA] Cleaning up old stream logs...")
    StreamLogger.cleanupOldLogs()

    println("[GROMOZEKA] Initializing Spring context...")
    val context = SpringApplicationBuilder(ChatApplication::class.java)
        .web(WebApplicationType.NONE)
        .run()
    println("[GROMOZEKA] Spring context initialized successfully")

    val settingsService = context.getBean<SettingsService>()
    println("[GROMOZEKA] Starting application in ${settingsService.mode.name} mode...")

    val ttsQueueService = context.getBean<TTSQueueService>()
    val ttsAutoplayService = context.getBean<TTSAutoplayService>()
    val sessionJsonlService = context.getBean<SessionJsonlService>()
    val sessionSearchService = context.getBean<SessionSearchService>()
    val sessionManager = context.getBean<SessionManager>()
    val globalHotkeyController = context.getBean<GlobalHotkeyController>()
    val pttEventRouter = context.getBean<PTTEventRouter>()
    val pttService = context.getBean<PTTService>()
    val windowStateService = context.getBean<WindowStateService>()
    val UIStateService = context.getBean<UIStateService>()
    val appViewModel = context.getBean<AppViewModel>()
    val translationService = context.getBean<TranslationService>()
    val themeService = context.getBean<ThemeService>()
    val screenCaptureController = context.getBean<com.gromozeka.bot.platform.ScreenCaptureController>()

    // Create AI theme generator
    val aiThemeGenerator = com.gromozeka.bot.services.theming.AIThemeGenerator(
        screenCaptureController = screenCaptureController,
        sessionManager = sessionManager,
        settingsService = settingsService
    )

    // Explicit startup of TTS services
    ttsQueueService.start()
    println("[GROMOZEKA] TTS queue service started")

    ttsAutoplayService.start()
    println("[GROMOZEKA] TTS autoplay service started")

    // Initialize services
    globalHotkeyController.initializeService()
    pttEventRouter.initialize()

    // Initialize UIStateService (loads state, restores sessions, starts subscription)
    runBlocking {
        UIStateService.initialize(appViewModel)
    }

    // TranslationService automatically initializes via @Bean creation and subscribes to settings

    println("[GROMOZEKA] Starting Compose Desktop UI...")
    application {
        GromozekaTheme(themeService) {
            TranslationProvider(translationService) {
                ChatWindow(
                    appViewModel,
                    ttsQueueService,
                    settingsService,
                    sessionJsonlService,
                    sessionSearchService,
                    sessionManager,
                    globalHotkeyController,
                    pttEventRouter,
                    pttService,
                    windowStateService,
                    UIStateService,
                    translationService,
                    themeService,
                    screenCaptureController,
                    aiThemeGenerator,
                    context
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
    UIStateService: UIStateService,
    translationService: TranslationService,
    themeService: ThemeService,
    screenCaptureController: com.gromozeka.bot.platform.ScreenCaptureController,
    aiThemeGenerator: com.gromozeka.bot.services.theming.AIThemeGenerator,
    context: org.springframework.context.ConfigurableApplicationContext,
) {
    val coroutineScope = rememberCoroutineScope()
    val sessionSearchViewModel = remember {
        com.gromozeka.bot.ui.viewmodel.SessionSearchViewModel(sessionSearchService, coroutineScope)
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
                val tabIndex = appViewModel.createTab(projectPath)
                appViewModel.selectTab(tabIndex)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val createNewSessionWithMessage: (String, String) -> Unit = { projectPath, initialMessage ->
        coroutineScope.launch {
            try {
                val tabIndex = appViewModel.createTab(projectPath, null, initialMessage)
                appViewModel.selectTab(tabIndex)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    val sendMessage: suspend (String) -> Unit = { message ->
        println("[ChatApp] SEND MESSAGE CALLED: $message")
        try {
            currentSession?.sendMessage(message)
        } catch (e: Exception) {
            println("[ChatApp] Failed to send message: ${e.message}")
            e.printStackTrace()
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
            println("[GROMOZEKA] Application window closing - stopping all sessions...")

            // Force save current state before cleanup
            UIStateService.forceSave()

            // Disable auto-save during cleanup to prevent saving empty state
            UIStateService.disableAutoSave()

            coroutineScope.launch {
                try {
                    appViewModel.cleanup()
                    println("[GROMOZEKA] All sessions stopped and UI state saved")
                } catch (e: Exception) {
                    println("[GROMOZEKA] Error during cleanup: ${e.message}")
                    e.printStackTrace()
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
            exitApplication()
        },
        title = buildString {
            append(translation.appName)
            if (currentSettings.alwaysOnTop) {
                append(translation.alwaysOnTopSuffix)
            }
            if (settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV) {
                append(translation.devModeSuffix)
            }
        },
        icon = painterResource("logos/logo-256x256.png")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .focusTarget()
                .advancedEscape(pttEventRouter)
                .onKeyEvent { event ->
                    if (event.key == Key.T &&
                        event.isMetaPressed &&
                        event.type == KeyEventType.KeyDown
                    ) {

                        // Cmd+T работает только на экране сессии (не на экране проектов)
                        val selectedTabIndex = currentTabIndex?.plus(1) ?: 0
                        if (selectedTabIndex > 0 && currentSession != null) {
                            createNewSession(currentSession!!.projectPath)
                        }
                        true
                    } else false
                }
        ) {
            if (initialized) {
                // Tab-based UI: SessionListScreen as first tab, active sessions as additional tabs
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(1f)) {

                        // Determine selected tab index (0 = projects, 1+ = sessions)
                        val selectedTabIndex = currentTabIndex?.plus(1) ?: 0

                        // Tab Row
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Projects tab (first tab)
                            OptionalTooltip("Проекты") {
                                Tab(
                                    selected = selectedTabIndex == 0,
                                    onClick = {
                                        coroutineScope.launch {
                                            appViewModel.selectTab(null)
                                            // Trigger refresh when switching to projects tab
                                            sessionListRefreshTrigger++
                                        }
                                    },
                                    text = { Text("📁") }
                                )
                            }

                            // Session tabs with loading indicators and edit button
                            tabs.forEachIndexed { index, tab ->
                                val isLoading = tab.isWaitingForResponse.collectAsState().value
                                val tabUiState = tab.uiState.collectAsState().value
                                val tabIndex = index + 1

                                Tab(
                                    selected = selectedTabIndex == tabIndex,
                                    onClick = {
                                        coroutineScope.launch {
                                            appViewModel.selectTab(index)
                                        }
                                    },
                                    modifier = Modifier.onPointerEvent(PointerEventType.Enter) {
                                        hoveredTabIndex = index
                                    }
                                        .onPointerEvent(PointerEventType.Exit) { hoveredTabIndex = -1 },
                                    text = {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(getTabDisplayName(tabUiState, index))

                                            // Edit button (pencil) - appears on hover
                                            if (hoveredTabIndex == index) {
                                                IconButton(
                                                    onClick = {
                                                        renameTabIndex = index
                                                        renameCurrentName = getTabDisplayName(tabUiState, index)
                                                        renameDialogOpen = true
                                                    },
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .align(Alignment.CenterStart)
                                                        .offset(x = (-8).dp)
                                                ) {
                                                    Text("✏️", fontSize = 10.sp)
                                                }
                                            }

                                            if (isLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier
                                                        .size(12.dp)
                                                        .align(Alignment.CenterEnd),
                                                    strokeWidth = 1.5.dp
                                                )
                                            }
                                        }
                                    }
                                )
                            }

                            // Rename dialog
                            TabRenameDialog(
                                isOpen = renameDialogOpen,
                                currentName = renameCurrentName,
                                onRename = { newName ->
                                    val tabIndexToRename = renameTabIndex
                                    coroutineScope.launch {
                                        appViewModel.renameTab(tabIndexToRename, newName)
                                    }
                                },
                                onDismiss = {
                                    renameDialogOpen = false
                                    renameTabIndex = -1
                                    renameCurrentName = ""
                                }
                            )
                        }

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
                                currentTab?.let { sessionViewModel ->
                                    SessionScreen(
                                        viewModel = sessionViewModel,

                                        // Navigation callbacks - modified to not stop sessions
                                        onBackToSessionList = {
                                            // Switch to SessionListScreen tab without stopping session
                                            coroutineScope.launch {
                                                appViewModel.selectTab(null)
                                            }
                                        },
                                        onNewSession = {
                                            createNewSession(currentSession!!.projectPath)
                                        },
                                        onOpenTab = createNewSession, // Reuse the same function for opening tabs
                                        onOpenTabWithMessage = createNewSessionWithMessage, // For AI theme generation with initial message

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

                                        // Dev mode
                                        isDev = settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV,
                                    )
                                }
                            }
                        }
                    }

                    // Settings Panel
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

                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@SpringBootApplication(
    exclude = [
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration::class
    ]
)
class ChatApplication