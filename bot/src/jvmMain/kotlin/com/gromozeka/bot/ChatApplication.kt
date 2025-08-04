package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.bot.services.OpenAiBalanceService
import com.gromozeka.bot.services.SttService
import com.gromozeka.bot.services.StreamLogger
import com.gromozeka.bot.services.TtsService
import com.gromozeka.bot.ui.ChatScreen
import com.gromozeka.bot.ui.SessionListScreen
import com.gromozeka.bot.utils.ClaudeCodePaths
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.WebApplicationType

fun main() {
    println("[GROMOZEKA] Starting application...")
    System.setProperty("java.awt.headless", "false")

    // Clean up old stream logs on startup
    println("[GROMOZEKA] Cleaning up old stream logs...")
    StreamLogger.cleanupOldLogs()
    
    // Check Claude Code is installed
    if (!ClaudeCodePaths.PROJECTS_DIR.exists()) {
        throw IllegalStateException("Claude Code not installed - directory does not exist: ${ClaudeCodePaths.PROJECTS_DIR.absolutePath}")
    }

    println("[GROMOZEKA] Initializing Spring context...")
    val context = SpringApplicationBuilder(ChatApplication::class.java)
        .web(WebApplicationType.NONE)
        .run()
    println("[GROMOZEKA] Spring context initialized successfully")
    val sttService = context.getBean<SttService>()
    val ttsService = context.getBean<TtsService>()
    val openAiBalanceService = context.getBean<OpenAiBalanceService>()
    println("[GROMOZEKA] Starting Compose Desktop UI...")
    application {
        MaterialTheme(
            typography = Typography(
                defaultFontFamily = FontFamily.SansSerif,
                body1 = TextStyle(fontSize = 12.sp),
                h5 = TextStyle(fontSize = 14.sp)
            )
        ) {
            ChatWindow(
                sttService,
                ttsService,
                openAiBalanceService
            )
        }
    }
}

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    sttService: SttService,
    ttsService: TtsService,
    openAiBalanceService: OpenAiBalanceService,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var initialized by remember { mutableStateOf(false) }

//    val objectMapper = remember {
//        ObjectMapper().apply {
//            registerKotlinModule()
//        }
//    }
    var userInput by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }

    val assistantIsThinking = false // Временно деактивировано

    var autoSend by remember { mutableStateOf(true) }

    var showSessionList by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var currentSession by remember { mutableStateOf<Session?>(null) }
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
            session.messageStream.collect { newMessage ->
                println("[ChatApp] Received streaming message: ${newMessage.messageType}")
                println("[ChatApp] Message content: ${newMessage.content.size} items, first: ${newMessage.content.firstOrNull()?.javaClass?.simpleName}")
                chatHistory.add(newMessage)  // Incremental updates
                println("[ChatApp] ChatHistory now has ${chatHistory.size} messages, last is ${chatHistory.lastOrNull()?.messageType}")
                
                // TTS для ASSISTANT сообщений (только новых, не исторических)
                if (newMessage.messageType == ChatMessage.MessageType.ASSISTANT && !newMessage.isHistorical) {
                    println("[ChatApp] Processing TTS for new assistant message")
                    val content = newMessage.content.firstOrNull()
                    println("[ChatApp] TTS Content type: ${content?.javaClass?.simpleName}")
                    
                    val structured = when (content) {
                        is ChatMessage.ContentItem.Message -> content.structured
                        is ChatMessage.ContentItem.IntermediateMessage -> content.structured
                        is ChatMessage.ContentItem.FinalResultMessage -> content.structured
                        is ChatMessage.ContentItem.SystemStructuredMessage -> content.structured
                        else -> null
                    }
                    
                    if (structured != null) {
                        val ttsText = structured.ttsText
                        println("[ChatApp] TTS text: '$ttsText'")
                        if (!ttsText.isNullOrBlank()) {
                            println("[ChatApp] Starting TTS playback...")
                            coroutineScope.launch {
                                try {
                                    ttsService.generateAndPlay(ttsText, structured.voiceTone ?: "neutral colleague")
                                    println("[ChatApp] TTS playback completed")
                                } catch (e: Exception) {
                                    println("TTS error: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                } else if (newMessage.messageType == ChatMessage.MessageType.ASSISTANT && newMessage.isHistorical) {
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
                val claudeWrapper = ClaudeCodeStreamingWrapper()
                val activeSession = Session(projectPath, claudeWrapper)
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

    val modifierWithPushToTalk = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.buttons.isPrimaryPressed && !isRecording) {
                    isRecording = true
                    coroutineScope.launch { sttService.startRecording() }
                } else if (!event.buttons.isPrimaryPressed && isRecording) {
                    isRecording = false
                    coroutineScope.launch {
                        val text = sttService.stopAndTranscribe()
                        if (autoSend && text.isNotBlank()) {
                            sendMessage(text)
                        } else {
                            userInput = text
                        }
                    }
                }
            }
        }
    }

    Window(onCloseRequest = { 
        println("[GROMOZEKA] Application window closing - stopping all sessions...")
        exitApplication() 
    }, title = "🤖 Громозека") {
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
                    onNewSession = createNewSession
                )
            } else {
                ChatScreen(
                    selectedSession = selectedSession,
                    chatHistory = chatHistory,
                    userInput = userInput,
                    onUserInputChange = { userInput = it },
                    assistantIsThinking = assistantIsThinking,
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
                    sttService = sttService,
                    ttsService = ttsService,
                    coroutineScope = coroutineScope,
                    modifierWithPushToTalk = modifierWithPushToTalk,
                    onCheckBalance = {
                        coroutineScope.launch {
                            balanceInfo = openAiBalanceService.checkBalance()
                            showBalanceDialog = true
                        }
                    }
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
                    Button(onClick = { showBalanceDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@SpringBootApplication
class ChatApplication