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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
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
                            MessageItem(
                                message = message,
                                onShowJson = { json -> jsonToShow = json }
                            )
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

        // Waiting for response indicator
        if (isWaitingForResponse) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colors.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Ожидание ответа от Claude...",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        }

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
            // Show original JSON button if available  
            message.originalJson?.let { originalJson ->
                Button(
                    onClick = { onShowJson(originalJson) },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("🔍 JSON", style = MaterialTheme.typography.caption)
                }
            }

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

    val alpha = if (isIntermediate) 0.6f else 1.0f
    val titleText = when (data) {
        is ChatMessage.ContentItem.IntermediateMessage -> "🔄 Обработка..."
        is ChatMessage.ContentItem.FinalResultMessage -> "🤖 Громозека"
        is ChatMessage.ContentItem.SystemStructuredMessage -> "⚙️ Система"
        else -> "🤖 Громозека"
    }

    Card(
        modifier = Modifier.fillMaxWidth().alpha(alpha),
        backgroundColor = MaterialTheme.colors.secondary.copy(alpha = if (isIntermediate) 0.05f else 0.1f),
        border = BorderStroke(1.dp, MaterialTheme.colors.secondary.copy(alpha = if (isIntermediate) 0.2f else 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.secondary.copy(alpha = alpha)
                )
            }

            // Display main content
            Text(
                text = text,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = alpha)
            )

            // Additional information in small font
            if (structured != null && (structured.ttsText != null || structured.voiceTone != null)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        structured.ttsText?.let { append("🗣️ TTS: $it ") }
                        structured.voiceTone?.let { append("🎭 Tone: $it") }
                    },
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f * alpha)
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
            Spacer(modifier = Modifier.width(8.dp))
        }

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
        Card(
            modifier = Modifier.fillMaxSize(),
            elevation = 8.dp
        ) {
            Column {
                // Header with title and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Original JSON",
                        style = MaterialTheme.typography.h6
                    )
                    Button(onClick = onDismiss) {
                        Text("✕")
                    }
                }

                Divider()

                // Scrollable content with selection support
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = jsonPrettyPrint(json),
                        style = MaterialTheme.typography.caption,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}