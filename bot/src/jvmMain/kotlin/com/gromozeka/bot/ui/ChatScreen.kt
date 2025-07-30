package com.gromozeka.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.TheAssistant
import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.services.SttService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    selectedSession: ChatSession?,
    chatHistory: List<ChatMessage>,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    assistantIsThinking: Boolean,
    showToolCalls: Boolean,
    pendingToolCalls: List<Map<String, String>>,
    autoSend: Boolean,
    onAutoSendChange: (Boolean) -> Unit,
    attachOpenedFile: Boolean,
    onAttachOpenedFileChange: (Boolean) -> Unit,
    onBackToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onSendMessage: suspend (String) -> Unit,
    onExecuteToolCalls: suspend () -> Unit,
    sttService: SttService,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier,
    onCheckBalance: () -> Unit
) {
    val scrollState = rememberScrollState()
    var stickyToBottom by remember { mutableStateOf(true) }
    
    val isAtBottom by remember {
        derivedStateOf {
            scrollState.value >= scrollState.maxValue - 50
        }
    }
    
    LaunchedEffect(scrollState.value, isAtBottom) {
        if (isAtBottom) {
            stickyToBottom = true
        } else if (scrollState.isScrollInProgress) {
            stickyToBottom = false
        }
    }
    
    LaunchedEffect(chatHistory.size) {
        if (stickyToBottom) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackToSessionList) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "Новая беседа")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Chat", style = MaterialTheme.typography.h5)
            selectedSession?.let { session ->
                Spacer(modifier = Modifier.width(8.dp))
                Text("(${session.displayPreview()})", style = MaterialTheme.typography.caption)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoSend, onCheckedChange = onAutoSendChange)
                Text("Отправлять сразу")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = attachOpenedFile, onCheckedChange = onAttachOpenedFileChange)
                Text("Отправлять файл")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onCheckBalance) {
                Text("💰 Баланс")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { stickyToBottom = !stickyToBottom }) {
                Text("Autoscroll is ${if (stickyToBottom) "ON" else "OFF"}")
            }
        }

        if (showToolCalls) {
            ToolCallsCard(
                pendingToolCalls = pendingToolCalls,
                assistantIsThinking = assistantIsThinking,
                onExecuteToolCalls = onExecuteToolCalls,
                coroutineScope = coroutineScope
            )
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
            SelectionContainer {
                Column {
                    chatHistory
                        .filter { it.metadataType != ChatMessage.MetadataType.IDE_CONTEXT }
                        .forEachIndexed { index, message ->
                            MessageItem(message = message)
                            if (index < chatHistory.filter { it.metadataType != ChatMessage.MetadataType.IDE_CONTEXT }.size - 1) {
                                Divider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                                    thickness = 1.dp
                                )
                            }
                        }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        MessageInput(
            userInput = userInput,
            onUserInputChange = onUserInputChange,
            assistantIsThinking = assistantIsThinking,
            onSendMessage = onSendMessage,
            coroutineScope = coroutineScope
        )

        Spacer(modifier = Modifier.height(10.dp))

        VoiceControls(
            sttService = sttService,
            autoSend = autoSend,
            onSendMessage = onSendMessage,
            onUserInputChange = onUserInputChange,
            coroutineScope = coroutineScope,
            modifierWithPushToTalk = modifierWithPushToTalk
        )
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (message.role == ChatMessage.Role.USER) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(8.dp)
                .background(
                    color = if (message.role == ChatMessage.Role.USER) 
                        MaterialTheme.colors.primary.copy(alpha = 0.1f)
                    else 
                        MaterialTheme.colors.surface.copy(alpha = 0.1f),
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

@Composable
private fun ToolCallsCard(
    pendingToolCalls: List<Map<String, String>>,
    assistantIsThinking: Boolean,
    onExecuteToolCalls: suspend () -> Unit,
    coroutineScope: CoroutineScope
) {
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
                            onExecuteToolCalls()
                        }
                    },
                    enabled = !assistantIsThinking
                ) {
                    Text("Выполнить инструменты")
                }
            }
        }
    }
}

@Composable
private fun MessageInput(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    assistantIsThinking: Boolean,
    onSendMessage: suspend (String) -> Unit,
    coroutineScope: CoroutineScope
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = userInput,
            onValueChange = onUserInputChange,
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    if (!assistantIsThinking && event.key == Key.Enter && event.isShiftPressed && userInput.isNotBlank()) {
                        coroutineScope.launch {
                            val text = userInput
                            onSendMessage(text)
                            onUserInputChange("")
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
                            onSendMessage(text)
                            onUserInputChange("")
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun VoiceControls(
    sttService: SttService,
    autoSend: Boolean,
    onSendMessage: suspend (String) -> Unit,
    onUserInputChange: (String) -> Unit,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier
) {
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
                    onSendMessage(text)
                } else {
                    onUserInputChange(text)
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