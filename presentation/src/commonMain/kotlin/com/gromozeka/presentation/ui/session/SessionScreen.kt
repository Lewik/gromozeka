package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TtsTask
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.TtsQueue
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.ToggleButtonGroup
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.format
import com.gromozeka.presentation.ui.viewmodel.PendingUserMessage
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
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
    ttsQueueService: TtsQueue,
    coroutineScope: CoroutineScope,
    pttEventHandler: PttEventHandler,
    isRecording: Boolean = false,
    pttStatusMessage: String? = null,

    // Settings - moved to ChatApplication level, but we still need settings for UI
    settings: Settings,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    showMemoryActionItemsPanel: Boolean,
    onShowMemoryActionItemsPanelChange: (Boolean) -> Unit,

    // Tab Settings Panel
    onShowPromptsPanelChange: (Boolean) -> Unit,

    // Context extraction
    onExtractContexts: (() -> Unit)? = null,

    // Context panel
    onShowContextsPanel: (() -> Unit)? = null,

    // Memory
    onRememberThread: (() -> Unit)? = null,
    onConsolidateMemory: (() -> Unit)? = null,
    onRepairMemory: (() -> Unit)? = null,
    onMaintainMemoryEntities: (() -> Unit)? = null,
    onApplyMemoryRetention: (() -> Unit)? = null,
    onInsertCurrentLocation: (() -> Unit)? = null,

    // Dev mode
    isDev: Boolean = false,
    isCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
) {
    // UI state management - LazyColumn state instead of scroll state
    val lazyListState = rememberLazyListState()
    var stickyToBottom by remember { mutableStateOf(true) }

    // All data comes from ViewModel
    val filteredHistory by viewModel.filteredMessages.collectAsState()
    val toolResultsMap by viewModel.toolResultsMap.collectAsState()
    val isWaitingForResponse by viewModel.isWaitingForResponse.collectAsState()
    val executionPauseRequested by viewModel.executionPauseRequested.collectAsState()
    val pendingMessagesCount by viewModel.pendingMessagesCount.collectAsState()
    val pendingMessages by viewModel.pendingMessages.collectAsState()
    val activeToolExecutions by viewModel.activeToolExecutions.collectAsState()
    val runtimeTrace by viewModel.runtimeTrace.collectAsState()
    val runtimeSnapshot by viewModel.runtimeSnapshot.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val userInput = uiState.userInput
    val jsonToShow = viewModel.jsonToShow
    val tokenStats by viewModel.tokenStats.collectAsState()
    val topToolbarScrollState = rememberScrollState()
    val editToolbarScrollState = rememberScrollState()
    val bottomToolbarScrollState = rememberScrollState()
    
    // Context percentage calculation (used by Agent button and Send button)
    val contextPercentage = tokenStats?.currentContextSize?.let { currentContext ->
        tokenStats?.contextWindowTokens?.let { contextWindow ->
            (currentContext.toFloat() / contextWindow * 100).toInt()
        }
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTestTag.SessionScreen.value)
    ) {
        // Main chat content
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                DisableSelection {
                    // Row 1: Navigation buttons (New, Fork, Restart) + Info buttons (Message count, Token stats, etc.)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isCompactLayout) Modifier.horizontalScroll(topToolbarScrollState) else Modifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Navigation buttons (left side)
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

                        if (isCompactLayout) {
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

//                        // Right-side buttons
//                        if (uiState.editMode) {
//                            // Exit edit mode button (replaces all other buttons in edit mode)
//                            CompactButton(
//                                onClick = { viewModel.toggleEditMode() },
//                                tooltip = "Exit edit mode"
//                            ) {
//                                Row(verticalAlignment = Alignment.CenterVertically) {
//                                    Icon(Icons.Default.Close, contentDescription = "Exit edit mode")
//                                    Spacer(modifier = Modifier.width(4.dp))
//                                    Text("Exit Edit Mode")
//                                }
//                            }
//                        } else {
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

                        // Agent button
                        CompactButton(
                            onClick = { onShowPromptsPanelChange(true) },
                            modifier = Modifier.testTag(UiTestTag.AgentButton.value),
                            tooltip = when {
                                contextPercentage != null && contextPercentage >= 90 -> "Agent (критическое заполнение контекста $contextPercentage%)"
                                contextPercentage != null && contextPercentage >= 75 -> "Agent (контекст заполнен на $contextPercentage%)"
                                else -> "Agent"
                            },
                            colors = when {
                                contextPercentage != null && contextPercentage >= 90 -> ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                                contextPercentage != null && contextPercentage >= 75 -> ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                                else -> ButtonDefaults.buttonColors()
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = "Agent")

                                // Show context window percentage if available
                                contextPercentage?.let { percentage ->
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("$percentage%")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Memory action items button
                        CompactButton(
                            onClick = { onShowMemoryActionItemsPanelChange(!showMemoryActionItemsPanel) },
                            modifier = Modifier.testTag(UiTestTag.MemoryActionItemsButton.value),
                            tooltip = "Memory action items"
                        ) {
                            Icon(Icons.Default.ListAlt, contentDescription = "Memory action items")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Settings button
                        CompactButton(
                            onClick = { onShowSettingsPanelChange(!showSettingsPanel) },
                            modifier = Modifier.testTag(UiTestTag.SettingsButton.value),
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // Row 2: Message editing tools (selection buttons + action buttons)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isCompactLayout) Modifier.horizontalScroll(editToolbarScrollState) else Modifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Selection buttons
                        val selectionOptions = remember {
                            listOf(
                                com.gromozeka.presentation.ui.ToggleButtonOption(
                                    Icons.Default.SelectAll,
                                    "Select/Deselect All"
                                ),
                                com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Person, "User Messages"),
                                com.gromozeka.presentation.ui.ToggleButtonOption(
                                    Icons.Default.DeveloperBoard,
                                    "Assistant Messages"
                                ),
                                com.gromozeka.presentation.ui.ToggleButtonOption(
                                    Icons.Default.Psychology,
                                    "Thinking Blocks"
                                ),
                                com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Build, "Tool Calls"),
                                com.gromozeka.presentation.ui.ToggleButtonOption(
                                    Icons.Default.ChatBubbleOutline,
                                    "Plain Messages"
                                ),
                            )
                        }

                        val selectedIndices = remember(filteredHistory, uiState.selectedMessageIds) {
                            buildSet {
                                if (uiState.selectedMessageIds.size == filteredHistory.size && filteredHistory.isNotEmpty()) {
                                    add(0)
                                }

                                val userMessages = filteredHistory.filter { it.role == Conversation.Message.Role.USER }
                                if (userMessages.isNotEmpty() && userMessages.all { it.id in uiState.selectedMessageIds }) {
                                    add(1)
                                }

                                val assistantMessages =
                                    filteredHistory.filter { it.role == Conversation.Message.Role.ASSISTANT }
                                if (assistantMessages.isNotEmpty() && assistantMessages.all { it.id in uiState.selectedMessageIds }) {
                                    add(2)
                                }

                                val thinkingMessages = filteredHistory.filter { message ->
                                    message.content.any {
                                        (it as? Conversation.Message.ContentItem.Thinking)?.isVisible == true
                                    }
                                }
                                if (thinkingMessages.isNotEmpty() && thinkingMessages.all { it.id in uiState.selectedMessageIds }) {
                                    add(3)
                                }

                                val toolMessages = filteredHistory.filter { message ->
                                    message.content.any { it is Conversation.Message.ContentItem.ToolCall }
                                }
                                if (toolMessages.isNotEmpty() && toolMessages.all { it.id in uiState.selectedMessageIds }) {
                                    add(4)
                                }

                                val plainMessages = filteredHistory.filter { message ->
                                    message.content.none {
                                        (it as? Conversation.Message.ContentItem.Thinking)?.isVisible == true
                                    } &&
                                            message.content.none { it is Conversation.Message.ContentItem.ToolCall }
                                }
                                if (plainMessages.isNotEmpty() && plainMessages.all { it.id in uiState.selectedMessageIds }) {
                                    add(5)
                                }
                            }
                        }

                        ToggleButtonGroup(
                            options = selectionOptions,
                            selectedIndices = selectedIndices,
                            onToggle = { index ->
                                when (index) {
                                    0 -> viewModel.toggleSelectAll(filteredHistory.map { it.id }.toSet())
                                    1 -> viewModel.toggleSelectUserMessages()
                                    2 -> viewModel.toggleSelectAssistantMessages()
                                    3 -> viewModel.toggleSelectThinkingMessages()
                                    4 -> viewModel.toggleSelectToolMessages()
                                    5 -> viewModel.toggleSelectPlainMessages()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Action buttons
                        // Concat - disabled when 0 or 1 message selected
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.squashSelectedMessages()
                                }
                            },
                            enabled = uiState.selectedMessageIds.size >= 2,
                            tooltip = "Concatenate messages (instant, no AI)"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = "Concat")
                                Spacer(modifier = Modifier.width(4.dp))
                                if (!isCompactLayout) Text("Concat")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Distill - disabled when 0 messages selected
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.distillSelectedMessages()
                                }
                            },
                            enabled = uiState.selectedMessageIds.isNotEmpty(),
                            tooltip = "Distill messages (AI context transfer)"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Compress, contentDescription = "Distill")
                                Spacer(modifier = Modifier.width(4.dp))
                                if (!isCompactLayout) Text("Distill")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Summarize - disabled when 0 messages selected
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.summarizeSelectedMessages()
                                }
                            },
                            enabled = uiState.selectedMessageIds.isNotEmpty(),
                            tooltip = "Summarize messages (AI history compression)"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = "Summarize")
                                Spacer(modifier = Modifier.width(4.dp))
                                if (!isCompactLayout) Text("Summarize")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Delete - disabled when 0 messages selected
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.deleteSelectedMessages()
                                }
                            },
                            enabled = uiState.selectedMessageIds.isNotEmpty(),
                            tooltip = "Delete selected message(s)"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                                Spacer(modifier = Modifier.width(4.dp))
                                if (!isCompactLayout) Text("Delete")
                            }
                        }

                        if (isCompactLayout) {
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // Selected count (right side)
                        Text("Selected: ${uiState.selectedMessageIds.size}")

                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // LazyColumn instead of Column + forEach
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(UiTestTag.MessageList.value),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredHistory) { message ->
                        MessageItem(
                            message = message,
                            settings = settings,
                            toolResultsMap = toolResultsMap,
                            projectPath = viewModel.projectPath,
                            isSelected = message.id in uiState.selectedMessageIds,
                            collapsedContentItems = uiState.collapsedContentItems[message.id] ?: emptySet(),
                            onToggleSelection = { messageId, isShiftPressed ->
                                viewModel.toggleMessageSelectionRange(messageId, isShiftPressed)
                            },
                            onToggleContentItemCollapse = { messageId, contentItemIndex ->
                                viewModel.toggleContentItemCollapse(messageId, contentItemIndex)
                            },
                            onShowJson = { json -> viewModel.jsonToShow = json },
                            onSpeakRequest = { text, tone ->
                                coroutineScope.launch {
                                    ttsQueueService.enqueue(TtsTask(text, tone))
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

                DisableSelection {
                    PendingMessagesPanel(
                        isWaitingForResponse = isWaitingForResponse,
                        pendingMessages = pendingMessages,
                        onSendInCurrentTurn = viewModel::sendPendingMessageInCurrentTurn,
                        onEdit = viewModel::editPendingMessage,
                        onCancel = viewModel::cancelPendingMessage,
                    )

                    ConversationProgressStrip(
                        isWaitingForResponse = isWaitingForResponse,
                        executionPauseRequested = executionPauseRequested,
                        pendingMessages = pendingMessages,
                        toolExecutions = activeToolExecutions,
                        runtimeTrace = runtimeTrace,
                        runtimeSnapshot = runtimeSnapshot,
                        onPause = viewModel::pauseExecution,
                        onResume = viewModel::resumeExecution,
                        onStop = viewModel::stopExecution,
                        onInterrupt = viewModel::interrupt,
                    )

                    MessageInput(
                        userInput = userInput,
                        onUserInputChange = { viewModel.updateUserInput(it) },
                        isWaitingForResponse = isWaitingForResponse,
                        pendingMessagesCount = pendingMessagesCount,
                        onSendMessage = { message ->
                            onSendMessage(message)
                        },
                        coroutineScope = coroutineScope,
                        pttEventHandler = pttEventHandler,
                        isRecording = isRecording,
                        showPttButton = settings.userProfile.speechSettings.speechToText.enabled,
                        clientPlatform = clientPlatform,
                        pttStatusMessage = pttStatusMessage,
                        contextPercentage = contextPercentage
                    )

                    // Message Tags and Screenshot button
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isCompactLayout) Modifier.horizontalScroll(bottomToolbarScrollState) else Modifier)
                            .padding(vertical = 4.dp),
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

                        onInsertCurrentLocation?.let { insertCurrentLocation ->
                            CompactButton(
                                onClick = insertCurrentLocation,
                                tooltip = "Insert current device location"
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = "Insert location"
                                )
                            }
                        }

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

                        // Remember button (if memory is enabled and callback provided)
                        onRememberThread?.let { rememberCallback ->
                            CompactButton(
                                onClick = {
                                    coroutineScope.launch {
                                        rememberCallback()
                                    }
                                },
                                tooltip = "Remember this conversation in typed memory"
                            ) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = "Remember"
                                )
                            }
                        }

                        onConsolidateMemory?.let { consolidateCallback ->
                            CompactButton(
                                onClick = {
                                    coroutineScope.launch {
                                        consolidateCallback()
                                    }
                                },
                                tooltip = "Run note consolidation"
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MergeType,
                                    contentDescription = "Consolidate Memory"
                                )
                            }
                        }

                        onRepairMemory?.let { repairCallback ->
                            CompactButton(
                                onClick = {
                                    coroutineScope.launch {
                                        repairCallback()
                                    }
                                },
                                tooltip = "Run memory repair"
                            ) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = "Repair Memory"
                                )
                            }
                        }

                        onMaintainMemoryEntities?.let { maintainEntitiesCallback ->
                            CompactButton(
                                onClick = {
                                    coroutineScope.launch {
                                        maintainEntitiesCallback()
                                    }
                                },
                                tooltip = "Run entity maintenance"
                            ) {
                                Icon(
                                    Icons.Default.AccountTree,
                                    contentDescription = "Entity Maintenance"
                                )
                            }
                        }

                        onApplyMemoryRetention?.let { retentionCallback ->
                            CompactButton(
                                onClick = {
                                    coroutineScope.launch {
                                        retentionCallback()
                                    }
                                },
                                tooltip = "Apply memory retention"
                            ) {
                                Icon(
                                    Icons.Default.Inventory2,
                                    contentDescription = "Memory Retention"
                                )
                            }
                        }

                    }

                    // Dev buttons only
                    if (isDev) {
                        Spacer(modifier = Modifier.height(8.dp))
                        DevButtons(
                            onSendMessage = { message ->
                                onSendMessage(message)
                            },
                            coroutineScope = coroutineScope,
                        )
                    }
                }
            }
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
}

