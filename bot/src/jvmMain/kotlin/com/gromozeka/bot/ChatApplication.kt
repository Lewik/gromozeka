package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ChatMessageContent
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.services.ClaudeStreamMessage
import com.gromozeka.bot.services.OpenAiBalanceService
import com.gromozeka.bot.services.SessionListService
import com.gromozeka.bot.services.SttService
import com.gromozeka.bot.ui.ChatScreen
import com.gromozeka.bot.ui.SessionListScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder

fun main() {
    System.setProperty("java.awt.headless", "false")
    val context = SpringApplicationBuilder(ChatApplication::class.java).run()
    val theAssistant = context.getBean<TheAssistant>()
    val sttService = context.getBean<SttService>()
    val chatMemory = context.getBean<ChatMemory>()
    val claudeCodeStreamingWrapper = context.getBean<ClaudeCodeStreamingWrapper>()
    val sessionListService = context.getBean<SessionListService>()
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
                theAssistant, sttService, chatMemory, claudeCodeStreamingWrapper, sessionListService, openAiBalanceService
            )
        }
    }
}

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    theAssistant: TheAssistant,
    sttService: SttService,
    chatMemory: ChatMemory,
    claudeCodeStreamingWrapper: ClaudeCodeStreamingWrapper,
    sessionListService: SessionListService,
    openAiBalanceService: OpenAiBalanceService
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var initialized by remember { mutableStateOf(false) }
    var userInput by remember { mutableStateOf("") }
    var chatHistory by remember { mutableStateOf(listOf<ChatMessage>()) }
    var loadedSessionFile by remember { mutableStateOf<String?>(null) }

    var assistantIsThinking by remember { mutableStateOf(false) }
    var showToolCalls by remember { mutableStateOf(false) }
    var pendingToolCalls by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    var autoSend by remember { mutableStateOf(true) }
    var attachOpenedFile by remember { mutableStateOf(true) }

    var showSessionList by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    
    var showBalanceDialog by remember { mutableStateOf(false) }
    var balanceInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        theAssistant.init()
        initialized = true
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    LaunchedEffect(selectedSession) {
        if (selectedSession != null) {
            println("Setting up streaming wrapper message listener")
            claudeCodeStreamingWrapper.messages.collect { message ->
                println("Received streaming message: $message")
                when (message) {
                    is ClaudeStreamMessage.AssistantMessage -> {
                        val newMessage = ChatMessage(
                            id = (chatHistory.size + 1).toString(),
                            role = ChatMessage.Role.ASSISTANT,
                            content = listOf(
                                ChatMessageContent(
                                    content = message.text,
                                    type = ChatMessageContent.Type.TEXT,
                                    ""
                                )
                            ),
                            timestamp = Clock.System.now(),
                            metadataType = ChatMessage.MetadataType.NONE
                        )
                        chatHistory = chatHistory + newMessage
                        println("Added assistant message to history: ${message.text.take(50)}...")
                    }
                    is ClaudeStreamMessage.ResultMessage -> {
                        println("Conversation completed. Cost: $${message.totalCostUsd}, Duration: ${message.durationMs}ms")
                        assistantIsThinking = false
                    }
                    else -> {
                        println("Other message type: $message")
                    }
                }
            }
        }
    }

    val updateChatHistory = {
        chatHistory = chatMemory.get("conversationId").mapIndexed { i, message ->
            ChatMessage(
                id = i.toString(),
                role = when (message) {
                    is UserMessage -> ChatMessage.Role.USER
                    is SystemMessage -> ChatMessage.Role.ASSISTANT
                    is AssistantMessage -> ChatMessage.Role.ASSISTANT
                    else -> throw IllegalArgumentException("Unknown message type: ${message.javaClass.name}")
                },
                content = listOf(
                    ChatMessageContent(
                        content = message.text ?: " - none - ",
                        type = ChatMessageContent.Type.TEXT,
                        "",
                    )
                ),
                timestamp = Clock.System.now(),
                metadataType = ChatMessage.MetadataType.NONE,
            )
        }
    }

    val sendMessage: suspend (String) -> Unit = { message ->
        println("Sending message: $message")
        assistantIsThinking = true
        
        if (selectedSession != null) {
            println("Sending via ClaudeCodeStreamingWrapper")
            
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
            chatHistory = chatHistory + userMessage
            
            claudeCodeStreamingWrapper.sendMessage(message)
        } else {
            println("Sending via TheAssistant")
            theAssistant.sendMessage(message)
            
            if (theAssistant.hasPendingToolCalls()) {
                pendingToolCalls = theAssistant.getPendingToolCallInfo()
                showToolCalls = true
            }
            
            updateChatHistory()
        }
        
        assistantIsThinking = false
    }

    val executeToolCalls: suspend () -> Unit = {
        assistantIsThinking = true
        theAssistant.executeToolCalls()

        if (theAssistant.hasPendingToolCalls()) {
            pendingToolCalls = theAssistant.getPendingToolCallInfo()
            showToolCalls = true
        } else {
            showToolCalls = false
            pendingToolCalls = emptyList()
        }

        updateChatHistory()
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

    Window(onCloseRequest = ::exitApplication, title = "Chat") {
        if (initialized) {
            if (showSessionList) {
                SessionListScreen(
                    sessionListService = sessionListService,
                    onSessionSelected = { session, messages ->
                        selectedSession = session
                        chatHistory = messages
                        showSessionList = false
                    },
                    claudeCodeStreamingWrapper = claudeCodeStreamingWrapper,
                    coroutineScope = coroutineScope
                )
            } else {
                ChatScreen(
                    selectedSession = selectedSession,
                    chatHistory = chatHistory,
                    userInput = userInput,
                    onUserInputChange = { userInput = it },
                    assistantIsThinking = assistantIsThinking,
                    showToolCalls = showToolCalls,
                    pendingToolCalls = pendingToolCalls,
                    autoSend = autoSend,
                    onAutoSendChange = { autoSend = it },
                    attachOpenedFile = attachOpenedFile,
                    onAttachOpenedFileChange = { attachOpenedFile = it },
                    onBackToSessionList = { showSessionList = true },
                    onSendMessage = sendMessage,
                    onExecuteToolCalls = executeToolCalls,
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