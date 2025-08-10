package com.gromozeka.bot.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.bot.services.TTSQueueService
import com.gromozeka.bot.settings.Settings
import com.gromozeka.shared.domain.message.ChatMessage
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.JsonElement

@Composable
fun ChatScreen(
    chatHistory: List<ChatMessage>,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    assistantIsThinking: Boolean,
    isWaitingForResponse: Boolean,
    autoSend: Boolean,
    onBackToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onSendMessage: suspend (String) -> Unit,
    ttsQueueService: TTSQueueService,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier,
    isDev: Boolean = false,
    ttsSpeed: Float = 1.0f,
    onTtsSpeedChange: (Float) -> Unit = {},
    // Settings
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
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


    Row(modifier = Modifier.fillMaxSize()) {
        // Main chat content
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                DisableSelection {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CompactButton(onClick = onBackToSessionList) {
                            Text("← Назад")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        CompactButton(onClick = onNewSession) {
                            Text("Новая")
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Message count
                        CompactButton(
                            onClick = { },
                            tooltip = "Всего сообщений: ${chatHistory.size}\n(включая системные)"
                        ) {
                            Text("💬 ${chatHistory.size}")
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Settings button
                        CompactButton(
                            onClick = { onShowSettingsPanelChange(!showSettingsPanel) },
                            tooltip = "Настройки"
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }

            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val filteredHistory = if (settings?.showSystemMessages == true) {
                        chatHistory
                    } else {
                        chatHistory.filter { message ->
                            message.role != ChatMessage.Role.SYSTEM || 
                            message.content.any { content ->
                                content is ChatMessage.ContentItem.System && 
                                content.level == ChatMessage.ContentItem.System.SystemLevel.ERROR
                            }
                        }
                    }
                    filteredHistory.forEach { message ->
                        MessageItem(
                            message = message,
                            settings = settings,
                            onShowJson = { json -> jsonToShow = json },
                            onSpeakRequest = { text, tone ->
                                coroutineScope.launch {
                                    ttsQueueService.enqueue(TTSQueueService.Task(text, tone))
                                }
                            }
                        )
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

            DisableSelection {
                MessageInput(
                    userInput = userInput,
                    onUserInputChange = onUserInputChange,
                    assistantIsThinking = assistantIsThinking,
                    onSendMessage = onSendMessage,
                    coroutineScope = coroutineScope
                )

                VoiceControls(
                    autoSend = autoSend,
                    onSendMessage = onSendMessage,
                    onUserInputChange = onUserInputChange,
                    coroutineScope = coroutineScope,
                    modifierWithPushToTalk = modifierWithPushToTalk,
                    isDev = isDev,
                    ttsSpeed = ttsSpeed,
                    onTtsSpeedChange = onTtsSpeedChange
                )
            }
        }
        }
        
        // Settings panel
        SettingsPanel(
            isVisible = showSettingsPanel,
            settings = settings,
            onSettingsChange = onSettingsChange,
            onClose = { onShowSettingsPanelChange(false) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageItem(
    message: ChatMessage,
    settings: Settings,
    onShowJson: (String) -> Unit = {},
    onSpeakRequest: (String, String) -> Unit = { _, _ -> },
) {
    // Combined metadata button data
    val roleIcon = when (message.role) {
        ChatMessage.Role.USER -> "👤"
        ChatMessage.Role.ASSISTANT -> "🤖"
        ChatMessage.Role.SYSTEM -> "⚙️"
    }
    
    val contentIcons = message.content.mapNotNull { content ->
        when (content) {
            is ChatMessage.ContentItem.UserMessage -> null
            is ChatMessage.ContentItem.ToolCall -> "🔧"
            is ChatMessage.ContentItem.ToolResult -> "📦"
            is ChatMessage.ContentItem.Thinking -> "🤔"
            is ChatMessage.ContentItem.System -> "⚙️"
            is ChatMessage.ContentItem.AssistantMessage -> null
            is ChatMessage.ContentItem.UnknownJson -> "⚠️"
        }
    }.distinct()
    
    val buttonLabel = buildString {
        append(roleIcon)
        if (contentIcons.isNotEmpty()) {
            contentIcons.forEach { append(" $it") }
        } else {
            append(" 💬") // Default chat bubble if no content icons
        }
    }
    
    val tooltipText = buildString {
        // Role / Type format
        append(message.role.name)
        if (contentIcons.isNotEmpty()) {
            append(" / ")
            val contentTypes = message.content.mapNotNull { content ->
                when (content) {
                    is ChatMessage.ContentItem.UserMessage -> "Message"
                    is ChatMessage.ContentItem.ToolCall -> "ToolCall"
                    is ChatMessage.ContentItem.ToolResult -> "ToolResult"
                    is ChatMessage.ContentItem.Thinking -> "Thinking"
                    is ChatMessage.ContentItem.System -> "System"
                    is ChatMessage.ContentItem.AssistantMessage -> "Assistant"
                    is ChatMessage.ContentItem.UnknownJson -> "Unknown"
                }
            }.distinct()
            append(contentTypes.joinToString(", "))
        }
        
        // Add TTS info if available
        val assistantContent = message.content.filterIsInstance<ChatMessage.ContentItem.AssistantMessage>().firstOrNull()
        assistantContent?.structured?.let { structured ->
            if (structured.ttsText != null || structured.voiceTone != null) {
                append("\n\n")
                structured.ttsText?.let { append("🗣️ TTS: $it") }
                if (structured.ttsText != null && structured.voiceTone != null) append("\n")
                structured.voiceTone?.let { append("🎭 Tone: $it") }
            }
        }
        
        append("\nПКМ - контекстное меню")
    }

    // Compact horizontal layout
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Metadata button (left, fixed width) with context menu
        DisableSelection {
            val clipboardManager = LocalClipboardManager.current
            
            ContextMenuArea(
                items = {
                    val assistantContent = message.content
                        .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
                        .firstOrNull()
                    val hasTtsText = !assistantContent?.structured?.ttsText.isNullOrBlank()
                    
                    buildList {
                        if (settings.showOriginalJson) {
                            add(ContextMenuItem("Показать JSON") {
                                onShowJson(message.originalJson ?: "No JSON available")
                            })
                        }
                        
                        add(ContextMenuItem("Копировать в Markdown") {
                            val markdownContent = message.content
                                .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
                                .firstOrNull()?.structured?.fullText
                                ?: message.content
                                    .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
                                    .firstOrNull()?.text
                                ?: "Содержимое недоступно"
                            clipboardManager.setText(AnnotatedString(markdownContent))
                        })
                        
                        if (hasTtsText) {
                            add(ContextMenuItem("Произнести") {
                                val ttsText = assistantContent!!.structured.ttsText!!
                                val voiceTone = assistantContent.structured.voiceTone ?: ""
                                onSpeakRequest(ttsText, voiceTone)
                            })
                        }
                    }
                }
            ) {
                CompactButton(
                    onClick = { }, // Убираем ЛКМ функциональность
                    tooltip = tooltipText,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(buttonLabel)
                }
            }
        }

        // Message content (right, expandable)
        Column(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = CompactButtonDefaults.ButtonHeight),
            verticalArrangement = Arrangement.Center
        ) {
            message.content.forEach { content ->
                when (content) {
                    is ChatMessage.ContentItem.UserMessage -> {
                        Markdown(content = content.text)
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

                    is ChatMessage.ContentItem.AssistantMessage -> {
                        StructuredMessageTemplate(content, isIntermediate = true)
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
        is ChatMessage.ContentItem.AssistantMessage -> data.structured.fullText to data.structured
        else -> return // Shouldn't happen, but safe fallback
    }

    Card {
        Column {
            Markdown(content = text)
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
    autoSend: Boolean,
    onSendMessage: suspend (String) -> Unit,
    onUserInputChange: (String) -> Unit,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier,
    isDev: Boolean = false,
    ttsSpeed: Float = 1.0f,
    onTtsSpeedChange: (Float) -> Unit = {},
) {
    // Voice controls row
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Development mode buttons - first in row
            if (isDev) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactButton(onClick = {
                        coroutineScope.launch {
                            onSendMessage("Расскажи скороговорку")
                        }
                    }) {
                        Text("🗣 Скороговорка")
                    }
                    
                    CompactButton(onClick = {
                        coroutineScope.launch {
                            onSendMessage("Создай таблицу с примерами разных типов данных в программировании")
                        }
                    }) {
                        Text("📊 Таблица")
                    }
                    
                    CompactButton(onClick = {
                        coroutineScope.launch {
                            onSendMessage("Загугли последние новости про Google")
                        }
                    }) {
                        Text("🔍 Загугли про гугл")
                    }
                    
                    CompactButton(onClick = {
                        coroutineScope.launch {
                            onSendMessage("Выполни ls")
                        }
                    }) {
                        Text("📁 выполни ls")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            CompactButton(onClick = {}, modifier = modifierWithPushToTalk) {
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
                    CompactButton(onClick = onDismiss) {
                        Text("✕")
                    }
                }

                Divider()

                // Scrollable content - Dialog is outside main SelectionContainer
                SelectionContainer {
                    Text(
                        text = jsonPrettyPrint(json),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}