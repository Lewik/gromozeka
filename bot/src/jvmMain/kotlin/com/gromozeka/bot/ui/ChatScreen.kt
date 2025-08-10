package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.bot.services.TTSQueueService
import com.gromozeka.bot.settings.Settings
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import com.gromozeka.shared.domain.message.ToolCallData
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.JsonElement

@Composable
fun ChatScreen(
    chatHistory: List<ChatMessage>,
    toolResultsMap: Map<String, ChatMessage.ContentItem.ToolResult>,
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
    isRecording: Boolean = false,
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
                                toolResultsMap = toolResultsMap,
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

                Spacer(modifier = Modifier.height(8.dp))

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
                        coroutineScope = coroutineScope,
                        modifierWithPushToTalk = modifierWithPushToTalk,
                        isRecording = isRecording,
                        showPttButton = settings.enableStt
                    )

                    // Dev buttons only
                    if (isDev) {
                        Spacer(modifier = Modifier.height(8.dp))
                        DevButtons(
                            onSendMessage = onSendMessage,
                            coroutineScope = coroutineScope
                        )
                    }
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
    toolResultsMap: Map<String, ChatMessage.ContentItem.ToolResult>,
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
            is ChatMessage.ContentItem.ToolResult -> null // Don't show ToolResult icon - they're integrated into ToolCall
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
                    is ChatMessage.ContentItem.ToolResult -> null // Don't show in tooltip - integrated into ToolCall
                    is ChatMessage.ContentItem.Thinking -> "Thinking"
                    is ChatMessage.ContentItem.System -> "System"
                    is ChatMessage.ContentItem.AssistantMessage -> "Assistant"
                    is ChatMessage.ContentItem.UnknownJson -> "Unknown"
                }
            }.distinct()
            append(contentTypes.joinToString(", "))
        }

        // Add TTS info if available
        val assistantContent =
            message.content.filterIsInstance<ChatMessage.ContentItem.AssistantMessage>().firstOrNull()
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
        Card(
            modifier = Modifier.weight(1f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (message.role == ChatMessage.Role.USER &&
                        message.content.any { it is ChatMessage.ContentItem.UserMessage }
                    ) 0.1f else 0f
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .defaultMinSize(minHeight = CompactButtonDefaults.ButtonHeight)
                    .padding(start = if (message.content.any { it is ChatMessage.ContentItem.ToolCall }) 0.dp else 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                message.content.forEach { content ->
                    when (content) {
                        is ChatMessage.ContentItem.UserMessage -> Markdown(content = content.text)
                        is ChatMessage.ContentItem.ToolCall -> {
                            // Find corresponding result from entire chat history
                            val correspondingResult = toolResultsMap[content.id]
                            ToolCallItem(
                                toolCall = content.call,
                                toolResult = correspondingResult
                            )
                        }

                        is ChatMessage.ContentItem.ToolResult -> {
                            // Don't render ToolResult separately - it's shown in ToolCallItem
                        }

                        is ChatMessage.ContentItem.Thinking -> Text(text = content.thinking)
                        is ChatMessage.ContentItem.System -> Text(text = content.content)
                        is ChatMessage.ContentItem.AssistantMessage -> Markdown(content = content.structured.fullText)
                        is ChatMessage.ContentItem.UnknownJson -> Column {
                            Text(text = jsonPrettyPrint(content.json))
                            Text(text = "⚠️ Не удалось распарсить структуру")
                        }
                    }
                }
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
    modifierWithPushToTalk: Modifier,
    isRecording: Boolean,
    showPttButton: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically, 
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
    ) {
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
            placeholder = { Text("") }
        )
        Spacer(modifier = Modifier.width(4.dp))
        
        // Send button
        if (assistantIsThinking) {
            CompactButton(
                onClick = {}, 
                enabled = false,
                modifier = Modifier.fillMaxHeight(),
                tooltip = "Отправка сообщения..."
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        } else {
            CompactButton(
                onClick = {
                    println("📤 Send button clicked, sending message: '$userInput'")
                    coroutineScope.launch {
                        onSendMessage(userInput)
                        onUserInputChange("")
                    }
                },
                modifier = Modifier.fillMaxHeight(),
                tooltip = "Отправить сообщение (Shift+Enter)"
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(16.dp))
            }
        }
        
        // PTT button - only show if STT is enabled
        if (showPttButton) {
            Spacer(modifier = Modifier.width(4.dp))
            
            CompactButton(
                onClick = {},
                modifier = modifierWithPushToTalk.fillMaxHeight(),
                tooltip = if (isRecording) "Идет запись... (отпустите для остановки)" else "Нажать и удерживать для записи (PTT)"
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Recording" else "Push to Talk",
                    modifier = Modifier.size(16.dp),
                    tint = if (isRecording) MaterialTheme.colorScheme.error else LocalContentColor.current
                )
            }
        }
    }
}

