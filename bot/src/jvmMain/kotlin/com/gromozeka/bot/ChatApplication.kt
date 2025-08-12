package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.gromozeka.bot.services.*
import com.gromozeka.shared.domain.session.toClaudeSessionUuid
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.*
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
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
    val globalHotkeyService = context.getBean<GlobalHotkeyService>()
    val pttEventRouter = context.getBean<PTTEventRouter>()
    val pttService = context.getBean<PTTService>()
    val windowStateService = context.getBean<WindowStateService>()

    // Explicit startup of TTS queue service
    ttsQueueService.start()
    println("[GROMOZEKA] TTS queue service started")

    // Initialize services
    globalHotkeyService.initializeService()
    pttEventRouter.initialize()
    println("[GROMOZEKA] Starting Compose Desktop UI...")
    application {
        GromozekaTheme {
            ChatWindow(
                ttsQueueService,
                settingsService,
                sessionJsonlService,
                globalHotkeyService,
                pttEventRouter,
                pttService,
                windowStateService,
                context
            )
        }
    }
}

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    ttsQueueService: TTSQueueService,
    settingsService: SettingsService,
    sessionJsonlService: SessionJsonlService,
    globalHotkeyService: GlobalHotkeyService,
    pttEventRouter: PTTEventRouter,
    pttService: PTTService,
    windowStateService: WindowStateService,
    context: org.springframework.context.ConfigurableApplicationContext,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val sessionManager = remember { context.getBean(SessionManager::class.java) }
    val sessionUiManager = remember { context.getBean(SessionUiManager::class.java) }

    var initialized by remember { mutableStateOf(false) }


    // Reactive settings state - single source of truth
    val currentSettings by settingsService.settingsFlow.collectAsState()


    val activeSessions by sessionManager.activeSessions.collectAsState()
    val currentSessionId by sessionUiManager.currentSessionId.collectAsState()
    val currentSession by sessionUiManager.currentSession.collectAsState(null)
    var showSettingsPanel by remember { mutableStateOf(false) }


    LaunchedEffect(Unit) {
        initialized = true

        // Initialize StreamToChatMessageMapper with current response format
        currentSettings?.let { settings ->
            StreamToChatMessageMapper.currentResponseFormat = settings.responseFormat
        }
    }


    // Subscribe to current session's sessionId changes  
    LaunchedEffect(currentSession) {
        currentSession?.let { session ->
            session.claudeSessionId.collectLatest { newSessionId ->
                // Session ID changes are now handled automatically via currentSession flow
                println("[ChatApp] UI updated with new session ID: $newSessionId")
            }
        }
    }

    // Subscribe to current session's events
    LaunchedEffect(currentSession) {
        currentSession?.let { session ->
            session.events.collectLatest { event ->
                when (event) {
                    is com.gromozeka.bot.model.StreamSessionEvent.MessagesUpdated -> {
                        println("[ChatApp] Messages updated: ${event.messageCount} messages")
                    }

                    is com.gromozeka.bot.model.StreamSessionEvent.Error -> {
                        println("[ChatApp] Session error: ${event.message}")
                    }

                    is com.gromozeka.bot.model.StreamSessionEvent.SessionIdChangedOnStart -> {
                        println("[ChatApp] Session ID changed to: ${event.newSessionId}")
                        // UI is automatically updated via sessionId StateFlow subscription above
                        // No manual session replacement needed!
                    }

                    else -> println("[ChatApp] Session event: $event")
                }
            }
        }
    }

    // Cleanup when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                sessionManager.stopAllSessions()
            }
        }
    }

    val createNewSession: (String) -> Unit = { projectPath ->
        coroutineScope.launch {
            try {
                // Explicit workflow: Session → ViewModel → Navigation
                val sessionId = sessionManager.createSession(projectPath)
                sessionUiManager.createViewModel(sessionId)
                sessionUiManager.setCurrentSession(sessionId)
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



    // Set interrupt executor for current session
    LaunchedEffect(currentSession) {
        pttEventRouter.setInterruptExecutor {
            currentSession?.sendInterrupt() ?: false
        }
    }

    // Get recording state from service
    val isRecording by pttService.recordingState.collectAsState()

    // Settings update handler
    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        // Save to service (this will update the reactive state flow)
        // All dependent services will react automatically via their subscriptions
        settingsService.saveSettings(newSettings)

        // Update the response format in StreamToChatMessageMapper immediately
        StreamToChatMessageMapper.currentResponseFormat = newSettings.responseFormat
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

            // Stop all sessions via SessionManager
            coroutineScope.launch {
                try {
                    sessionManager.stopAllSessions()
                    println("[GROMOZEKA] All sessions stopped via SessionManager")
                } catch (e: Exception) {
                    println("[GROMOZEKA] Error stopping sessions: ${e.message}")
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
                    val tabTitles = mutableListOf("📁")
                    tabTitles.addAll(activeSessions.keys.mapIndexed { index, _ -> "Таб ${index + 1}" })
                    
                    // Determine selected tab index
                    val selectedTabIndex = when {
                        currentSessionId == null -> 0 // SessionListScreen tab
                        else -> {
                            val sessionIndex = activeSessions.keys.toList().indexOf(currentSessionId)
                            if (sessionIndex >= 0) sessionIndex + 1 else 0
                        }
                    }
                    
                    // Tab Row
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier
                    ) {
                        tabTitles.forEachIndexed { index, title ->
                            OptionalTooltip(if (index == 0) "Проекты" else null) {
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = {
                                        if (index == 0) {
                                            coroutineScope.launch {
                                                sessionUiManager.setCurrentSession(null)
                                            }
                                        } else {
                                            val sessionId = activeSessions.keys.toList()[index - 1]
                                            coroutineScope.launch {
                                                sessionUiManager.setCurrentSession(sessionId)
                                            }
                                        }
                                    },
                                    text = { Text(title) }
                                )
                            }
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
                                sessionUiManager = sessionUiManager,
                                settings = currentSettings,
                                onSettingsChange = onSettingsChange,
                                showSettingsPanel = showSettingsPanel,
                                onShowSettingsPanelChange = { showSettingsPanel = it }
                            )
                        }
                        
                        // Only render SessionScreen for current session
                        val currentSessionViewModel by sessionUiManager.currentSessionViewModel.collectAsState(null)
                        if (currentSessionId != null && currentSession != null) {
                            currentSessionViewModel?.let { viewModel ->
                                SessionScreen(
                                    viewModel = viewModel,
                                    
                                    // Navigation callbacks - modified to not stop sessions
                                    onBackToSessionList = {
                                        // Switch to SessionListScreen tab without stopping session
                                        coroutineScope.launch {
                                            sessionUiManager.setCurrentSession(null)
                                        }
                                    },
                                    onNewSession = {
                                        createNewSession(currentSession!!.projectPath)
                                    },
                                    
                                    // Close tab callback - removes ViewModel and stops session
                                    onCloseTab = {
                                        coroutineScope.launch {
                                            val sessionId = currentSessionId!!
                                            sessionUiManager.removeViewModel(sessionId)
                                            sessionManager.stopSession(sessionId)
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