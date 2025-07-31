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
import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ChatMessageContent
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.services.OpenAiBalanceService
import com.gromozeka.bot.services.SessionFileCoordinator
import com.gromozeka.bot.services.SttService
import com.gromozeka.bot.ui.ChatScreen
import com.gromozeka.bot.ui.SessionListScreen
import com.gromozeka.bot.utils.ClaudeCodePaths
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder

fun main() {
    System.setProperty("java.awt.headless", "false")

    // Check Claude Code is installed
    if (!ClaudeCodePaths.PROJECTS_DIR.exists()) {
        throw IllegalStateException("Claude Code not installed - directory does not exist: ${ClaudeCodePaths.PROJECTS_DIR.absolutePath}")
    }

    val context = SpringApplicationBuilder(ChatApplication::class.java).run()
    val sttService = context.getBean<SttService>()
    val claudeCodeStreamingWrapper = context.getBean<ClaudeCodeStreamingWrapper>()
    val sessionFileCoordinator = context.getBean<SessionFileCoordinator>() // Initialize file monitoring
    val openAiBalanceService = context.getBean<OpenAiBalanceService>()
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
                claudeCodeStreamingWrapper,
                sessionFileCoordinator,
                openAiBalanceService
            )
        }
    }
}

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    sttService: SttService,
    claudeCodeStreamingWrapper: ClaudeCodeStreamingWrapper,
    sessionFileCoordinator: SessionFileCoordinator,
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
    var loadedSessionFile by remember { mutableStateOf<String?>(null) }

    var assistantIsThinking by remember { mutableStateOf(false) }

    var autoSend by remember { mutableStateOf(true) }
    var attachOpenedFile by remember { mutableStateOf(true) }

    var showSessionList by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    var showBalanceDialog by remember { mutableStateOf(false) }
    var balanceInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        initialized = true
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    val createNewSession: (String) -> Unit = { projectPath ->
        coroutineScope.launch {
            try {
                // Set up callback to capture real session ID
                claudeCodeStreamingWrapper.onSessionIdCaptured = { realSessionId ->
                    println("[ChatApp] Captured real session ID via callback: $realSessionId")

                    // Update selectedSession with real ID
                    selectedSession = selectedSession?.copy(sessionId = realSessionId)
                    println("[ChatApp] Updated selectedSession with real ID: $realSessionId")

                    // Set up coordinator with real session ID
                    sessionFileCoordinator.setChatHistory(realSessionId, chatHistory)
                }

                claudeCodeStreamingWrapper.start(
                    sessionId = null,
                    projectPath = projectPath
                )

                val newSession = ChatSession(
                    sessionId = "new-session", // Temporary ID, will be updated when real sessionId is captured
                    projectPath = projectPath,
                    firstMessage = "",
                    lastTimestamp = Clock.System.now(),
                    messageCount = 0,
                    preview = "New Session"
                )

                selectedSession = newSession
                chatHistory.clear()
                // Don't set coordinator yet - wait for real sessionId from streaming callback
                showSessionList = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    val sendMessage: suspend (String) -> Unit = { message ->
        assistantIsThinking = true

        val userMessage = ChatMessage(
            id = (chatHistory.size + 1).toString(),
            role = ChatMessage.Role.USER,
            content = listOf(
                ChatMessageContent(
                    content = message,
                    type = ChatMessageContent.Type.TEXT,
                    ""
                )
            ),
            timestamp = Clock.System.now(),
            metadataType = ChatMessage.MetadataType.NONE
        )
        chatHistory.add(userMessage)

        claudeCodeStreamingWrapper.sendMessage(message)


        assistantIsThinking = false
    }


    LaunchedEffect(loadedSessionFile) {
        if (loadedSessionFile != null) {
            snapshotFlow { scrollState.maxValue }
                .distinctUntilChanged()
                .collect { max ->
                    scrollState.animateScrollTo(max)
                }
        }
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

    Window(onCloseRequest = ::exitApplication, title = "🤖 Громозека") {
        if (initialized) {
            if (showSessionList) {
                SessionListScreen(
                    sessionFileCoordinator = sessionFileCoordinator,
                    onSessionSelected = { session, messages ->
                        selectedSession = session
                        chatHistory.clear()
                        chatHistory.addAll(messages)
                        sessionFileCoordinator.setChatHistory(session.sessionId, chatHistory)
                        showSessionList = false
                    },
                    claudeCodeStreamingWrapper = claudeCodeStreamingWrapper,
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
                    attachOpenedFile = attachOpenedFile,
                    onAttachOpenedFileChange = { attachOpenedFile = it },
                    onBackToSessionList = { showSessionList = true },
                    onNewSession = {
                        val currentProjectPath = selectedSession?.projectPath ?: "/Users/lewik/code/gromozeka"
                        createNewSession(currentProjectPath)
                    },
                    onSendMessage = sendMessage,
                    sttService = sttService,
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