@Composable
private fun DevButtons(
    onSendMessage: suspend (String) -> Unit,
    coroutineScope: CoroutineScope,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically, 
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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

@Composable
private fun ToolCallItem(
    toolCall: ToolCallData,
    toolResult: ChatMessage.ContentItem.ToolResult?,
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Determine status icon based on toolResult
    val statusIcon = when {
        toolResult == null -> "⏳" // No result yet - in progress
        toolResult.isError -> "❌" // Error
        else -> "✅" // Success
    }

    // Get tool name for display
    val toolName = when (toolCall) {
        is ClaudeCodeToolCallData.Read -> "Read"
        is ClaudeCodeToolCallData.Edit -> "Edit"
        is ClaudeCodeToolCallData.Bash -> "Bash"
        is ClaudeCodeToolCallData.Grep -> "Grep"
        is ClaudeCodeToolCallData.TodoWrite -> "TodoWrite"
        is ClaudeCodeToolCallData.WebSearch -> "WebSearch"
        is ClaudeCodeToolCallData.WebFetch -> "WebFetch"
        is ClaudeCodeToolCallData.Task -> "Task"
        is ToolCallData.Generic -> toolCall.name
    }

    // Get tool description for display (short version for button)
    val toolDescription = when (toolCall) {
        is ClaudeCodeToolCallData.Read -> toolCall.filePath
        is ClaudeCodeToolCallData.Edit -> toolCall.filePath
        is ClaudeCodeToolCallData.Bash -> toolCall.command.take(30) + if (toolCall.command.length > 30) "..." else ""
        is ClaudeCodeToolCallData.Grep -> toolCall.pattern.take(25) + if (toolCall.pattern.length > 25) "..." else ""
        is ClaudeCodeToolCallData.TodoWrite -> "todo list"
        is ClaudeCodeToolCallData.WebSearch -> toolCall.query.take(30) + if (toolCall.query.length > 30) "..." else ""
        is ClaudeCodeToolCallData.WebFetch -> toolCall.url.take(40) + if (toolCall.url.length > 40) "..." else ""
        is ClaudeCodeToolCallData.Task -> toolCall.description.take(35) + if (toolCall.description.length > 35) "..." else ""
        is ToolCallData.Generic -> toolCall.name
        else -> "unknown"
    }

    // Get full description for expanded view
    val fullToolDescription = when (toolCall) {
        is ClaudeCodeToolCallData.Read -> "Read file ${toolCall.filePath}"
        is ClaudeCodeToolCallData.Edit -> "Edit file ${toolCall.filePath}"
        is ClaudeCodeToolCallData.Bash -> "Execute: ${toolCall.command}"
        is ClaudeCodeToolCallData.Grep -> "Search: ${toolCall.pattern}"
        is ClaudeCodeToolCallData.TodoWrite -> "Update todo list"
        is ClaudeCodeToolCallData.WebSearch -> "Search: ${toolCall.query}"
        is ClaudeCodeToolCallData.WebFetch -> "Fetch: ${toolCall.url}"
        is ClaudeCodeToolCallData.Task -> "Task: ${toolCall.description}"
        is ToolCallData.Generic -> "Tool: ${toolCall.name}"
        else -> "Unknown tool"
    }

    Column {
        // Enhanced tool call button with status + name + description
        DisableSelection {
            CompactButton(
                onClick = {
                    if (toolResult != null) {
                        isExpanded = !isExpanded
                    }
                },
                modifier = Modifier,
                enabled = toolResult != null,
                tooltip = when {
                    toolResult == null -> "Выполняется..."
                    toolResult.isError -> "Ошибка - клик для просмотра"
                    else -> "Успешно - клик для просмотра результата"
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status icon
                    Text(statusIcon)
                    Spacer(modifier = Modifier.width(4.dp))
                    // Tool name and description
                    Text("$toolName: $toolDescription")
                }
            }
        }

        // Animated expandable result content
        AnimatedVisibility(
            visible = isExpanded && toolResult != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            toolResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Show full command/description
                        Text(
                            text = fullToolDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Show result content based on result type
                        SelectionContainer {
                            when (val resultData = result.result) {
                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.Read -> {
                                    Text(
                                        text = resultData.content,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.Edit -> {
                                    Text(
                                        text = resultData.message ?: "Edit completed",
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.Bash -> {
                                    Column {
                                        resultData.stdout?.let {
                                            Text("STDOUT:", style = MaterialTheme.typography.labelSmall)
                                            Text(it, style = MaterialTheme.typography.bodySmall)
                                        }
                                        resultData.stderr?.let {
                                            Text("STDERR:", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.Grep -> {
                                    Text(
                                        text = resultData.content ?: resultData.matches?.joinToString("\n")
                                        ?: "No matches",
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.TodoWrite -> {
                                    Text(
                                        text = resultData.message ?: "TodoWrite completed",
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.WebSearch -> {
                                    Text(
                                        text = resultData.results.toString(),
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.WebFetch -> {
                                    Text(
                                        text = resultData.content,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.Subagent -> {
                                    Text(
                                        text = resultData.response,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ClaudeCodeToolResultData.NullResult -> {
                                    Text(
                                        text = "No result",
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                is com.gromozeka.shared.domain.message.ToolResultData.Generic -> {
                                    Text(
                                        text = resultData.output.toString(),
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}