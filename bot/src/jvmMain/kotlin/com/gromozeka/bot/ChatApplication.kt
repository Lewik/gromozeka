package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
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
import kotlinx.coroutines.CoroutineScope
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

    // Initialize JAR resources (extract MCP proxy JAR to Gromozeka home)
    val settingsService = context.getBean<SettingsService>()
    println("[GROMOZEKA] Initializing JAR resources...")
    JarResourceManager.ensureMcpProxyJar(settingsService)
    println("[GROMOZEKA] JAR resources initialized successfully")
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
    val mcpHttpServer = context.getBean<McpHttpServer>()

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
    
    // Start global MCP HTTP server
    mcpHttpServer.start()
    println("[GROMOZEKA] Global MCP HTTP server started")

    // Initialize JAR resources (copy from resources to Gromozeka home)
    JarResourceManager.ensureMcpProxyJar(settingsService)
    println("[GROMOZEKA] MCP proxy JAR initialized")

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
        val currentSettings by settingsService.settingsFlow.collectAsState()

        GromozekaTheme(
            themeService = themeService
        ) {
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
                    mcpHttpServer,
                    context
                )
            }  // TranslationProvider
        }  // GromozekaTheme
    }
}

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
    mcpHttpServer: McpHttpServer,
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
            mcpHttpServer.stop()
            println("[GROMOZEKA] Global MCP HTTP server stopped")
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

@Composable
private fun CustomTabRow(
    selectedTabIndex: Int,
    showTabsAtBottom: Boolean,
    tabs: List<com.gromozeka.bot.ui.viewmodel.SessionViewModel>,
    hoveredTabIndex: Int,
    onTabSelect: (Int?) -> Unit,
    onTabHover: (Int) -> Unit,
    onTabHoverExit: () -> Unit,
    onRenameTab: (Int, String) -> Unit,
    coroutineScope: CoroutineScope,
) {
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameTabIndex by remember { mutableStateOf(-1) }
    var renameCurrentName by remember { mutableStateOf("") }

    SecondaryTabRow(
        selectedTabIndex = selectedTabIndex,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier
                    .tabIndicatorOffset(selectedTabIndex, matchContentSize = false)
                    .offset(y = if (showTabsAtBottom) (-46).dp else 0.dp)
            )
        },
        divider = {},
    ) {
        // Projects tab (first tab)
        OptionalTooltip("Проекты") {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = {
                    coroutineScope.launch {
                        onTabSelect(null)
                    }
                },
                text = { Row(verticalAlignment = Alignment.CenterVertically) { 
                    Icon(Icons.Default.Folder, contentDescription = "Sessions list")
                } }
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
                        onTabSelect(index)
                    }
                },
                modifier = Modifier.onPointerEvent(PointerEventType.Enter) {
                    onTabHover(index)
                }
                    .onPointerEvent(PointerEventType.Exit) { onTabHoverExit() },
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
                                Icon(Icons.Default.Edit, contentDescription = "Edit tab name")
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
                    onRenameTab(tabIndexToRename, newName)
                }
            },
            onDismiss = {
                renameDialogOpen = false
                renameTabIndex = -1
                renameCurrentName = ""
            }
        )
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