package com.gromozeka.bot

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.gromozeka.bot.services.ClaudeCodeSessionMapper
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.services.ClaudeStreamMessage
import com.gromozeka.bot.services.SessionListService
import com.gromozeka.bot.services.SttService
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
import java.io.File

fun main() {
    System.setProperty("java.awt.headless", "false")
    val context = SpringApplicationBuilder(ChatApplication::class.java).run()
    val theAssistant = context.getBean<TheAssistant>()
    val sttService = context.getBean<SttService>()
    val chatMemory = context.getBean<ChatMemory>()
    val claudeCodeStreamingWrapper = context.getBean<ClaudeCodeStreamingWrapper>()
    val sessionListService = context.getBean<SessionListService>()
    application {
        MaterialTheme(
            typography = Typography(
                defaultFontFamily = FontFamily.SansSerif,
                body1 = TextStyle(fontSize = 12.sp),
                h5 = TextStyle(fontSize = 14.sp)
            )
        ) {
            ChatWindow(
                theAssistant, sttService, chatMemory, claudeCodeStreamingWrapper, sessionListService
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

    // Session management state
    var showSessionList by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var availableSessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }

    LaunchedEffect(Unit) {
        theAssistant.init()

        // Load available sessions
        try {
            availableSessions = sessionListService.getAvailableSessions()
            println("Found ${availableSessions.size} available sessions")
        } catch (e: Exception) {
            println("Error loading sessions: ${e.message}")
            e.printStackTrace()
        }

        initialized = true
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    // Listen to streaming wrapper messages
    LaunchedEffect(selectedSession) {
        if (selectedSession != null) {
            println("Setting up streaming wrapper message listener")
            claudeCodeStreamingWrapper.messages.collect { message ->
                println("Received streaming message: $message")
                when (message) {
                    is ClaudeStreamMessage.AssistantMessage -> {
                        // Add assistant message to chat history
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
            // Use Claude Code streaming wrapper for selected session
            println("Sending via ClaudeCodeStreamingWrapper")
            
            // Add user message to chat history
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
            // Use regular assistant
            println("Sending via TheAssistant")
            theAssistant.sendMessage(message)
            
            // Проверяем, есть ли ожидающие инструменты
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

        // Проверяем, есть ли еще ожидающие инструменты после выполнения
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

    var isRecording by remember { mutableStateOf(false) }

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
                // Session selection screen
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    Text("Выберите беседу", style = MaterialTheme.typography.h5)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (availableSessions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Нет доступных бесед")
                        }
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                            availableSessions.forEach { session ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).pointerInput(Unit) {
                                        detectTapGestures {
                                            selectedSession = session
                                            // Load session messages
                                            coroutineScope.launch {
                                                try {
                                                    // Load session messages
                                                    val encodedPath = session.projectPath.replace("/", "-")
                                                    val sessionFile = File(
                                                        System.getProperty("user.home"),
                                                        ".claude/projects/$encodedPath/${session.sessionId}.jsonl"
                                                    )

                                                    val messages = ClaudeCodeSessionMapper.loadSessionAsChatMessages(
                                                        sessionFile
                                                    )
                                                    chatHistory = messages

                                                    // Start streaming wrapper with session
                                                    claudeCodeStreamingWrapper.start(
                                                        sessionId = session.sessionId, projectPath = session.projectPath
                                                    )

                                                    println("Loaded ${messages.size} messages from session ${session.sessionId}")
                                                    println("Started streaming wrapper for project: ${session.projectPath}")
                                                    showSessionList = false

                                                } catch (e: Exception) {
                                                    println("Error loading session: ${e.message}")
                                                    e.printStackTrace()
                                                }
                                            }
                                        }
                                    }, elevation = 2.dp
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = session.displayPreview(), style = MaterialTheme.typography.body1
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Проект: ${session.displayProject()}",
                                            style = MaterialTheme.typography.caption,
                                            color = MaterialTheme.colors.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row {
                                            Text(
                                                text = session.displayTime(), style = MaterialTheme.typography.caption
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text(
                                                text = "${session.messageCount} сообщений",
                                                style = MaterialTheme.typography.caption
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Chat screen
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { showSessionList = true }) {
                            Text("← Выбрать беседу")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Chat", style = MaterialTheme.typography.h5)
                        selectedSession?.let { session ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("(${session.displayPreview()})", style = MaterialTheme.typography.caption)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoSend, onCheckedChange = { autoSend = it })
                            Text("Отправлять сразу")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = attachOpenedFile, onCheckedChange = { attachOpenedFile = it })
                            Text("Отправлять файл")
                        }
                    }

                    // Отображение ожидающих инструментов, если они есть
                    if (showToolCalls) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f),
                            elevation = 4.dp
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Инструменты, требующие выполнения:",
                                    style = MaterialTheme.typography.h6,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                pendingToolCalls.forEach { toolCall ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        backgroundColor = MaterialTheme.colors.surface
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                "Инструмент: ${toolCall["name"]}",
                                                style = MaterialTheme.typography.subtitle1
                                            )
                                            Text(
                                                "Аргументы: ${toolCall["arguments"]}",
                                                style = MaterialTheme.typography.body2
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                executeToolCalls()
                                            }
                                        }, enabled = !assistantIsThinking
                                    ) {
                                        Text("Выполнить инструменты")
                                    }
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
                        SelectionContainer {
                            Column {
                                chatHistory
                                    .filter { it.metadataType != ChatMessage.MetadataType.IDE_CONTEXT }
                                    .forEach { message ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = if (message.role == ChatMessage.Role.USER) Arrangement.End else Arrangement.Start
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.85f)
                                                    .padding(8.dp)
                                                    .background(
                                                        color = if (message.role == ChatMessage.Role.USER) MaterialTheme.colors.primary.copy(
                                                            alpha = 0.1f
                                                        )
                                                        else MaterialTheme.colors.surface.copy(alpha = 0.1f),
                                                        shape = MaterialTheme.shapes.medium
                                                    )
                                                    .padding(12.dp)
                                            ) {
                                                message.content.forEach { content ->
                                                    Text(
                                                        text = content.content,
                                                        style = MaterialTheme.typography.body2,
                                                    )
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = userInput,
                            onValueChange = { userInput = it },
                            modifier = Modifier
                                .onPreviewKeyEvent { event ->
                                    // https://stackoverflow.com/questions/76215993/jetpack-compose-desktop-basictextfield-how-to-shift-enter-to-line-skip
                                    if (!assistantIsThinking && event.key == Key.Enter && event.isShiftPressed && userInput.isNotBlank()) {
                                        coroutineScope.launch {
                                            val text = userInput
                                            sendMessage(text)
                                            userInput = ""
                                        }
                                        true
                                    } else {
                                        false
                                    }

                                }
                                .weight(1f),
                            enabled = !assistantIsThinking,
                            placeholder = { Text("") },
                            trailingIcon = {
                                if (assistantIsThinking) {
                                    CircularProgressIndicator()
                                } else {
                                    IconButton(onClick = {
                                        coroutineScope.launch {
                                            val text = userInput
                                            sendMessage(text)
                                            userInput = ""
                                        }
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.weight(1f))

                        Button(onClick = { coroutineScope.launch { sttService.startRecording() } }) {
                            Text("🎙 Идёт запись")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            coroutineScope.launch {
                                val text = sttService.stopAndTranscribe()
                                if (autoSend && text.isNotBlank()) {
                                    sendMessage(text)
                                } else {
                                    userInput = text
                                }
                            }
                        }) {
                            Text("🛑 Остановить")
                        }
                        Spacer(modifier = Modifier.width(8.dp))

                        Button(onClick = {}, modifier = modifierWithPushToTalk) {
                            Text("🎤 PTT")
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

@SpringBootApplication
class ChatApplication