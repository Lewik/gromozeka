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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.*
import com.gromozeka.bot.services.PTTEventRouter
import com.gromozeka.bot.ui.advancedEscape
import com.gromozeka.bot.ui.advancedPttGestures
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
    val unifiedPTTService = context.getBean<UnifiedPTTService>()
    val pttEventRouter = context.getBean<PTTEventRouter>()

    // Explicit startup of TTS queue service
    ttsQueueService.start()
    println("[GROMOZEKA] TTS queue service started")

    // Initialize services
    globalHotkeyService.initializeService()
    unifiedPTTService.initialize()
    pttEventRouter.initialize()
    println("[GROMOZEKA] Starting Compose Desktop UI...")
    application {
        GromozekaTheme {
            ChatWindow(
                ttsQueueService,
                settingsService,
                sessionJsonlService,
                globalHotkeyService,
                unifiedPTTService,
                pttEventRouter,
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
    unifiedPTTService: UnifiedPTTService,
    pttEventRouter: PTTEventRouter,
    context: org.springframework.context.ConfigurableApplicationContext,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val sessionService = remember { context.getBean(SessionService::class.java) }

    var initialized by remember { mutableStateOf(false) }

    var userInput by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    val toolResultsMap = remember { mutableStateMapOf<String, ChatMessage.ContentItem.ToolResult>() }

    val assistantIsThinking = false // Temporarily deactivated

    // Reactive settings state - single source of truth
    val currentSettings by settingsService.settingsFlow.collectAsState()


    var showSessionList by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var currentSession by remember { mutableStateOf<Session?>(null) }
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
            session.sessionId.collectLatest { newSessionId ->
                // Update UI automatically when sessionId changes (handle nullable sessionId)
                newSessionId?.let { id ->
                    selectedSession = selectedSession?.copy(sessionId = id)
                    println("[ChatApp] UI updated with new session ID: $id")
                }
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
                currentSession?.stop()
            }
        }
    }

    val createNewSession: (String) -> Unit = { projectPath ->
        coroutineScope.launch {
            try {
                // Stop existing session if running
                currentSession?.stop()
                currentSession = null

                // Create and start new active session
                val activeSession = sessionService.createSession(projectPath)
                activeSession.start(coroutineScope)
                currentSession = activeSession

                selectedSession = ChatSession(
                    sessionId = "new-session", // Temporary ID, will be updated when real sessionId is captured
                    projectPath = projectPath,
                    firstMessage = "",
                    lastTimestamp = Clock.System.now(),
                    messageCount = 0,
                    preview = "New Session"
                )
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

    // Subscribe to UnifiedPTTService events
    LaunchedEffect(Unit) {
        launch {
            unifiedPTTService.textReceived.collect { text ->
                // Only set userInput if autoSend is disabled
                if (currentSettings?.autoSend != true) {
                    userInput = text
                }
            }
        }
        launch {
            unifiedPTTService.sendMessage.collect { text ->
                sendMessage(text)
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
    val isRecording by unifiedPTTService.recordingState.collectAsState()

    // Settings update handler
    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        // Save to service (this will update the reactive state flow)
        // All dependent services will react automatically via their subscriptions
        settingsService.saveSettings(newSettings)
        
        // Update the response format in StreamToChatMessageMapper immediately
        com.gromozeka.bot.services.StreamToChatMessageMapper.currentResponseFormat = newSettings.responseFormat
    }

    // Create modifier with PTT event router
    val modifierWithPushToTalk = Modifier.advancedPttGestures(pttEventRouter, coroutineScope)

    Window(
        onCloseRequest = {
            println("[GROMOZEKA] Application window closing - stopping all sessions...")
            globalHotkeyService.cleanup()
            ttsQueueService.shutdown()
            exitApplication()
        },
        title = "🤖 Громозека${if (settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV) " [DEV]" else ""}${selectedSession?.projectPath?.let { " • $it" } ?: ""}",
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
                        onSessionSelected = { session, messages, sessionObj ->
                            // Stop current session if any
                            currentSession?.let { currentSess ->
                                coroutineScope.launch { currentSess.stop() }
                            }

                            selectedSession = session
                            currentSession = sessionObj
                            chatHistory.clear()
                            toolResultsMap.clear()
                            chatHistory.addAll(messages)
                            
                            // Fill toolResultsMap with historical tool results
                            messages.flatMap { message -> 
                                message.content.filterIsInstance<ChatMessage.ContentItem.ToolResult>() 
                            }.forEach { toolResult ->
                                toolResultsMap[toolResult.toolUseId] = toolResult
                            }
                            
                            showSessionList = false

                            // Start the session with resume capability
                            coroutineScope.launch {
                                try {
                                    // Pass the old session ID for history loading
                                    sessionObj.start(coroutineScope, resumeSessionId = session.sessionId)

                                    // Session started successfully
                                    println("[ChatApplication] Session started with resume for: ${session.sessionId}")
                                } catch (e: Exception) {
                                    println("[ChatApplication] Failed to start session: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        },
                        coroutineScope = coroutineScope,
                        onNewSession = createNewSession,
                        sessionJsonlService = sessionJsonlService,
                        context = context,
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
                                currentSession?.stop()
                            }
                            showSessionList = true
                        },
                        onNewSession = {
                            val currentProjectPath = selectedSession?.projectPath ?: "/Users/lewik/code/gromozeka"
                            createNewSession(currentProjectPath)
                        },
                        onSendMessage = sendMessage,
                        ttsQueueService = ttsQueueService,
                        coroutineScope = coroutineScope,
                        modifierWithPushToTalk = modifierWithPushToTalk,
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