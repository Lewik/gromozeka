package com.gromozeka.bot.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.services.SttService
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    selectedSession: ChatSession?,
    chatHistory: List<ChatMessage>,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    assistantIsThinking: Boolean,
    autoSend: Boolean,
    onAutoSendChange: (Boolean) -> Unit,
    onBackToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onSendMessage: suspend (String) -> Unit,
    sttService: SttService,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier,
    onCheckBalance: () -> Unit,
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
            Button(onClick = onCheckBalance) {
                Text("💰 Баланс")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { stickyToBottom = !stickyToBottom }) {
                Text("Autoscroll is ${if (stickyToBottom) "ON" else "OFF"}")
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
            SelectionContainer {
                Column {
                    chatHistory
                        .forEachIndexed { index, message ->
                            MessageItem(message = message)
                            if (index < chatHistory.size - 1) {
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
        horizontalArrangement = if (message.messageType == ChatMessage.MessageType.USER) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(8.dp)
                .background(
                    color = if (message.messageType == ChatMessage.MessageType.USER)
                        MaterialTheme.colors.primary.copy(alpha = 0.1f)
                    else
                        MaterialTheme.colors.surface.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(12.dp)
        ) {
            message.content.forEach { content ->
                when (content) {
                    is ChatMessage.ContentItem.Message -> {
                        Text(
                            text = content.text,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    is ChatMessage.ContentItem.ToolCall -> {
                        Text(
                            text = "🔧 Tool: ${content.call::class.simpleName}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.primary
                        )
                    }
                    is ChatMessage.ContentItem.ToolResult -> {
                        Text(
                            text = "📊 Tool Result",
                            style = MaterialTheme.typography.caption,
                            color = if (content.isError) MaterialTheme.colors.error else MaterialTheme.colors.secondary
                        )
                    }
                    is ChatMessage.ContentItem.Thinking -> {
                        Text(
                            text = "🤔 ${content.thinking}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    is ChatMessage.ContentItem.System -> {
                        Text(
                            text = "⚙️ ${content.content}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    is ChatMessage.ContentItem.Media -> {
                        Text(
                            text = "📎 Media: ${content.mimeType}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    is ChatMessage.ContentItem.GromozekaMessage -> {
                        GromozekaMessageTemplate(content)
                    }
                    is ChatMessage.ContentItem.UnknownJson -> {
                        Column {
                            Text(
                                text = jsonPrettyPrint(content.json),
                                style = MaterialTheme.typography.body2,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚠️ Не удалось распарсить структуру",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GromozekaMessageTemplate(data: ChatMessage.ContentItem.GromozekaMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colors.secondary.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, MaterialTheme.colors.secondary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Заголовок с иконкой
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = "🤖",
                    style = MaterialTheme.typography.h6
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Громозека",
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.secondary
                )
            }
            
            // Отображаем основной контент
            Text(
                text = data.fullText,
                style = MaterialTheme.typography.body2
            )
            
            // Дополнительная информация мелким шрифтом
            if (data.ttsText != null || data.voiceTone != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        data.ttsText?.let { append("🗣️ TTS: $it ") }
                        data.voiceTone?.let { append("🎭 Tone: $it") }
                    },
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}


private fun jsonPrettyPrint(json: JsonElement): String {
    return json.toString().let { jsonString ->
        // Простой pretty print
        jsonString.replace(",", ",\n").replace("{", "{\n").replace("}", "\n}")
    }
}

@Composable
private fun MessageInput(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    assistantIsThinking: Boolean,
    onSendMessage: suspend (String) -> Unit,
    coroutineScope: CoroutineScope,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = userInput,
            onValueChange = onUserInputChange,
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    if (!assistantIsThinking && event.key == Key.Enter && event.isShiftPressed && userInput.isNotBlank()) {
                        coroutineScope.launch {
                            onSendMessage(userInput)
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
                            onSendMessage(userInput)
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
    modifierWithPushToTalk: Modifier,
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