package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.services.translation.data.Translation
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutScope
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.presentation.services.LiveVoiceInputService
import com.gromozeka.presentation.services.LiveVoiceInputState
import com.gromozeka.presentation.services.NoOpLiveVoiceInputService
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.VoiceInputTarget
import com.gromozeka.presentation.services.VoiceTranscriptionActivity
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.DockedPanel
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.CompactButtonDefaults
import com.gromozeka.presentation.ui.CompactIconButton
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
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator

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
    pttTarget: VoiceInputTarget? = null,
    liveVoiceTarget: VoiceInputTarget? = null,
    voiceTranscriptions: List<VoiceTranscriptionActivity> = emptyList(),
    remoteConnectionState: RemoteConnectionState = RemoteConnectionState(RemoteConnectionState.Status.CONNECTED),

    // Settings - moved to ChatApplication level, but we still need settings for UI
    settings: Settings,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    showMemoryActionItemsPanel: Boolean,
    onShowMemoryActionItemsPanelChange: (Boolean) -> Unit,
    showParticipantsPanel: Boolean,
    onShowParticipantsPanelChange: (Boolean) -> Unit,
    showRuntimePanel: Boolean,
    onShowRuntimePanelChange: (Boolean) -> Unit,
    onInspectRuntime: (RuntimeInspectionSection) -> Unit = { onShowRuntimePanelChange(true) },

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
    contentPadding: Dp = 16.dp,
    slotService: com.gromozeka.domain.slot.SlotService? = null,
    onOpenSlotConversation: (com.gromozeka.domain.model.Conversation.Id) -> Unit = {},
) {
    val localization = LocalTranslation.current
    val spacing = GromozekaTheme.spacing
    val controls = GromozekaTheme.controls
    // All data comes from ViewModel
    // Capture one immutable emission: paging callbacks must not re-read a newer State.value.
    val historyContent = viewModel.historyContent.collectAsState().value
    val filteredHistory = historyContent.messages
    val allMessages by viewModel.allMessages.collectAsState()
    val olderHistory by viewModel.olderHistory.collectAsState()
    val newerHistory by viewModel.newerHistory.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()
    val historyActivityRevision by viewModel.historyActivityRevision.collectAsState()
    val historyError by viewModel.historyError.collectAsState()
    val deferredHistory by viewModel.deferredHistoryMessages.collectAsState()
    val selectedHistoryKinds by viewModel.selectedHistoryKinds.collectAsState()
    val displayedHistoryCount = filteredHistory.size.toString() + if (olderHistory != null || newerHistory != null) "+" else ""
    val externalChannel by viewModel.externalChannel.collectAsState()
    val toolResultsMap by viewModel.toolResultsMap.collectAsState()
    val isWaitingForResponse by viewModel.isWaitingForResponse.collectAsState()
    val pendingMessagesCount by viewModel.pendingMessagesCount.collectAsState()
    val agentMentionCandidates by viewModel.agentMentionCandidates.collectAsState()
    val messageSubmissionError by viewModel.messageSubmissionError.collectAsState()
    val messageSquashState by viewModel.messageSquashState.collectAsState()
    val suggestedRepliesOverride by viewModel.suggestedRepliesOverride.collectAsState()
    val suggestedRepliesRegeneratingFor by viewModel.suggestedRepliesRegeneratingFor.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val runtimeSnapshot by viewModel.runtimeSnapshot.collectAsState()
    val activeGeneration by viewModel.activeGeneration.collectAsState()
    val tokenStats by viewModel.tokenStats.collectAsState()
    val executionPauseRequested by viewModel.executionPauseRequested.collectAsState()
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
        expandedActivityKeys = uiState.expandedActivityKeys,
    )
    val runtimeStrings = LocalTranslation.current.runtime
    val editLastMessageShortcut = remember(settings.userProfile.keyboardShortcuts) {
        settings.userProfile.keyboardShortcuts
            .binding(KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE)
            .takeIf { it.enabled && it.scope == KeyboardShortcutScope.FOCUSED }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTestTag.SessionScreen.value)
    ) {
        // Main chat content
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val compactToolbar = isCompactLayout || maxWidth < 1000.dp
            Column(modifier = Modifier.fillMaxSize()) {
                DockedPanel(
                    dividerAtTop = false,
                    modifier = Modifier.testTag("conversation-toolbar-panel"),
                ) {
                    val toolbarColors = CompactButtonDefaults.tonalColors()
                    DisableSelection {
                        if (externalChannel != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(localization.text("telegram.inputPolicy"), modifier = Modifier.weight(1f).padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CompactIconButton(
                                    onClick = { onShowRuntimePanelChange(!showRuntimePanel) },
                                    tooltip = localization.text("session.toolbar.showRuntime"),
                                    icon = Icons.Default.RuntimePanel,
                                    contentDescription = localization.text("runtime.title"),
                                )
                            }
                        } else {
                            // Row 1: Navigation buttons (New, Fork, Restart) + Info buttons (Message count, Token stats, etc.)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth().testTag("conversation-navigation-toolbar")
                                    .then(if (compactToolbar) Modifier.horizontalScroll(topToolbarScrollState) else Modifier),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Navigation buttons (left side)
                                CompactButton(
                                    onClick = onNewSession,
                                    colors = toolbarColors,
                                ) {
                                    Text(LocalTranslation.current.newSessionShort)
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                CompactButton(
                                    onClick = onForkSession,
                                    colors = toolbarColors,
                                ) {
                                    Text(LocalTranslation.current.forkButton)
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                CompactButton(
                                    onClick = onRestartSession,
                                    colors = toolbarColors,
                                ) {
                                    Text(LocalTranslation.current.restartButton)
                                }

                                slotService?.let { service ->
                                    Spacer(modifier = Modifier.width(spacing.controlGap))
                                    SlotHeaderButton(service, viewModel.conversationId, onOpenSlotConversation)
                                }

                                if (compactToolbar) {
                                    Spacer(modifier = Modifier.width(spacing.rowGap))
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                                // Context extraction button
                                onExtractContexts?.let { extractCallback ->
                                    CompactIconButton(
                                        onClick = extractCallback,
                                        tooltip = localization.text("session.toolbar.extractContexts"),
                                        icon = Icons.Default.FolderOpen,
                                        contentDescription = localization.text("session.toolbar.extractContextsShort"),
                                    )

                                    Spacer(modifier = Modifier.width(spacing.controlGap))
                                }

                                // Context panel button
                                onShowContextsPanel?.let { showContextsCallback ->
                                    CompactIconButton(
                                        onClick = showContextsCallback,
                                        tooltip = localization.text("session.toolbar.viewContexts"),
                                        icon = Icons.Default.Book,
                                        contentDescription = localization.text("session.toolbar.viewContextsShort"),
                                    )

                                    Spacer(modifier = Modifier.width(spacing.controlGap))
                                }

                                CompactIconButton(
                                    onClick = { onShowParticipantsPanelChange(!showParticipantsPanel) },
                                    modifier = Modifier.testTag(UiTestTag.ParticipantsButton.value).semantics { selected = showParticipantsPanel },
                                    tooltip = if (showParticipantsPanel) localization.text("session.toolbar.hideParticipants") else localization.text("session.toolbar.showParticipants"),
                                    icon = Icons.Default.Person,
                                    contentDescription = localization.text("session.participants.title"),
                                    colors = CompactButtonDefaults.tonalColors(showParticipantsPanel),
                                )

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                CompactIconButton(
                                    onClick = { onShowRuntimePanelChange(!showRuntimePanel) },
                                    modifier = Modifier.testTag(UiTestTag.RuntimeButton.value).semantics { selected = showRuntimePanel },
                                    tooltip = if (showRuntimePanel) localization.text("session.toolbar.hideRuntime") else localization.text("session.toolbar.showRuntime"),
                                    icon = Icons.Default.RuntimePanel,
                                    contentDescription = localization.text("runtime.title"),
                                    colors = CompactButtonDefaults.tonalColors(showRuntimePanel),
                                )

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                CompactButton(
                                    onClick = {},
                                    tooltip = LocalTranslation.current.format("messageCountTooltip", filteredHistory.size),
                                    colors = toolbarColors,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ChatBubbleOutline, contentDescription = localization.text("session.toolbar.messages"))
                                        Spacer(modifier = Modifier.width(spacing.controlGap))
                                        Text(displayedHistoryCount)
                                    }
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                Box {
                                    CompactIconButton(
                                        onClick = { showMemoryMenu = !showMemoryMenu },
                                        modifier = Modifier.testTag(UiTestTag.MemoryMenuButton.value),
                                        tooltip = localization.text("session.toolbar.memoryActions"),
                                        icon = Icons.Default.Inventory2,
                                        contentDescription = localization.text("session.toolbar.memoryActions"),
                                    )

                                    DropdownMenu(
                                        expanded = showMemoryMenu,
                                        onDismissRequest = { showMemoryMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(localization.text("session.toolbar.actionItems")) },
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
                                                text = { Text(localization.text("session.toolbar.remember")) },
                                                leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                                                onClick = {
                                                    showMemoryMenu = false
                                                    rememberCallback()
                                                },
                                            )
                                        }
                                        onConsolidateMemory?.let { consolidateCallback ->
                                            DropdownMenuItem(
                                                text = { Text(localization.text("session.toolbar.consolidate")) },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                                                onClick = {
                                                    showMemoryMenu = false
                                                    consolidateCallback()
                                                },
                                            )
                                        }
                                        onRepairMemory?.let { repairCallback ->
                                            DropdownMenuItem(
                                                text = { Text(localization.text("session.toolbar.repair")) },
                                                leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                                                onClick = {
                                                    showMemoryMenu = false
                                                    repairCallback()
                                                },
                                            )
                                        }
                                        onMaintainMemoryEntities?.let { maintainEntitiesCallback ->
                                            DropdownMenuItem(
                                                text = { Text(localization.text("session.toolbar.maintainEntities")) },
                                                leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null) },
                                                onClick = {
                                                    showMemoryMenu = false
                                                    maintainEntitiesCallback()
                                                },
                                            )
                                        }
                                        onApplyMemoryRetention?.let { retentionCallback ->
                                            DropdownMenuItem(
                                                text = { Text(localization.text("session.toolbar.retention")) },
                                                leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                                                onClick = {
                                                    showMemoryMenu = false
                                                    retentionCallback()
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                // Settings button
                                CompactIconButton(
                                    onClick = { onShowSettingsPanelChange(!showSettingsPanel) },
                                    modifier = Modifier.testTag(UiTestTag.SettingsButton.value).semantics { selected = showSettingsPanel },
                                    tooltip = LocalTranslation.current.settingsTooltip,
                                    icon = Icons.Default.Settings,
                                    contentDescription = LocalTranslation.current.settingsTooltip,
                                    colors = CompactButtonDefaults.tonalColors(showSettingsPanel),
                                )

                                // Close tab button (if onCloseTab callback is provided)
                                onCloseTab?.let { closeCallback ->
                                    Spacer(modifier = Modifier.width(spacing.controlGap))
                                    CompactIconButton(
                                        onClick = closeCallback,
                                        tooltip = LocalTranslation.current.closeTabTooltip,
                                        icon = Icons.Default.Close,
                                        contentDescription = LocalTranslation.current.closeTabTooltip,
                                    )
                                }
                            }

                            // Row 2: Message editing tools (selection buttons + action buttons)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth().testTag("conversation-selection-toolbar")
                                    .then(if (compactToolbar) Modifier.horizontalScroll(editToolbarScrollState) else Modifier),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Selection buttons
                                val selectionOptions = remember(localization) {
                                    listOf(
                                        com.gromozeka.presentation.ui.ToggleButtonOption(
                                            Icons.Default.SelectAll,
                                            localization.text("chat.selection.toggleAll")
                                        ),
                                        com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Person, localization.text("chat.selection.userMessages")),
                                        com.gromozeka.presentation.ui.ToggleButtonOption(
                                            Icons.Default.DeveloperBoard,
                                            localization.text("chat.selection.assistantMessages")
                                        ),
                                        com.gromozeka.presentation.ui.ToggleButtonOption(
                                            Icons.Default.Psychology,
                                            localization.text("chat.selection.thinkingBlocks")
                                        ),
                                        com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Build, localization.text("chat.selection.toolCalls")),
                                        com.gromozeka.presentation.ui.ToggleButtonOption(
                                            Icons.Default.ChatBubbleOutline,
                                            localization.text("chat.selection.plainMessages")
                                        ),
                                    )
                                }

                                val selectedIndices = selectedHistoryKinds.map { it.ordinal }.toSet()

                                ToggleButtonGroup(
                                    options = selectionOptions,
                                    selectedIndices = selectedIndices,
                                    onToggle = { index ->
                                        viewModel.toggleHistorySelection(ConversationMessageSelection.entries[index])
                                    }
                                )

                                Spacer(modifier = Modifier.width(spacing.rowGap))

                                // Action buttons
                                val messageSquashRunning = messageSquashState is MessageSquashUiState.Running
                                val protectedMessageIds = remember(allMessages) {
                                    ConversationContext(allMessages).protectedMessageIds()
                                }
                                val selectedMessage = remember(allMessages, uiState.selectedMessageIds) {
                                    uiState.selectedMessageIds.singleOrNull()?.let { selectedMessageId ->
                                        allMessages.firstOrNull { it.id == selectedMessageId }
                                    }
                                }

                                CompactButton(
                                    onClick = {
                                        selectedMessage?.let { viewModel.startEditMessage(it.id) }
                                    },
                                    modifier = Modifier.testTag(UiTestTag.EditSelectedMessageButton.value).then(if (compactToolbar) Modifier.size(controls.minHeight) else Modifier),
                                    enabled = selectedMessage?.editableText() != null && selectedMessage.id !in protectedMessageIds && !messageSquashRunning,
                                    tooltip = localization.text("chat.selection.editHint"),
                                    colors = toolbarColors,
                                    contentPadding = if (compactToolbar) PaddingValues(0.dp) else CompactButtonDefaults.ContentPadding,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Edit, contentDescription = localization.text("runtime.editButton"))
                                        if (!compactToolbar) {
                                            Spacer(Modifier.width(spacing.controlGap))
                                            Text(localization.text("runtime.editButton"))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                // Concat - disabled when 0 or 1 message selected
                                CompactButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel.squashSelectedMessages()
                                        }
                                    },
                                    enabled = uiState.selectedMessageIds.size >= 2 && !messageSquashRunning,
                                    tooltip = localization.text("chat.selection.concatHint"),
                                    colors = toolbarColors,
                                    modifier = if (compactToolbar) Modifier.size(controls.minHeight) else Modifier,
                                    contentPadding = if (compactToolbar) PaddingValues(0.dp) else CompactButtonDefaults.ContentPadding,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = localization.text("chat.selection.concat"))
                                        if (!compactToolbar) {
                                            Spacer(Modifier.width(spacing.controlGap))
                                            Text(localization.text("chat.selection.concat"))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                // Distill - disabled when 0 messages selected
                                CompactButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel.distillSelectedMessages()
                                        }
                                    },
                                    enabled = uiState.selectedMessageIds.isNotEmpty() && !messageSquashRunning,
                                    tooltip = localization.text("chat.selection.distillHint"),
                                    colors = toolbarColors,
                                    modifier = if (compactToolbar) Modifier.size(controls.minHeight) else Modifier,
                                    contentPadding = if (compactToolbar) PaddingValues(0.dp) else CompactButtonDefaults.ContentPadding,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Compress, contentDescription = localization.text("chat.selection.distill"))
                                        if (!compactToolbar) {
                                            Spacer(Modifier.width(spacing.controlGap))
                                            Text(localization.text("chat.selection.distill"))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                // Summarize - disabled when 0 messages selected
                                CompactButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel.summarizeSelectedMessages()
                                        }
                                    },
                                    enabled = uiState.selectedMessageIds.isNotEmpty() && !messageSquashRunning,
                                    tooltip = localization.text("chat.selection.summarizeHint"),
                                    colors = toolbarColors,
                                    modifier = if (compactToolbar) Modifier.size(controls.minHeight) else Modifier,
                                    contentPadding = if (compactToolbar) PaddingValues(0.dp) else CompactButtonDefaults.ContentPadding,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = localization.text("chat.selection.summarize"))
                                        if (!compactToolbar) {
                                            Spacer(Modifier.width(spacing.controlGap))
                                            Text(localization.text("chat.selection.summarize"))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(spacing.controlGap))

                                // Delete - disabled when 0 messages selected
                                CompactButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel.deleteSelectedMessages()
                                        }
                                    },
                                    enabled = uiState.selectedMessageIds.isNotEmpty() && uiState.selectedMessageIds.none { it in protectedMessageIds } && !messageSquashRunning,
                                    tooltip = localization.text("chat.selection.deleteHint"),
                                    colors = toolbarColors,
                                    modifier = if (compactToolbar) Modifier.size(controls.minHeight) else Modifier,
                                    contentPadding = if (compactToolbar) PaddingValues(0.dp) else CompactButtonDefaults.ContentPadding,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Delete, contentDescription = localization.text("chat.selection.delete"))
                                        if (!compactToolbar) {
                                            Spacer(Modifier.width(spacing.controlGap))
                                            Text(localization.text("chat.selection.delete"))
                                        }
                                    }
                                }

                                if (compactToolbar) {
                                    Spacer(modifier = Modifier.width(spacing.rowGap))
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }

                                MessageSquashStatus(messageSquashState)
                                // Selected count (right side)
                                Text(localization.text("chat.selection.selectedCount", "count" to uiState.selectedMessageIds.size))

                            }
                        }
                    }

                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = contentPadding),
                ) {

                    Spacer(modifier = Modifier.height(spacing.panelPadding))

                    if (historyLoading) {
                        Box(Modifier.fillMaxWidth().testTag("history-loading"), contentAlignment = Alignment.Center) {
                            GromozekaLoadingIndicator(Modifier.size(24.dp))
                        }
                    }
                    if (historyError != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(historyError.orEmpty(), modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryHistory) { Text(localization.text("chat.error.retry")) }
                        }
                    }
                    if (olderHistory != null) {
                        TextButton(onClick = { viewModel.loadOlderHistory() }, enabled = !historyLoading) {
                            Text(localization.text("chat.history.loadOlder"))
                        }
                    }
                    key(viewModel.conversationId) {
                        FollowLatestLazyColumn(
                            items = messageEntries,
                            hasOlderItems = olderHistory != null,
                            hasNewerItems = newerHistory != null,
                            isLoadingHistory = historyLoading,
                            paginationKey = historyContent.firstLoadedMessageId to historyContent.lastLoadedMessageId,
                            onLoadOlder = { historyContent.firstLoadedMessageId?.let { viewModel.loadOlderHistory(it) } },
                            onLoadNewer = { historyContent.lastLoadedMessageId?.let { viewModel.loadNewerHistory(it) } },
                            onLoadLatest = viewModel::loadLatestHistory,
                            onVisibleItemChanged = { viewModel.rememberHistoryAnchor(it?.message?.id) },
                            itemKey = MessageListEntry::key,
                            unreadKey = { it.message.id },
                            contentRevision = messageEntries,
                            unreadRevision = historyActivityRevision,
                            unreadLabel = { count ->
                                if (count > 0) {
                                    localization.plural("chat.history.unreadMessages", count.toLong())
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
                        ) { entry, pauseFollowingLatest ->
                            if ((entry.isFirstInMessage || entry.segment is MessageSegment.Activity) && viewModel.hasDeferredHistoryContent(entry.message, deferredHistory)) {
                                TextButton(
                                    onClick = { pauseFollowingLatest(); viewModel.loadHistoryDetails(entry.message.id) },
                                    modifier = Modifier.testTag("history-details:${entry.message.id.value}"),
                                ) { Text(localization.text("chat.history.loadFull")) }
                            }
                            MessageItem(
                                entry = entry,
                                workspaceRootPath = null,
                                isSelected = entry.message.id in uiState.selectedMessageIds,
                                onToggleSelection = { messageId, isShiftPressed ->
                                    viewModel.toggleMessageSelectionRange(messageId, isShiftPressed)
                                },
                                onToggleContentItemCollapse = { messageId, contentItemIndex ->
                                    viewModel.toggleContentItemCollapse(messageId, contentItemIndex)
                                },
                                onManualContentResize = pauseFollowingLatest,
                                expandedActivityKeys = uiState.expandedActivityKeys,
                                onToggleActivityExpansion = viewModel::toggleActivityExpansion,
                                loadArtifactContent = viewModel::loadArtifactContent,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(spacing.panelPadding))

                    // Dev buttons only
                    if (isDev && externalChannel == null) {
                        Spacer(modifier = Modifier.height(spacing.panelPadding))
                        DevButtons(
                            onSendMessage = { message -> viewModel.sendMessageToSession(message) },
                            coroutineScope = coroutineScope,
                        )
                    }
                }
                val activityContent: @Composable () -> Unit = {
                    ConversationActivityBar(
                        runtime = runtimeSnapshot?.takeIf { it.conversationId == viewModel.conversationId },
                        generation = activeGeneration?.takeIf { it.conversationId == viewModel.conversationId },
                        isWaiting = isWaitingForResponse,
                        pauseRequested = executionPauseRequested,
                        tokenStats = tokenStats,
                        voice = voiceTranscriptions,
                        tabId = uiState.tabId,
                        uploadingArtifacts = uiState.composerArtifactUploadInProgress,
                        regeneratingSuggestions = suggestedRepliesRegeneratingFor != null,
                        voiceError = listOfNotNull(
                            pttStatusMessage?.takeIf { pttState == PttState.IDLE && pttTarget?.tabId?.value == uiState.tabId },
                            liveVoiceInputStatusMessage?.takeIf { liveVoiceInputState == LiveVoiceInputState.IDLE && liveVoiceTarget?.tabId?.value == uiState.tabId },
                        ).firstOrNull(),
                        connection = remoteConnectionState,
                        onPause = viewModel::pauseExecution,
                        onResume = viewModel::resumeExecution,
                        onStop = viewModel::stopExecution,
                        onDetails = { onShowRuntimePanelChange(true) },
                        runtimePanelVisible = showRuntimePanel,
                        onToggleRuntimePanel = { onShowRuntimePanelChange(!showRuntimePanel) },
                        onInspectRuntime = onInspectRuntime,
                    )
                }
                DisableSelection {
                    if (externalChannel == null) {
                        // The ViewModel claims the composer draft atomically before asynchronous submission.
                        MessageInput(
                            statusContent = activityContent,
                            userInput = userInput,
                            onUserInputChange = { viewModel.updateUserInput(it) },
                            isWaitingForResponse = isWaitingForResponse,
                            pendingMessagesCount = pendingMessagesCount,
                            agentMentionCandidates = agentMentionCandidates,
                            messageSubmissionError = messageSubmissionError?.resolve(localization),
                            suggestedReplies = suggestedReplies,
                            suggestedRepliesRegenerating = suggestedRepliesRegeneratingFor != null,
                            onRegenerateSuggestedReplies = viewModel::regenerateSuggestedReplies,
                            onSendMessage = viewModel::submitUserInputToSession,
                            coroutineScope = coroutineScope,
                            pttEventHandler = pttEventHandler,
                            pttState = pttState,
                            voiceAutoSend = uiState.voiceAutoSend ?: settings.userDeviceSettings.voiceInputSettings.autoSend,
                            onVoiceAutoSendChange = viewModel::setVoiceAutoSend,
                            pttUnavailableReason = pttUnavailableReason,
                            liveVoiceInputService = liveVoiceInputService,
                            liveVoiceInputState = liveVoiceInputState,
                            liveVoiceInputUnavailableReason = liveVoiceInputUnavailableReason,
                            showLiveVoiceButton = settings.userDeviceSettings.voiceInputSettings.liveVoiceInputEnabled,
                            showPttButton = settings.userProfile.speechSettings.speechToText.enabled,
                            clientPlatform = clientPlatform,
                            instructionGroups = viewModel.messageInstructionGroups,
                            activeInstructionIds = uiState.activeMessageInstructionIds,
                            onSelectInstruction = viewModel::selectMessageInstruction,
                            composerArtifacts = uiState.composerArtifacts,
                            artifactUploadInProgress = uiState.composerArtifactUploadInProgress,
                            artifactError = uiState.composerArtifactError?.resolve(localization),
                            canPickAttachments = viewModel.attachmentCapabilities.filePicker,
                            canCaptureScreenshot = viewModel.attachmentCapabilities.screenshot,
                            onPickAttachments = viewModel::pickAttachments,
                            onCaptureScreenshot = viewModel::captureScreenshot,
                            onRemoveArtifact = viewModel::removeComposerArtifact,
                            onInsertCurrentLocation = onInsertCurrentLocation,
                            editLastMessageShortcut = editLastMessageShortcut,
                            enterKeyAction = settings.userProfile.keyboardShortcuts.enterKeyAction,
                            onEditLastUserMessage = viewModel::startEditLatestUserMessage,
                        )
                    } else {
                        ComposerPanel { activityContent() }
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
    val localization = LocalTranslation.current
    val text = when (state) {
        MessageSquashUiState.Idle -> return
        is MessageSquashUiState.Running -> localization.text("chat.selection.operationRunning", "action" to state.squashType.actionTitle(localization))
        is MessageSquashUiState.Succeeded -> localization.text("chat.selection.operationComplete", "action" to state.squashType.actionTitle(localization))
        is MessageSquashUiState.Failed -> localization.text("chat.selection.operationFailed", "action" to state.squashType.actionTitle(localization), "message" to state.message.resolve(localization))
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag(UiTestTag.MessageSquashStatus.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state is MessageSquashUiState.Running) {
            GromozekaLoadingIndicator(modifier = Modifier.size(16.dp))
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

private fun com.gromozeka.domain.model.SquashType.actionTitle(localization: Translation): String = when (this) {
    com.gromozeka.domain.model.SquashType.CONCATENATE -> localization.text("chat.selection.concat")
    com.gromozeka.domain.model.SquashType.DISTILL -> localization.text("chat.selection.distill")
    com.gromozeka.domain.model.SquashType.SUMMARIZE -> localization.text("chat.selection.summarize")
}
