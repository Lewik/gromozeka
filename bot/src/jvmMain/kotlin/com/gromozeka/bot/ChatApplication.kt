package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
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

    var initialized by remember { mutableStateOf(false) }

    var userInput by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    val toolResultsMap = remember { mutableStateMapOf<String, ChatMessage.ContentItem.ToolResult>() }

    val assistantIsThinking = false // Temporarily deactivated

    // Reactive settings state - single source of truth
    val currentSettings by settingsService.settingsFlow.collectAsState()


    var showSessionList by remember { mutableStateOf(true) }
    val currentSession by sessionManager.currentSession.collectAsState(null)
    var showSettingsPanel by remember { mutableStateOf(false) }
    val isWaitingForResponse by currentSession?.isWaitingForResponse?.collectAsState() ?: remember {
        mutableStateOf(
            false
        )
    }


    LaunchedEffect(Unit) {
        initialized = true
        scrollState.animateScrollTo(scrollState.maxValue)

        // Initialize StreamToChatMessageMapper with current response format
        currentSettings?.let { settings ->
            StreamToChatMessageMapper.currentResponseFormat = settings.responseFormat
        }
    }

    // Subscribe to current session's message stream (true streaming)
    LaunchedEffect(currentSession) {
        currentSession?.let { session ->
            session.messageOutputStream.collect { newMessage ->
                println("[ChatApp] Received streaming message: ${newMessage.role}")
                println("[ChatApp] Message content: ${newMessage.content.size} items, first: ${newMessage.content.firstOrNull()?.javaClass?.simpleName}")
                chatHistory.add(newMessage)  // Incremental updates

                // Update toolResultsMap with any ToolResult items from this message
                newMessage.content.filterIsInstance<ChatMessage.ContentItem.ToolResult>().forEach { toolResult ->
                    toolResultsMap[toolResult.toolUseId] = toolResult
                    println("[ChatApp] Updated toolResultsMap with result for tool: ${toolResult.toolUseId}")
                }

                println("[ChatApp] ChatHistory now has ${chatHistory.size} messages, last is ${chatHistory.lastOrNull()?.role}")

                // TTS for ASSISTANT messages (only new ones, not historical)
                if (newMessage.role == ChatMessage.Role.ASSISTANT && !newMessage.isHistorical) {
                    println("[ChatApp] Processing TTS for new assistant message")
                    val content = newMessage.content.firstOrNull()
                    println("[ChatApp] TTS Content type: ${content?.javaClass?.simpleName}")

                    val structured = when (content) {
                        is ChatMessage.ContentItem.AssistantMessage -> content.structured
                        else -> null
                    }

                    if (structured != null) {
                        val ttsText = structured.ttsText
                        println("[ChatApp] TTS text: '$ttsText'")
                        if (!ttsText.isNullOrBlank()) {
                            println("[ChatApp] Enqueueing TTS: '$ttsText'")
                            ttsQueueService.enqueue(TTSQueueService.Task(ttsText, structured.voiceTone ?: ""))
                        }
                    }
                } else if (newMessage.role == ChatMessage.Role.ASSISTANT && newMessage.isHistorical) {
                    println("[ChatApp] Skipping TTS for historical assistant message")
                }
            }
        }
    }

    // Clear history and toolResultsMap when switching sessions
    LaunchedEffect(currentSession) {
        chatHistory.clear()
        toolResultsMap.clear()
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
                // Stop current session via SessionManager 
                sessionManager.currentSessionId.value?.let { currentId ->
                    sessionManager.stopSession(currentId)
                }

                // Create and start new active session via SessionManager
                val sessionId = sessionManager.createSession(projectPath)
                // SessionManager automatically switches to new session and updates currentSession flow
                chatHistory.clear()
                toolResultsMap.clear()
                showSessionList = false
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


    // Auto-scroll to bottom when messages change
    LaunchedEffect(chatHistory.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // Subscribe to PTT events
    LaunchedEffect(Unit) {
        pttEventRouter.textTranscribed.collect { text ->
            if (text.isEmpty()) {
                // Clear input on cancellation
                userInput = ""
            } else {
                // UI decides what to do based on settings
                if (currentSettings.autoSend) {
                    sendMessage(text)
                } else {
                    userInput = text
                }
            }
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
        title = if (settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV) "Gromozeka [DEV]" else "Gromozeka",
        icon = painterResource("logos/logo-256x256.png")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .advancedEscape(pttEventRouter)
        ) {
            if (initialized) {
                if (showSessionList) {
                    SessionListScreen(
                        onSessionMetadataSelected = { session ->
                            // Session already created in SessionListScreen via SessionManager
                            // currentSession will be automatically updated via SessionManager flow
                            
                            // Clear UI state for new session
                            chatHistory.clear()
                            toolResultsMap.clear()
                            
                            showSessionList = false
                        },
                        coroutineScope = coroutineScope,
                        onNewSession = createNewSession,
                        sessionJsonlService = sessionJsonlService,
                        sessionManager = sessionManager,
                        settings = currentSettings,
                        onSettingsChange = onSettingsChange,
                        showSettingsPanel = showSettingsPanel,
                        onShowSettingsPanelChange = { showSettingsPanel = it }
                    )
                } else {
                    ChatScreen(
                        chatHistory = chatHistory,
                        toolResultsMap = toolResultsMap,
                        userInput = userInput,
                        onUserInputChange = { userInput = it },
                        assistantIsThinking = assistantIsThinking,
                        isWaitingForResponse = isWaitingForResponse,
                        autoSend = currentSettings?.autoSend ?: true,
                        onBackToSessionList = {
                            coroutineScope.launch {
                                sessionManager.currentSessionId.value?.let { currentId ->
                                    sessionManager.stopSession(currentId)
                                }
                            }
                            showSessionList = true
                        },
                        onNewSession = {
                            val currentProjectPath = currentSession?.projectPath ?: "/Users/slavik/code/gromozeka"
                            createNewSession(currentProjectPath)
                        },
                        onSendMessage = sendMessage,
                        ttsQueueService = ttsQueueService,
                        coroutineScope = coroutineScope,
                        modifierWithPushToTalk = modifierWithPushToTalk,
                        isRecording = isRecording,
                        isDev = settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV,
                        ttsSpeed = currentSettings?.ttsSpeed ?: 1.0f,
                        onTtsSpeedChange = { newSpeed ->
                            currentSettings?.let { settings ->
                                settingsService.saveSettings(settings.copy(ttsSpeed = newSpeed))
                            }
                        },
                        // Settings integration
                        settings = currentSettings,
                        onSettingsChange = onSettingsChange,
                        showSettingsPanel = showSettingsPanel,
                        onShowSettingsPanelChange = { showSettingsPanel = it }
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