@Composable
private fun ConversationProgressStrip(
    isWaitingForResponse: Boolean,
    executionPauseRequested: Boolean,
    pendingMessages: List<PendingUserMessage>,
    toolExecutions: List<ConversationRuntimeToolExecution>,
    runtimeTrace: List<ConversationRuntimeTraceEntry>,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val isReady = !isWaitingForResponse && pendingMessages.isEmpty()
    val runningTools = toolExecutions
        .filter { it.status == ConversationRuntimeToolExecution.Status.RUNNING }
        .map { it.toolName }
        .distinct()
    val statusText = when {
        executionPauseRequested ->
            "Пауза запрошена. Агент остановится на ближайшей безопасной границе."
        runningTools.isNotEmpty() ->
            "Инструменты выполняются: ${runningTools.joinToString(", ")}"
        isWaitingForResponse && pendingMessages.isNotEmpty() ->
            "Агент отвечает. В очереди ${pendingMessages.size}."
        isWaitingForResponse ->
            "Агент отвечает. Можно отправить уточнение или поставить сообщение в очередь."
        pendingMessages.isNotEmpty() ->
            "В очереди ${pendingMessages.size}"
        else ->
            "Готов к отправке"
    }
    val traceText = runtimeTrace.lastOrNull()?.runtimeTraceText()
    val runtimeDetailsText = runtimeSnapshot?.runtimeDetailsText()
    val containerColor = if (isReady) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val contentColor = if (isReady) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        isReady -> Icons.Default.CheckCircle
        pendingMessages.isEmpty() -> Icons.Default.HourglassTop
        else -> Icons.Default.PlaylistAddCheck
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag(UiTestTag.ConversationProgressStrip.value),
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                val detailText = runtimeDetailsText?.takeIf { it.isNotBlank() } ?: traceText
                if (!detailText.isNullOrBlank() && !isReady) {
                    Text(
                        text = detailText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f)
                    )
                }
            }
            if (isWaitingForResponse) {
                if (executionPauseRequested) {
                    TextButton(onClick = onResume) {
                        Text("Продолжить")
                    }
                } else {
                    TextButton(onClick = onPause) {
                        Text("Пауза")
                    }
                }
                TextButton(onClick = onStop) {
                    Text("Стоп")
                }
                TextButton(onClick = onInterrupt) {
                    Text("Прервать")
                }
            }
        }
    }
}

