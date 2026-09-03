package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.KeyboardShortcutScope
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.presentation.services.LiveVoiceInputService
import com.gromozeka.presentation.services.LiveVoiceInputState
import com.gromozeka.presentation.services.NoOpLiveVoiceInputService
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.ToggleButtonGroup
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.format
import com.gromozeka.presentation.ui.viewmodel.MessageSquashUiState
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
import com.gromozeka.presentation.ui.viewmodel.editableText
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
    coroutineScope: CoroutineScope,
    pttEventHandler: PttEventHandler,
    pttState: PttState = PttState.IDLE,
    pttStatusMessage: String? = null,
    pttUnavailableReason: String? = null,
    liveVoiceInputService: LiveVoiceInputService = NoOpLiveVoiceInputService(),
    liveVoiceInputState: LiveVoiceInputState = LiveVoiceInputState.IDLE,
    liveVoiceInputStatusMessage: String? = null,
    liveVoiceInputUnavailableReason: String? = null,

    // Settings - moved to ChatApplication level, but we still need settings for UI
    settings: Settings,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    showMemoryActionItemsPanel: Boolean,
    onShowMemoryActionItemsPanelChange: (Boolean) -> Unit,
    showRuntimePanel: Boolean,
    onShowRuntimePanelChange: (Boolean) -> Unit,

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
    // All data comes from ViewModel
    val filteredHistory by viewModel.filteredMessages.collectAsState()
    val allMessages by viewModel.allMessages.collectAsState()
    val toolResultsMap by viewModel.toolResultsMap.collectAsState()
    val isWaitingForResponse by viewModel.isWaitingForResponse.collectAsState()
    val pendingMessagesCount by viewModel.pendingMessagesCount.collectAsState()
    val messageSquashState by viewModel.messageSquashState.collectAsState()
    val suggestedRepliesOverride by viewModel.suggestedRepliesOverride.collectAsState()
    val suggestedRepliesRegeneratingFor by viewModel.suggestedRepliesRegeneratingFor.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val messageFocusRequest by viewModel.messageFocusRequest.collectAsState()
    val userInput = uiState.userInput
    val suggestedReplies = when {
        isWaitingForResponse -> null
        settings.userProfile.suggestedRepliesSettings.mode ==
            UserProfile.SuggestedRepliesSettings.Mode.DISABLED -> null
        else -> latestSuggestedReplies(filteredHistory)?.let { persisted ->
            suggestedRepliesOverride
                ?.takeIf { it.sourceMessageId == persisted.sourceMessageId }
                ?.let { SuggestedReplyOptions(it.sourceMessageId, it.values) }
                ?: persisted
        }
    }
    val jsonToShow = viewModel.jsonToShow
    val topToolbarScrollState = rememberScrollState()
    val editToolbarScrollState = rememberScrollState()
    var showMemoryMenu by remember { mutableStateOf(false) }
    val messageEntries = rememberMessageListEntries(
        messages = filteredHistory,
        collapsedContentItems = uiState.collapsedContentItems,
        toolResultsMap = toolResultsMap,
    )
    val runtimeStrings = LocalTranslation.current.runtime
    val editLastMessageShortcut = remember(settings.userDeviceSettings) {
        (settings.userDeviceSettings as? UserDeviceSettings.Desktop)
            ?.inputSettings
            ?.keyboardShortcuts
            ?.binding(KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE)
            ?.takeIf { it.enabled && it.scope == KeyboardShortcutScope.FOCUSED }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTestTag.SessionScreen.value)
    ) {
        // Main chat content
        Box(modifier = Modifier.weight(1f)) {
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
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null)
                                    },
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

                        val selectedIndices = remember(allMessages, filteredHistory, uiState.selectedMessageIds) {
                            buildSet {
                                val allMessageIds = allMessages.mapTo(mutableSetOf()) { it.id }
                                if (allMessageIds.isNotEmpty() && allMessageIds.all { it in uiState.selectedMessageIds }) {
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
                                    0 -> viewModel.toggleSelectAll(allMessages.map { it.id }.toSet())
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
                        val messageSquashRunning = messageSquashState is MessageSquashUiState.Running
                        val selectedMessage = remember(allMessages, uiState.selectedMessageIds) {
                            uiState.selectedMessageIds.singleOrNull()?.let { selectedMessageId ->
                                allMessages.firstOrNull { it.id == selectedMessageId }
                            }
                        }

                        CompactButton(
                            onClick = {
                                selectedMessage?.let { viewModel.startEditMessage(it.id) }
                            },
                            modifier = Modifier.testTag(UiTestTag.EditSelectedMessageButton.value),
                            enabled = selectedMessage?.editableText() != null && !messageSquashRunning,
                            tooltip = "Edit selected message"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                                Spacer(modifier = Modifier.width(4.dp))
                                if (!isCompactLayout) Text("Edit")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Concat - disabled when 0 or 1 message selected
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.squashSelectedMessages()
                                }
                            },
                            enabled = uiState.selectedMessageIds.size >= 2 && !messageSquashRunning,
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
                            enabled = uiState.selectedMessageIds.size >= 2 && !messageSquashRunning,
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
                            enabled = uiState.selectedMessageIds.size >= 2 && !messageSquashRunning,
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
                            enabled = uiState.selectedMessageIds.isNotEmpty() && !messageSquashRunning,
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

                        MessageSquashStatus(messageSquashState)
                        // Selected count (right side)
                        Text("Selected: ${uiState.selectedMessageIds.size}")

                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                key(viewModel.conversationId) {
                    FollowLatestLazyColumn(
                        items = messageEntries,
                        itemKey = MessageListEntry::key,
                        unreadKey = { it.message.id },
                        contentRevision = messageEntries,
                        unreadLabel = { count ->
                            if (count > 0) {
                                runtimeStrings.newMessagesLabel
                            } else {
                                runtimeStrings.newActivityLabel
                            }
                        },
                        focusKey = messageFocusRequest?.value,
                        focusItemKey = { it.message.id.value },
                        onFocusConsumed = {
                            messageFocusRequest?.let(viewModel::consumeMessageFocus)
                        },
                        modifier = Modifier.weight(1f),
                    ) { entry ->
                        MessageItem(
                            entry = entry,
                            toolResultsMap = toolResultsMap,
                            workspaceRootPath = null,
                            isSelected = entry.message.id in uiState.selectedMessageIds,
                            onToggleSelection = { messageId, isShiftPressed ->
                                viewModel.toggleMessageSelectionRange(messageId, isShiftPressed)
                            },
                            onToggleContentItemCollapse = { messageId, contentItemIndex ->
                                viewModel.toggleContentItemCollapse(messageId, contentItemIndex)
                            },
                            loadArtifactContent = viewModel::loadArtifactContent,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                DisableSelection {
                    // The ViewModel claims the composer draft atomically before asynchronous submission.
                    MessageInput(
                        userInput = userInput,
                        onUserInputChange = { viewModel.updateUserInput(it) },
                        isWaitingForResponse = isWaitingForResponse,
                        pendingMessagesCount = pendingMessagesCount,
                        suggestedReplies = suggestedReplies,
                        suggestedRepliesRegenerating = suggestedRepliesRegeneratingFor ==
                            suggestedReplies?.sourceMessageId,
                        onRegenerateSuggestedReplies = viewModel::regenerateSuggestedReplies,
                        onSendMessage = viewModel::submitUserInputToSession,
                        coroutineScope = coroutineScope,
                        pttEventHandler = pttEventHandler,
                        pttState = pttState,
                        pttStatusMessage = pttStatusMessage,
                        pttUnavailableReason = pttUnavailableReason,
                        liveVoiceInputService = liveVoiceInputService,
                        liveVoiceInputState = liveVoiceInputState,
                        liveVoiceInputStatusMessage = liveVoiceInputStatusMessage,
                        liveVoiceInputUnavailableReason = liveVoiceInputUnavailableReason,
                        showLiveVoiceButton = settings.userDeviceSettings.voiceInputSettings.liveVoiceInputEnabled,
                        showPttButton = settings.userProfile.speechSettings.speechToText.enabled,
                        compactVoiceMode = isCompactLayout,
                        clientPlatform = clientPlatform,
                        instructionGroups = viewModel.messageInstructionGroups,
                        activeInstructionIds = uiState.activeMessageInstructionIds,
                        onSelectInstruction = viewModel::selectMessageInstruction,
                        composerArtifacts = uiState.composerArtifacts,
                        artifactUploadInProgress = uiState.composerArtifactUploadInProgress,
                        artifactError = uiState.composerArtifactError,
                        canPickAttachments = viewModel.attachmentCapabilities.filePicker,
                        canCaptureScreenshot = viewModel.attachmentCapabilities.screenshot,
                        onPickAttachments = viewModel::pickAttachments,
                        onCaptureScreenshot = viewModel::captureScreenshot,
                        onRemoveArtifact = viewModel::removeComposerArtifact,
                        onInsertCurrentLocation = onInsertCurrentLocation,
                        editLastMessageShortcut = editLastMessageShortcut,
                        onEditLastUserMessage = viewModel::startEditLatestUserMessage,
                    )

                    // Dev buttons only
                    if (isDev) {
                        Spacer(modifier = Modifier.height(8.dp))
                        DevButtons(
                            onSendMessage = { message -> viewModel.sendMessageToSession(message) },
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
private fun MessageSquashStatus(state: MessageSquashUiState) {
    val text = when (state) {
        MessageSquashUiState.Idle -> return
        is MessageSquashUiState.Running -> "${state.squashType.actionTitle()} running"
        is MessageSquashUiState.Succeeded -> "${state.squashType.actionTitle()} complete"
        is MessageSquashUiState.Failed -> "${state.squashType.actionTitle()} failed: ${state.message}"
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag(UiTestTag.MessageSquashStatus.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state is MessageSquashUiState.Running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (state is MessageSquashUiState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun com.gromozeka.domain.model.SquashType.actionTitle(): String = when (this) {
    com.gromozeka.domain.model.SquashType.CONCATENATE -> "Concat"
    com.gromozeka.domain.model.SquashType.DISTILL -> "Distill"
    com.gromozeka.domain.model.SquashType.SUMMARIZE -> "Summarize"
}
