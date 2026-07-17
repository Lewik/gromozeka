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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.TtsTask
import com.gromozeka.domain.model.WorkspaceContextReference
import com.gromozeka.domain.service.WorkspaceFileSystemService
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.TtsQueue
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.ToggleButtonGroup
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.WorkspacePathPickerDialog
import com.gromozeka.presentation.ui.format
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
    pttState: PttState = PttState.IDLE,
    workspaceFileSystemService: WorkspaceFileSystemService,

    // Settings - moved to ChatApplication level, but we still need settings for UI
    settings: Settings,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    showMemoryActionItemsPanel: Boolean,
    onShowMemoryActionItemsPanelChange: (Boolean) -> Unit,
    showRuntimePanel: Boolean,
    onShowRuntimePanelChange: (Boolean) -> Unit,

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
    val pendingMessagesCount by viewModel.pendingMessagesCount.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val userInput = uiState.userInput
    val jsonToShow = viewModel.jsonToShow
    val topToolbarScrollState = rememberScrollState()
    val editToolbarScrollState = rememberScrollState()
    var showMemoryMenu by remember { mutableStateOf(false) }
    var showWorkspaceContextPicker by remember { mutableStateOf(false) }

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
                            tooltip = "Select agent",
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = "Select agent")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        CompactButton(
                            onClick = { onShowRuntimePanelChange(!showRuntimePanel) },
                            modifier = Modifier.testTag(UiTestTag.RuntimeButton.value),
                            tooltip = if (showRuntimePanel) "Hide runtime" else "Show runtime",
                        ) {
                            Icon(Icons.Default.HourglassTop, contentDescription = "Runtime")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        CompactButton(
                            onClick = {},
                            tooltip = LocalTranslation.current.messageCountTooltip.format(filteredHistory.size),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Messages")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${filteredHistory.size}")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box {
                            CompactButton(
                                onClick = { showMemoryMenu = !showMemoryMenu },
                                modifier = Modifier.testTag(UiTestTag.MemoryMenuButton.value),
                                tooltip = "Memory actions",
                            ) {
                                Icon(Icons.Default.Inventory2, contentDescription = "Memory actions")
                            }

                            DropdownMenu(
                                expanded = showMemoryMenu,
                                onDismissRequest = { showMemoryMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Action items") },
                                    leadingIcon = { Icon(Icons.Default.ListAlt, contentDescription = null) },
                                    onClick = {
                                        showMemoryMenu = false
                                        onShowMemoryActionItemsPanelChange(!showMemoryActionItemsPanel)
                                    },
                                    modifier = Modifier.testTag(UiTestTag.MemoryActionItemsButton.value),
                                )
                                onRememberThread?.let { rememberCallback ->
                                    DropdownMenuItem(
                                        text = { Text("Remember conversation") },
                                        leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                                        onClick = {
                                            showMemoryMenu = false
                                            rememberCallback()
                                        },
                                    )
                                }
                                onConsolidateMemory?.let { consolidateCallback ->
                                    DropdownMenuItem(
                                        text = { Text("Consolidate") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                                        onClick = {
                                            showMemoryMenu = false
                                            consolidateCallback()
                                        },
                                    )
                                }
                                onRepairMemory?.let { repairCallback ->
                                    DropdownMenuItem(
                                        text = { Text("Repair") },
                                        leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                                        onClick = {
                                            showMemoryMenu = false
                                            repairCallback()
                                        },
                                    )
                                }
                                onMaintainMemoryEntities?.let { maintainEntitiesCallback ->
                                    DropdownMenuItem(
                                        text = { Text("Entity maintenance") },
                                        leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null) },
                                        onClick = {
                                            showMemoryMenu = false
                                            maintainEntitiesCallback()
                                        },
                                    )
                                }
                                onApplyMemoryRetention?.let { retentionCallback ->
                                    DropdownMenuItem(
                                        text = { Text("Apply retention") },
                                        leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                                        onClick = {
                                            showMemoryMenu = false
                                            retentionCallback()
                                        },
                                    )
                                }
                            }
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
                        pttState = pttState,
                        showPttButton = settings.userProfile.speechSettings.speechToText.enabled,
                        clientPlatform = clientPlatform,
                        instructionGroups = viewModel.messageInstructionGroups,
                        activeInstructionIds = uiState.activeMessageInstructionIds,
                        onSelectInstruction = viewModel::selectMessageInstruction,
                        workspaceContextReferences = uiState.workspaceContextReferences,
                        onAddWorkspaceContext = { showWorkspaceContextPicker = true },
                        onRemoveWorkspaceContext = viewModel::removeWorkspaceContextReference,
                        onCaptureScreenshot = viewModel::captureAndAddToInput,
                        onInsertCurrentLocation = onInsertCurrentLocation,
                    )

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

        if (showWorkspaceContextPicker) {
            WorkspacePathPickerDialog(
                title = "Add workspace context",
                fileSystemService = workspaceFileSystemService,
                initialPath = viewModel.projectPath,
                showFiles = true,
                selectLabel = "Add this folder",
                allowDirectoryEntrySelection = true,
                onSelect = { path ->
                    viewModel.addWorkspaceContextReference(
                        WorkspaceContextReference(
                            path = path.path,
                            name = path.name,
                            kind = path.kind,
                        )
                    )
                    showWorkspaceContextPicker = false
                },
                onDismiss = { showWorkspaceContextPicker = false },
            )
        }
    }
}