private fun ConversationRuntimeSnapshot.runtimeDetailsText(): String =
    buildList {
        state?.let { state ->
            add("control=${state.controlState.name.lowercase()}")
            state.activeWorkerId?.takeIf { it.isNotBlank() }?.let { add("worker=$it") }
            state.activeTaskId?.value?.let { add("task=$it") }
        }
        activeTask?.payload?.let { payload ->
            add(
                "payload=" + when (payload) {
                    is com.gromozeka.domain.service.ConversationRuntimeTask.Payload.UserTurn -> "user_turn"
                    is com.gromozeka.domain.service.ConversationRuntimeTask.Payload.LlmCall -> "llm_call"
                    is com.gromozeka.domain.service.ConversationRuntimeTask.Payload.ToolExecution -> "tool_execution"
                    is com.gromozeka.domain.service.ConversationRuntimeTask.Payload.MemoryRecall -> "memory_recall"
                }
            )
        }
        if (pendingTasks.isNotEmpty()) {
            add("pending=${pendingTasks.size}")
        }
        if (failedTasks.isNotEmpty()) {
            add("failed=${failedTasks.size}")
        }
    }.joinToString(" · ")

private fun ConversationRuntimeTraceEntry.runtimeTraceText(): String =
    buildString {
        append(kind.name.lowercase().replace('_', ' '))
        message?.takeIf { it.isNotBlank() }?.let {
            append(": ")
            append(it)
        }
    }

