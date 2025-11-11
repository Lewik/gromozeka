package com.gromozeka.bot.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.services.TTSQueueService
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.CompactButton
import com.gromozeka.bot.ui.LocalTranslation
import com.gromozeka.bot.ui.viewmodel.TabViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(
    viewModel: TabViewModel,

    // Navigation callbacks
    onNewSession: () -> Unit,
    onForkSession: () -> Unit,
    onRestartSession: () -> Unit,
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

    // Context extraction
    onExtractContexts: (() -> Unit)? = null,

    // Context panel
    onShowContextsPanel: (() -> Unit)? = null,

    // Memory
    onRememberThread: (() -> Unit)? = null,

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
    val pendingMessagesCount by viewModel.pendingMessagesCount.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val userInput = uiState.userInput
    val jsonToShow = viewModel.jsonToShow
    val tokenStats by viewModel.tokenStats.collectAsState()

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

                        Spacer(modifier = Modifier.width(8.dp))

                        CompactButton(onClick = onForkSession) {
                            Text(LocalTranslation.current.forkButton)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        CompactButton(onClick = onRestartSession) {
                            Text(LocalTranslation.current.restartButton)
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

                        // Token usage statistics
                        tokenStats?.let { stats ->
                            CompactButton(
                                onClick = { },
                                tooltipMonospace = true,
                                tooltipNoWrap = true,
                                tooltip = buildString {
                                    fun Int.formatWithCommas(): String =
                                        this.toString().reversed().chunked(3).joinToString(",").reversed()

                                    val lines = mutableListOf<String>()

                                    lines.add("Token Usage Statistics")

                                    val totalTokens = stats.totalPromptTokens + stats.totalCompletionTokens + stats.totalThinkingTokens
                                    lines.add("Total: ${totalTokens.formatWithCommas()} tokens")
                                    lines.add("  Prompt:     ${stats.totalPromptTokens.formatWithCommas()}")
                                    lines.add("  Completion: ${stats.totalCompletionTokens.formatWithCommas()}")
                                    if (stats.totalThinkingTokens > 0) {
                                        lines.add("  Thinking:   ${stats.totalThinkingTokens.formatWithCommas()}")
                                    }
                                    if (stats.totalCacheReadTokens > 0) {
                                        lines.add("  Cache Read: ${stats.totalCacheReadTokens.formatWithCommas()}")
                                    }

                                    val contextWindow = stats.modelId?.let {
                                        com.gromozeka.shared.domain.ModelContextWindows.getContextWindow(it)
                                    }
                                    val currentContext = stats.currentContextSize

                                    if (currentContext != null) {
                                        if (contextWindow != null) {
                                            val percentage = (currentContext.toFloat() / contextWindow * 100).toInt()
                                            lines.add("Context: ${currentContext.formatWithCommas()} / ${contextWindow.formatWithCommas()} ($percentage%)")
                                        } else {
                                            lines.add("Context: ${currentContext.formatWithCommas()} / unknown (n/a)")
                                        }
                                    }

                                    if (stats.recentCalls.isNotEmpty()) {
                                        lines.add("Recent ${stats.recentCalls.size} Turns:")

                                        val hasThinking = stats.recentCalls.any { it.thinkingTokens > 0 }

                                        if (hasThinking) {
                                            lines.add("Turn  Prompt  Compl  Think  Total")
                                        } else {
                                            lines.add("Turn  Prompt  Compl  Total")
                                        }

                                        stats.recentCalls.forEach { call ->
                                            val turn = call.turnNumber.toString().padStart(4)
                                            val prompt = call.promptTokens.formatWithCommas().padStart(7)
                                            val completion = call.completionTokens.formatWithCommas().padStart(6)
                                            val total = call.totalTokens.formatWithCommas().padStart(6)

                                            if (hasThinking) {
                                                val thinking = call.thinkingTokens.formatWithCommas().padStart(6)
                                                lines.add("$turn  $prompt  $completion  $thinking  $total")
                                            } else {
                                                lines.add("$turn  $prompt  $completion  $total")
                                            }
                                        }
                                    }

                                    val maxLength = lines.maxOfOrNull { it.length } ?: 0
                                    val separator = "-".repeat(maxLength)

                                    append(lines[0] + "\n")
                                    append(separator + "\n")
                                    for (i in 1 until lines.size) {
                                        if (lines[i].startsWith("Recent")) {
                                            append("\n" + lines[i] + "\n")
                                            append(separator + "\n")
                                        } else {
                                            append(lines[i] + "\n")
                                        }
                                    }
                                }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Assessment,
                                        contentDescription = "Tokens"
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))

                                    val contextPercentage = stats.currentContextSize?.let { currentContext ->
                                        stats.modelId?.let { modelId ->
                                            com.gromozeka.shared.domain.ModelContextWindows.getContextWindow(modelId)
                                                ?.let { contextWindow ->
                                                    (currentContext.toFloat() / contextWindow * 100).toInt()
                                                }
                                        }
                                    }

                                    if (contextPercentage != null) {
                                        Text("$contextPercentage%")
                                    } else {
                                        val totalTokens = stats.totalPromptTokens + stats.totalCompletionTokens + stats.totalThinkingTokens
                                        Text("$totalTokens")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // Context extraction button
                        onExtractContexts?.let { extractCallback ->
                            CompactButton(
                                onClick = extractCallback,
                                tooltip = "Extract contexts from conversation"
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Extract contexts")
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // Context panel button
                        onShowContextsPanel?.let { showContextsCallback ->
                            CompactButton(
                                onClick = showContextsCallback,
                                tooltip = "View saved contexts"
                            ) {
                                Icon(Icons.Default.Book, contentDescription = "View contexts")
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                        }

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
                            isSelected = message.id in uiState.selectedMessageIds,
                            onToggleSelection = { messageId ->
                                viewModel.toggleMessageSelection(messageId)
                            },
                            onShowJson = { json -> viewModel.jsonToShow = json },
                            onSpeakRequest = { text, tone ->
                                coroutineScope.launch {
                                    ttsQueueService.enqueue(TTSQueueService.Task(text, tone))
                                }
                            },
                            onEditRequest = { messageId ->
                                viewModel.startEditMessage(messageId)
                            },
                            onDeleteRequest = { messageId ->
                                coroutineScope.launch {
                                    viewModel.deleteMessage(messageId)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Squash button (shown when 2+ messages selected)
                if (uiState.selectedMessageIds.size >= 2) {
                    DisableSelection {
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.squashSelectedMessages()
                                }
                            },
                            tooltip = "Squash ${uiState.selectedMessageIds.size} selected messages"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MergeType, contentDescription = "Squash")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Squash (${uiState.selectedMessageIds.size})")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                DisableSelection {
                    MessageInput(
                        userInput = userInput,
                        onUserInputChange = { viewModel.updateUserInput(it) },
                        isWaitingForResponse = isWaitingForResponse,
                        pendingMessagesCount = pendingMessagesCount,
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

                        // Remember button (if memory is enabled and callback provided)
                        onRememberThread?.let { rememberCallback ->
                            CompactButton(
                                onClick = {
                                    coroutineScope.launch {
                                        rememberCallback()
                                    }
                                },
                                tooltip = "Remember this conversation to vector memory"
                            ) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = "Remember"
                                )
                            }
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

    // Edit message dialog at top level to avoid hierarchy issues
    if (uiState.editingMessageId != null) {
        EditMessageDialog(
            messageText = uiState.editingMessageText,
            onTextChange = { viewModel.updateEditingMessageText(it) },
            onConfirm = {
                coroutineScope.launch {
                    viewModel.confirmEditMessage()
                }
            },
            onDismiss = {
                viewModel.cancelEditMessage()
            }
        )
    }
}
