package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.gromozeka.bot.services.*
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.*
import com.gromozeka.bot.viewmodel.AppViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.File

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
    val sessionJsonlService = context.getBean<SessionJsonlService>()
    val sessionSearchService = context.getBean<SessionSearchService>()
    val globalHotkeyService = context.getBean<GlobalHotkeyService>()
    val pttEventRouter = context.getBean<PTTEventRouter>()
    val pttService = context.getBean<PTTService>()
    val windowStateService = context.getBean<WindowStateService>()
    val appUiStateService = context.getBean<AppUiStateService>()
    val appViewModel = context.getBean<AppViewModel>()

    // Explicit startup of TTS queue service
    ttsQueueService.start()
    println("[GROMOZEKA] TTS queue service started")

    // Initialize services
    globalHotkeyService.initializeService()
    pttEventRouter.initialize()

    // Initialize AppUiStateService (loads state, restores tabs, starts subscription)
    runBlocking {
        appUiStateService.initialize(appViewModel)
    }
    println("[GROMOZEKA] Starting Compose Desktop UI...")
    application {
        GromozekaTheme {
            ChatWindow(
                appViewModel,
                ttsQueueService,
                settingsService,
                sessionJsonlService,
                sessionSearchService,
                globalHotkeyService,
                pttEventRouter,
                pttService,
                windowStateService,
                appUiStateService,
                context
            )
        }
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
    globalHotkeyService: GlobalHotkeyService,
    pttEventRouter: PTTEventRouter,
    pttService: PTTService,
    windowStateService: WindowStateService,
    appUiStateService: AppUiStateService,
    context: org.springframework.context.ConfigurableApplicationContext,
) {
    val coroutineScope = rememberCoroutineScope()
    val sessionManager = remember { context.getBean(SessionManager::class.java) }
    val sessionSearchViewModel = remember { 
        com.gromozeka.bot.viewmodel.SessionSearchViewModel(sessionSearchService, coroutineScope) 
    }

    var initialized by remember { mutableStateOf(false) }


    // Reactive settings state - single source of truth
    val currentSettings by settingsService.settingsFlow.collectAsState()


    val tabs by appViewModel.tabs.collectAsState()
    val currentTabIndex by appViewModel.currentTabIndex.collectAsState()
    val currentTab by appViewModel.currentTab.collectAsState()
    val currentSession by appViewModel.currentSession.collectAsState()
    var showSettingsPanel by remember { mutableStateOf(false) }
    var sessionListRefreshTrigger by remember { mutableStateOf(0) }

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

            // Save UI state BEFORE cleanup
            appUiStateService.saveCurrentState()

            // Disable auto-save during cleanup to prevent saving empty state
            appUiStateService.disableAutoSave()

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

            globalHotkeyService.cleanup()
            ttsQueueService.shutdown()
            exitApplication()
        },
        title = buildString {
            append("Gromozeka")
            if (currentSettings.alwaysOnTop) {
                append(" [Always On Top]")
            }
            if (settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV) {
                append(" [DEV]")
            }
        },
        icon = painterResource("logos/logo-256x256.png")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .advancedEscape(pttEventRouter)
        ) {
            if (initialized) {
                // Tab-based UI: SessionListScreen as first tab, active sessions as additional tabs
                Column(modifier = Modifier.fillMaxSize()) {

                    // Determine selected tab index (0 = projects, 1+ = tabs)
                    val selectedTabIndex = currentTabIndex?.plus(1) ?: 0

                    // Tab Row
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier
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

                        // Session tabs with loading indicators
                        tabs.forEachIndexed { index, tab ->
                            val isLoading = tab.isWaitingForResponse.collectAsState().value
                            val tabIndex = index + 1

                            Tab(
                                selected = selectedTabIndex == tabIndex,
                                onClick = {
                                    coroutineScope.launch {
                                        appViewModel.selectTab(index)
                                    }
                                },
                                text = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Таб ${index + 1}")

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
                                settings = currentSettings,
                                onSettingsChange = onSettingsChange,
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
                                    onBackToSessionList = {
                                        // Switch to SessionListScreen tab without stopping session
                                        coroutineScope.launch {
                                            appViewModel.selectTab(null)
                                        }
                                    },
                                    onNewSession = {
                                        createNewSession(currentSession!!.projectPath)
                                    },

                                    // Close tab callback - removes tab and stops session
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
                                    onSettingsChange = onSettingsChange,
                                    showSettingsPanel = showSettingsPanel,
                                    onShowSettingsPanelChange = { showSettingsPanel = it },

                                    // Dev mode
                                    isDev = settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV,
                                )
                            }
                        }
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