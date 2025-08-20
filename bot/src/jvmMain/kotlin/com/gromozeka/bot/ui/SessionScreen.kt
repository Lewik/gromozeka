package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.gromozeka.bot.utils.TokenUsageCalculator
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import com.gromozeka.shared.domain.message.MessageTagDefinition
import com.gromozeka.shared.domain.message.ToolCallData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.JsonElement

@Composable
fun SessionScreen(
    viewModel: com.gromozeka.bot.ui.viewmodel.SessionViewModel,

    // Navigation callbacks
    onBackToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onOpenTab: (String) -> Unit, // Callback to open new tab with project path  
    onOpenTabWithMessage: ((String, String) -> Unit)? = null, // Callback to open new tab with initial message
    onCloseTab: (() -> Unit)? = null,

    // Services
    ttsQueueService: TTSQueueService,
    coroutineScope: CoroutineScope,
    modifierWithPushToTalk: Modifier,
    isRecording: Boolean = false,

    // Settings - moved to ChatApplication level, but we still need settings for UI
    settings: Settings,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,

    // Dev mode
    isDev: Boolean = false,
) {
    // UI state management - LazyColumn state instead of scroll state
    val lazyListState = rememberLazyListState()
    var stickyToBottom by remember { mutableStateOf(true) }

    // All data comes from ViewModel
    val filteredHistory by viewModel.filteredMessages.collectAsState()
    val toolResultsMap by viewModel.toolResultsMap.collectAsState()
    val isWaitingForResponse by viewModel.isWaitingForResponse.collectAsState()
    val tokenUsage by viewModel.tokenUsage.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val userInput = uiState.userInput
    val jsonToShow = viewModel.jsonToShow

    // Format UI strings here in UI layer
    val formattedTokenUsage = remember(tokenUsage) {
        formatTokenUsageForDisplay(tokenUsage)
    }
    val tokenUsageTooltip = remember(tokenUsage) {
        createTokenUsageTooltip(tokenUsage)
    }

    // Message sending function using ViewModel
    val onSendMessage: (String) -> Unit = { message ->
        coroutineScope.launch {
            viewModel.sendMessageToSession(message)
        }
    }

    // LazyColumn sticky to bottom logic
    val isAtBottom by remember {
        derivedStateOf {
            lazyListState.layoutInfo.let { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val totalItems = layoutInfo.totalItemsCount
                lastVisibleItem?.index == totalItems - 1 || totalItems == 0
            }
        }
    }

    LaunchedEffect(lazyListState.firstVisibleItemIndex, isAtBottom) {
        if (isAtBottom) {
            stickyToBottom = true
        } else if (lazyListState.isScrollInProgress) {
            stickyToBottom = false
        }
    }

    LaunchedEffect(filteredHistory.size) {
        if (stickyToBottom && filteredHistory.isNotEmpty()) {
            lazyListState.animateScrollToItem(filteredHistory.size - 1)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Main chat content
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                DisableSelection {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CompactButton(onClick = onNewSession) {
                            Text(LocalTranslation.current.newSessionShort)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Message count
                        CompactButton(
                            onClick = { },
                            tooltip = LocalTranslation.current.messageCountTooltip.format(filteredHistory.size)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ChatBubbleOutline,
                                    contentDescription = "Messages"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${filteredHistory.size}")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Token usage count  
                        CompactButton(
                            onClick = { },
                            tooltip = tokenUsageTooltip
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.MonetizationOn,
                                    contentDescription = "Tokens"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(formattedTokenUsage)
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Settings button
                        CompactButton(
                            onClick = { onShowSettingsPanelChange(!showSettingsPanel) },
                            tooltip = LocalTranslation.current.settingsTooltip
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = LocalTranslation.current.settingsTooltip)
                        }

                        // Close tab button (if onCloseTab callback is provided)
                        onCloseTab?.let { closeCallback ->
                            Spacer(modifier = Modifier.width(8.dp))
                            CompactButton(
                                onClick = closeCallback,
                                tooltip = LocalTranslation.current.closeTabTooltip
                            ) {
                                Icon(Icons.Default.Close, contentDescription = LocalTranslation.current.closeTabTooltip)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // LazyColumn instead of Column + forEach
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredHistory) { message ->
                        MessageItem(
                            message = message,
                            settings = settings,
                            toolResultsMap = toolResultsMap,
                            onShowJson = { json -> viewModel.jsonToShow = json },
                            onSpeakRequest = { text, tone ->
                                coroutineScope.launch {
                                    ttsQueueService.enqueue(TTSQueueService.Task(text, tone))
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                DisableSelection {
                    MessageInput(
                        userInput = userInput,
                        onUserInputChange = { viewModel.updateUserInput(it) },
                        isWaitingForResponse = isWaitingForResponse,
                        onSendMessage = { message ->
                            onSendMessage(message)
                        },
                        coroutineScope = coroutineScope,
                        modifierWithPushToTalk = modifierWithPushToTalk,
                        isRecording = isRecording,
                        showPttButton = settings.enableStt
                    )

                    // Message Tags and Screenshot button
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Message Tags
                        viewModel.availableMessageTags.forEach { messageTag ->
                            MultiStateMessageTagButton(
                                messageTag = messageTag,
                                activeMessageTags = uiState.activeMessageTags,
                                onToggleTag = { tag, controlIdx -> viewModel.toggleMessageTag(tag, controlIdx) }
                            )
                        }

                        // Screenshot button
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.captureAndAddToInput()
                                }
                            },
                            tooltip = LocalTranslation.current.screenshotTooltip
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = LocalTranslation.current.screenshotTooltip
                            )
                        }
                    }

                    // Dev buttons only
                    if (isDev) {
                        Spacer(modifier = Modifier.height(8.dp))
                        DevButtons(
                            onSendMessage = { message ->
                                coroutineScope.launch {
                                    onSendMessage(message)
                                }
                            }
                        )
                    }
                }
            }
        }

        // SettingsPanel moved to ChatApplication level for consistency
    }

    // JSON Dialog at top level to avoid hierarchy issues
    jsonToShow?.let { json ->
        JsonDialog(
            json = json,
            onDismiss = { viewModel.jsonToShow = null }
        )
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    settings: Settings,
    toolResultsMap: Map<String, ChatMessage.ContentItem.ToolResult>,
    onShowJson: (String) -> Unit = {},
    onSpeakRequest: (String, String) -> Unit = { _, _ -> },
) {
    val translation = LocalTranslation.current

    // Combined metadata button data
    val roleIcon = when (message.role) {
        ChatMessage.Role.USER -> Icons.Default.Person
        ChatMessage.Role.ASSISTANT -> Icons.Default.DeveloperBoard
        ChatMessage.Role.SYSTEM -> Icons.Default.Settings
    }

    val contentIcons = message.content.mapNotNull { content ->
        when (content) {
            is ChatMessage.ContentItem.UserMessage -> null
            is ChatMessage.ContentItem.ToolCall -> Icons.Default.Build
            is ChatMessage.ContentItem.ToolResult -> null // Don't show ToolResult icon - they're integrated into ToolCall
            is ChatMessage.ContentItem.Thinking -> Icons.Default.Psychology
            is ChatMessage.ContentItem.System -> Icons.Default.Settings
            is ChatMessage.ContentItem.AssistantMessage -> null
            is ChatMessage.ContentItem.ImageItem -> Icons.Default.Image
            is ChatMessage.ContentItem.UnknownJson -> Icons.Default.Warning
        }
    }.distinct()

    val hasContentIcons = contentIcons.isNotEmpty()

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
                    is ChatMessage.ContentItem.ImageItem -> "Image"
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
                structured.ttsText?.let { append("🎵 TTS: $it") }
                if (structured.ttsText != null && structured.voiceTone != null) append("\n")
                structured.voiceTone?.let { append("🎭 Tone: $it") }
            }
        }

        append(translation.contextMenuHint)
    }

    // Compact horizontal layout - simple Row without IntrinsicSize.Min
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                            add(ContextMenuItem(translation.showJsonMenuItem) {
                                val jsonToShow = (message.originalJson ?: "No JSON available")
                                onShowJson(jsonToShow)
                            })
                        }

                        add(ContextMenuItem(translation.copyMarkdownMenuItem) {
                            val markdownContent = message.content
                                .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
                                .firstOrNull()?.structured?.fullText
                                ?: message.content
                                    .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
                                    .firstOrNull()?.text
                                ?: translation.contentUnavailable
                            clipboardManager.setText(AnnotatedString(markdownContent))
                        })

                        if (hasTtsText) {
                            add(ContextMenuItem(translation.speakMenuItem) {
                                val ttsText = assistantContent.structured.ttsText!!
                                val voiceTone = assistantContent.structured.voiceTone ?: ""
                                onSpeakRequest(ttsText, voiceTone)
                            })
                        }
                    }
                }
            ) {
                CompactButton(
                    onClick = { },
                    tooltip = tooltipText,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            roleIcon,
                            contentDescription = message.role.name
                        )

                        // Content type icons
                        if (hasContentIcons) {
                            contentIcons.forEach { contentIcon ->
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    contentIcon,
                                    contentDescription = "Content type"
                                )
                            }
                        } else {
                            // Default chat bubble icon
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = "Message"
                            )
                        }
                    }
                }
            }
        }

        // Message content (right, expandable) - LazyColumn should provide proper constraints
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp) // Should work now with LazyColumn constraints
                .background(
                    color = if (message.role == ChatMessage.Role.USER &&
                        message.content.any { it is ChatMessage.ContentItem.UserMessage }
                    ) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else Color.Transparent
                )
                .padding(horizontal = if (message.content.any { it is ChatMessage.ContentItem.ToolCall }) 0.dp else 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            message.content.forEach { content ->
                when (content) {
                    is ChatMessage.ContentItem.UserMessage -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                GromozekaMarkdown(
                                    content = content.text,
                                    modifier = Modifier
                                )
                            }
                            FlowRow(
                                maxItemsInEachRow = 4,
                                verticalArrangement = Arrangement.Top,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                message.activeTags.forEach { tag ->
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = tag.title,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        colors = AssistChipDefaults.assistChipColors(),
                                    )
                                }
                            }
                        }
                    }

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

                    is ChatMessage.ContentItem.ImageItem -> {
                        when (val source = content.source) {
                            is ChatMessage.ImageSource.Base64ImageSource -> {
                                // Base64 too long - show placeholder
                                Text(
                                    LocalTranslation.current.imageDisplayText.format(
                                        source.mediaType,
                                        source.data.length
                                    )
                                )
                            }

                            is ChatMessage.ImageSource.UrlImageSource -> {
                                // URL can be shown in full
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Image")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(source.url)
                                }
                            }

                            is ChatMessage.ImageSource.FileImageSource -> {
                                // File ID can be shown in full
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Image")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("File: ${source.fileId}")
                                }
                            }
                        }
                    }

                    is ChatMessage.ContentItem.Thinking -> GromozekaMarkdown(content = content.thinking)
                    is ChatMessage.ContentItem.System -> Text(text = content.content)
                    is ChatMessage.ContentItem.AssistantMessage -> GromozekaMarkdown(content = content.structured.fullText)
                    is ChatMessage.ContentItem.UnknownJson -> Column {
                        Text(text = jsonPrettyPrint(content.json))
                        Text(text = LocalTranslation.current.parseErrorText)
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
    isWaitingForResponse: Boolean,
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
                    if (!isWaitingForResponse && event.key == Key.Enter && event.isShiftPressed && event.type == KeyEventType.KeyDown && userInput.isNotBlank()) {
                        coroutineScope.launch {
                            onSendMessage(userInput)
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
        if (isWaitingForResponse) {
            CompactButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxHeight(),
                tooltip = LocalTranslation.current.sendingMessageTooltip
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        } else {
            CompactButton(
                onClick = {
                    coroutineScope.launch {
                        onSendMessage(userInput)
                    }
                },
                modifier = Modifier.fillMaxHeight(),
                tooltip = LocalTranslation.current.sendMessageTooltip
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }

        // PTT button - only show if STT is enabled
        if (showPttButton) {
            Spacer(modifier = Modifier.width(4.dp))

            CompactButton(
                onClick = {},
                modifier = modifierWithPushToTalk.fillMaxHeight(),
                tooltip = if (isRecording) LocalTranslation.current.recordingTooltip else LocalTranslation.current.pttButtonTooltip
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Mic,
                    contentDescription = if (isRecording) LocalTranslation.current.recordingText else LocalTranslation.current.pushToTalkText,
                    tint = if (isRecording) MaterialTheme.colorScheme.error else LocalContentColor.current
                )
            }
        }
    }
}

@Composable
private fun DevButtons(
    onSendMessage: suspend (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CompactButton(onClick = {
            kotlinx.coroutines.runBlocking {
                onSendMessage("Расскажи скороговорку")
            }
        }) {
            Text("🗣 Скороговорка")
        }

        CompactButton(onClick = {
            kotlinx.coroutines.runBlocking {
                onSendMessage("Создай таблицу с примерами разных типов данных в программировании")
            }
        }) {
            Text("📊 Таблица")
        }

        CompactButton(onClick = {
            kotlinx.coroutines.runBlocking {
                onSendMessage("Загугли последние новости про Google")
            }
        }) {
            Text("🔍 Загугли про гугл")
        }

        CompactButton(onClick = {
            kotlinx.coroutines.runBlocking {
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
                    Text(LocalTranslation.current.viewOriginalJson)
                    CompactButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
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
        toolResult == null -> Icons.Default.Schedule // No result yet - in progress
        toolResult.isError -> Icons.Default.Error // Error
        else -> Icons.Default.CheckCircle // Success
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
                    Icon(
                        statusIcon,
                        contentDescription = "Tool status"
                    )
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

                        // Show result content - now it's a list of Data items
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                result.result.forEach { dataItem ->
                                    when (dataItem) {
                                        is ChatMessage.ContentItem.ToolResult.Data.Text -> {
                                            Text(
                                                text = dataItem.content,
                                                modifier = Modifier.fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        is ChatMessage.ContentItem.ToolResult.Data.Base64Data -> {
                                            when {
                                                dataItem.mediaType.type == "image" -> {
                                                    // Base64 image - show placeholder with truncation
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Image,
                                                            contentDescription = "Image"
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "[Image ${dataItem.mediaType.value} - ${dataItem.data.length} chars Base64]",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }

                                                else -> {
                                                    // Non-image Base64 data - show truncated version
                                                    val truncated = if (dataItem.data.length > 100) {
                                                        "${dataItem.data.take(50)}...[${dataItem.data.length - 100} chars]...${
                                                            dataItem.data.takeLast(
                                                                50
                                                            )
                                                        }"
                                                    } else {
                                                        dataItem.data
                                                    }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Description,
                                                            contentDescription = "Document"
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "[${dataItem.mediaType.value}] $truncated",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        is ChatMessage.ContentItem.ToolResult.Data.UrlData -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    Icons.Default.Link,
                                                    contentDescription = "URL"
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${dataItem.url}${dataItem.mediaType?.let { " (${it.value})" } ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        is ChatMessage.ContentItem.ToolResult.Data.FileData -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    Icons.Default.Folder,
                                                    contentDescription = "File"
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "File: ${dataItem.fileId}${dataItem.mediaType?.let { " (${it.value})" } ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
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
        }
    }
}

@Composable
private fun MultiStateMessageTagButton(
    messageTag: MessageTagDefinition,
    activeMessageTags: Set<String>,
    onToggleTag: (MessageTagDefinition, Int) -> Unit,
) {
    // Find which control is currently active based on activeMessageTags
    val activeControlIndex = messageTag.controls.indexOfFirst { control ->
        control.data.id in activeMessageTags
    }
    val selectedIndex = if (activeControlIndex >= 0) activeControlIndex else messageTag.selectedByDefault

    // Convert MessageTagDefinition.Controls to SegmentedButtonOptions
    val options = messageTag.controls.map { control ->
        SegmentedButtonOption(
            text = control.data.title,
            tooltip = control.data.instruction
        )
    }

    CustomSegmentedButtonGroup(
        options = options,
        selectedIndex = selectedIndex,
        onSelectionChange = { controlIndex ->
            onToggleTag(messageTag, controlIndex)
        }
    )
}


// === Token Usage UI Formatting ===

/**
 * Format token usage for display in UI
 */
private fun formatTokenUsageForDisplay(usage: TokenUsageCalculator.SessionTokenUsage): String {
    val total = usage.grandTotal
    val percent = (usage.contextUsagePercent * 100).toInt()

    return when {
        total < 1000 -> "${total}t"
        total < 10000 -> "${total / 1000}k"
        else -> "${total / 1000}k"
    } + if (percent > 10) " (${percent}%)" else ""
}

/**
 * Create detailed breakdown tooltip for token usage
 */
private fun createTokenUsageTooltip(usage: TokenUsageCalculator.SessionTokenUsage): String {
    return buildString {
        appendLine("📊 Токены сессии (как в Claude Code):")
        appendLine("• Ввод: ${usage.totalInputTokens}")
        appendLine("• Вывод: ${usage.totalOutputTokens}")
        appendLine("• Кэш чтение: ${usage.currentCacheReadTokens}")
        appendLine()
        appendLine("Total: ${usage.grandTotal}")
        appendLine()
        appendLine("Cache creation: ${usage.totalCacheCreationTokens} (separate)")
        appendLine("Total with cache: ${usage.totalCacheTokens}")
        appendLine()
        val percent = (usage.contextUsagePercent * 100).toInt()
        appendLine("Context: ${percent}% of ~200k")

        if (percent > 80) {
            appendLine()
            appendLine("WARNING: Approaching context limit!")
        }
    }
}