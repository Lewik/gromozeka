package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.*
import com.gromozeka.bot.ui.ChatScreen
import com.gromozeka.bot.ui.CompactButton
import com.gromozeka.bot.ui.GromozekaTheme
import com.gromozeka.bot.ui.SessionListScreen
import com.gromozeka.bot.ui.onEscape
import com.gromozeka.bot.ui.pttGestures
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
    settingsService.initialize()
    println("[GROMOZEKA] Starting application in ${settingsService.mode.name} mode...")

    val sttService = context.getBean<SttService>()
    val ttsService = context.getBean<TtsService>()
    val ttsQueueService = context.getBean<TTSQueueService>()
    val openAiBalanceService = context.getBean<OpenAiBalanceService>()
    val sessionJsonlService = context.getBean<SessionJsonlService>()
    val globalHotkeyService = context.getBean<GlobalHotkeyService>()

    // Explicit startup of TTS queue service
    ttsQueueService.start()
    println("[GROMOZEKA] TTS queue service started")

    // Initialize hotkey service if enabled in settings
    if (settingsService.settings.globalPttHotkeyEnabled) {
        globalHotkeyService.initialize()
        println("[GROMOZEKA] Global hotkey service enabled")
    } else {
        println("[GROMOZEKA] Global hotkey service disabled in settings")
    }
    println("[GROMOZEKA] Starting Compose Desktop UI...")
    application {
        GromozekaTheme {
            ChatWindow(
                sttService,
                ttsService,
                ttsQueueService,
                openAiBalanceService,
                settingsService,
                sessionJsonlService,
                globalHotkeyService,
                context
            )
        }
    }
}

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    sttService: SttService,
    ttsService: TtsService,
    ttsQueueService: TTSQueueService,
    openAiBalanceService: OpenAiBalanceService,
    settingsService: SettingsService,
    sessionJsonlService: SessionJsonlService,
    globalHotkeyService: GlobalHotkeyService,
    context: org.springframework.context.ConfigurableApplicationContext,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val sessionService = remember { context.getBean(SessionService::class.java) }

    var initialized by remember { mutableStateOf(false) }

//    val objectMapper = remember {
//        ObjectMapper().apply {
//            registerKotlinModule()
//        }
//    }
    var userInput by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }

    val assistantIsThinking = false // Temporarily deactivated

    var autoSend by remember { mutableStateOf(true) }

    var showSessionList by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var currentSession by remember { mutableStateOf<Session?>(null) }
    val isWaitingForResponse by currentSession?.isWaitingForResponse?.collectAsState() ?: remember {
        mutableStateOf(
            false
        )
    }
    var isRecording by remember { mutableStateOf(false) }

    var showBalanceDialog by remember { mutableStateOf(false) }
    var balanceInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        initialized = true
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // Subscribe to current session's message stream (true streaming)
    LaunchedEffect(currentSession) {
        currentSession?.let { session ->
            session.messageOutputStream.collect { newMessage ->
                println("[ChatApp] Received streaming message: ${newMessage.role}")
                println("[ChatApp] Message content: ${newMessage.content.size} items, first: ${newMessage.content.firstOrNull()?.javaClass?.simpleName}")
                chatHistory.add(newMessage)  // Incremental updates
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

    // Clear history when switching sessions
    LaunchedEffect(currentSession) {
        chatHistory.clear()
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

    // Unified PTT Handler for hotkey and UI button
    val unifiedPTTHandler = remember {
        UnifiedPTTHandler(
            sttService = sttService,
            ttsQueueService = ttsQueueService,
            autoSend = autoSend,
            onTextReceived = { text -> userInput = text },
            onSendMessage = { text ->
                coroutineScope.launch { sendMessage(text) }
            },
            onInterrupt = {
                currentSession?.sendInterrupt() ?: false
            }
        )
    }

    // Update recording state from unified handler
    isRecording = unifiedPTTHandler.isRecording()

    // Configure global hotkey handler
    LaunchedEffect(Unit) {
        globalHotkeyService.setGestureHandler(unifiedPTTHandler)
    }

    // Create modifier with unified logic
    val modifierWithPushToTalk = Modifier.pttGestures(
        handler = unifiedPTTHandler,
        coroutineScope = coroutineScope
    )

    Window(
        onCloseRequest = {
            println("[GROMOZEKA] Application window closing - stopping all sessions...")
            globalHotkeyService.shutdown()
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
                .onEscape(
                    handler = unifiedPTTHandler,
                    coroutineScope = coroutineScope
                )
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
                            chatHistory.addAll(messages)
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
                        context = context
                    )
                } else {
                    ChatScreen(
                        selectedSession = selectedSession,
                        chatHistory = chatHistory,
                        userInput = userInput,
                        onUserInputChange = { userInput = it },
                        assistantIsThinking = assistantIsThinking,
                        isWaitingForResponse = isWaitingForResponse,
                        autoSend = autoSend,
                        onAutoSendChange = { autoSend = it },
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
                        ttsService = ttsService,
                        coroutineScope = coroutineScope,
                        modifierWithPushToTalk = modifierWithPushToTalk,
                        onCheckBalance = {
                            coroutineScope.launch {
                                balanceInfo = openAiBalanceService.checkBalance()
                                showBalanceDialog = true
                            }
                        },
                        isDev = settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (showBalanceDialog) {
                AlertDialog(
                    onDismissRequest = { showBalanceDialog = false },
                    title = { Text("OpenAI Balance") },
                    text = { Text(balanceInfo) },
                    confirmButton = {
                        CompactButton(onClick = { showBalanceDialog = false }) {
                            Text("OK")
                        }
                    }
                )
            }
        }
    }
}

@SpringBootApplication
class ChatApplication