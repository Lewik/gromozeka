package com.gromozeka.bot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.services.SttService
import com.gromozeka.bot.services.TtsService
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.JsonElement

@Composable
fun ChatScreen(
    selectedSession: ChatSession?,
    chatHistory: List<ChatMessage>,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    assistantIsThinking: Boolean,
    isWaitingForResponse: Boolean,
    autoSend: Boolean,
    onAutoSendChange: (Boolean) -> Unit,
    onBackToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onSendMessage: suspend (String) -> Unit,
    sttService: SttService,
    ttsService: TtsService,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier,
    onCheckBalance: () -> Unit,
    isDev: Boolean = false,
) {
    val scrollState = rememberScrollState()
    var stickyToBottom by remember { mutableStateOf(true) }
    var jsonToShow by remember { mutableStateOf<String?>(null) }

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


    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackToSessionList) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "Новая беседа")
            }
            Text("Chat")
            selectedSession?.let { session ->
                    Text("(${session.displayPreview()})")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoSend, onCheckedChange = onAutoSendChange)
                Text("Отправлять сразу")
            }
            Button(onClick = onCheckBalance) {
                Text("💰 Баланс")
            }
            Button(onClick = { stickyToBottom = !stickyToBottom }) {
                Text("Autoscroll is ${if (stickyToBottom) "ON" else "OFF"}")
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
            SelectionContainer {
                Column {
                    chatHistory
                        .forEachIndexed { index, message ->
                            MessageItem(
                                message = message,
                                onShowJson = { json -> jsonToShow = json }
                            )
                            if (index < chatHistory.size - 1) {
                                Divider()
                            }
                        }
                }
            }
        }


        // Waiting for response indicator
        if (isWaitingForResponse) {
            Row {
                CircularProgressIndicator()
                    Text("Ожидание ответа от Claude...")
            }
        }

        MessageInput(
            userInput = userInput,
            onUserInputChange = onUserInputChange,
            assistantIsThinking = assistantIsThinking,
            onSendMessage = onSendMessage,
            coroutineScope = coroutineScope
        )


        VoiceControls(
            sttService = sttService,
            autoSend = autoSend,
            onSendMessage = onSendMessage,
            onUserInputChange = onUserInputChange,
            coroutineScope = coroutineScope,
            modifierWithPushToTalk = modifierWithPushToTalk,
            isDev = isDev
        )
    }

    // JSON Dialog at top level to avoid hierarchy issues
    jsonToShow?.let { json ->
        JsonDialog(
            json = json,
            onDismiss = { jsonToShow = null }
        )
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    onShowJson: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.messageType == ChatMessage.MessageType.USER) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
        ) {
            // Show original JSON button if available  
            message.originalJson?.let { originalJson ->
                Button(
                    onClick = { onShowJson(originalJson) }
                ) {
                    Text("🔍 JSON")
                }
            }

            message.content.forEach { content ->
                when (content) {
                    is ChatMessage.ContentItem.Message -> {
                        Text(text = content.text)
                    }

                    is ChatMessage.ContentItem.ToolCall -> {
                        Text(text = "🔧 Tool: ${content.call::class.simpleName}")
                    }

                    is ChatMessage.ContentItem.ToolResult -> {
                        Text(text = "📊 Tool Result")
                    }

                    is ChatMessage.ContentItem.Thinking -> {
                        Text(text = "🤔 ${content.thinking}")
                    }

                    is ChatMessage.ContentItem.System -> {
                        Text(text = "⚙️ ${content.content}")
                    }

                    is ChatMessage.ContentItem.Media -> {
                        Text(text = "📎 Media: ${content.mimeType}")
                    }

                    is ChatMessage.ContentItem.IntermediateMessage -> {
                        StructuredMessageTemplate(content, isIntermediate = true)
                    }

                    is ChatMessage.ContentItem.FinalResultMessage -> {
                        StructuredMessageTemplate(content, isIntermediate = false)
                    }

                    is ChatMessage.ContentItem.SystemStructuredMessage -> {
                        StructuredMessageTemplate(content, isIntermediate = false)
                    }

                    is ChatMessage.ContentItem.UnknownJson -> {
                        Column {
                            Text(text = jsonPrettyPrint(content.json))
                            Text(text = "⚠️ Не удалось распарсить структуру")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredMessageTemplate(
    data: ChatMessage.ContentItem,
    isIntermediate: Boolean,
) {
    // Extract structured data from different message types
    val (text, structured) = when (data) {
        is ChatMessage.ContentItem.IntermediateMessage -> data.text to data.structured
        is ChatMessage.ContentItem.FinalResultMessage -> data.text to data.structured
        is ChatMessage.ContentItem.SystemStructuredMessage -> data.text to data.structured
        else -> return // Shouldn't happen, but safe fallback
    }

    val titleText = when (data) {
        is ChatMessage.ContentItem.IntermediateMessage -> "🔄 Обработка..."
        is ChatMessage.ContentItem.FinalResultMessage -> "🤖 Громозека"
        is ChatMessage.ContentItem.SystemStructuredMessage -> "⚙️ Система"
        else -> "🤖 Громозека"
    }

    Card {
        Column {
            Row {
                Text(text = titleText)
            }
            Text(text = text)
            if (structured != null && (structured.ttsText != null || structured.voiceTone != null)) {
                Text(
                    text = buildString {
                        structured.ttsText?.let { append("🗣️ TTS: $it ") }
                        structured.voiceTone?.let { append("🎭 Tone: $it") }
                    }
                )
            }
        }
    }
}


private val prettyJson = kotlinx.serialization.json.Json {
    prettyPrint = true
}

private fun jsonPrettyPrint(json: JsonElement) = prettyJson.encodeToString(JsonElement.serializer(), json)

private fun jsonPrettyPrint(jsonString: String): String = try {
    val json = parseToJsonElement(jsonString)
    jsonPrettyPrint(json)
} catch (e: Exception) {
    jsonString // Return as-is if can't parse
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
                    if (!assistantIsThinking && event.key == Key.Enter && event.isShiftPressed && event.type == KeyEventType.KeyDown && userInput.isNotBlank()) {
                        println("🚀 Shift+Enter KeyDown detected, sending message: '$userInput'")
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
                        println("📤 Send button clicked, sending message: '$userInput'")
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
        Spacer(modifier = Modifier.width(4.dp))
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
    isDev: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Development mode button - first in row
        if (isDev) {
            Button(onClick = {
                coroutineScope.launch {
                    onSendMessage("Расскажи скороговорку")
                }
            }) {
                Text("🗣 Скороговорка")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = { coroutineScope.launch { sttService.startRecording() } }) {
            Text("🎙 Идёт запись")
        }
        Spacer(modifier = Modifier.width(4.dp))
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
        Spacer(modifier = Modifier.width(4.dp))

        Button(onClick = {}, modifier = modifierWithPushToTalk) {
            Text("🎤 PTT")
        }
    }
}

@Composable
private fun JsonDialog(
    json: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        ),

        ) {
        Card {
            Column {
                // Header with title and close button
                Row {
                    Text("Original JSON")
                    Button(onClick = onDismiss) {
                        Text("✕")
                    }
                }

                Divider()

                // Scrollable content with selection support
                SelectionContainer {
                    Text(text = jsonPrettyPrint(json))
                }
            }
        }
    }
}