private fun queuePlacementDescription(placement: QueuedMessagePlacement): String =
    when (placement) {
        QueuedMessagePlacement.AFTER_TOOL_RESULT -> "Будет передано в текущий ход после ближайшего результата инструмента"
        QueuedMessagePlacement.END_OF_TURN -> "В очереди: отправится после текущего ответа"
    }

private fun pendingMessageOrder(placement: QueuedMessagePlacement): Int =
    when (placement) {
        QueuedMessagePlacement.AFTER_TOOL_RESULT -> 0
        QueuedMessagePlacement.END_OF_TURN -> 1
    }

private fun List<PendingUserMessage>.orderedForDisplay(): List<PendingUserMessage> =
    withIndex()
        .sortedWith(compareBy({ pendingMessageOrder(it.value.placement) }, { it.index }))
        .map { it.value }

@Composable
private fun PendingMessagesPanel(
    isWaitingForResponse: Boolean,
    pendingMessages: List<PendingUserMessage>,
    onSendInCurrentTurn: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    if (pendingMessages.isEmpty()) {
        return
    }

    val orderedMessages = pendingMessages.orderedForDisplay()
    val steeringMessages = orderedMessages.filter { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
    val queuedMessages = orderedMessages.filter { it.placement == QueuedMessagePlacement.END_OF_TURN }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag(UiTestTag.PendingMessagesPanel.value),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Очередь сообщений: ${pendingMessages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            PendingMessageGroup(
                title = "Уточнение текущего хода",
                messages = steeringMessages,
                firstIndex = 1,
                isWaitingForResponse = isWaitingForResponse,
                onSendInCurrentTurn = onSendInCurrentTurn,
                onEdit = onEdit,
                onCancel = onCancel,
            )
            PendingMessageGroup(
                title = "После ответа",
                messages = queuedMessages,
                firstIndex = steeringMessages.size + 1,
                isWaitingForResponse = isWaitingForResponse,
                onSendInCurrentTurn = onSendInCurrentTurn,
                onEdit = onEdit,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun PendingMessageGroup(
    title: String,
    messages: List<PendingUserMessage>,
    firstIndex: Int,
    isWaitingForResponse: Boolean,
    onSendInCurrentTurn: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    if (messages.isEmpty()) {
        return
    }

    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
    )
    messages.forEachIndexed { index, message ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${firstIndex + index}. ${message.text}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = queuePlacementDescription(message.placement),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isWaitingForResponse && message.placement == QueuedMessagePlacement.END_OF_TURN) {
                TextButton(onClick = { onSendInCurrentTurn(message.id) }) {
                    Text("В текущий ход")
                }
            }
            TextButton(onClick = { onEdit(message.id) }) {
                Text("Править")
            }
            TextButton(onClick = { onCancel(message.id) }) {
                Text("Убрать")
            }
        }
    }
}
