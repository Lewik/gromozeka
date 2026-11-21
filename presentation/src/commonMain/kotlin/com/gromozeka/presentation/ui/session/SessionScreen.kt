package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.domain.model.Conversation
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
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun SessionScreen(
    viewModel: TabViewModel,
    activityPresentation: ConversationActivityPresentation,
    conversationSidePanelVisible: Boolean,
    conversationSidePanelContent: @Composable () -> Unit,
    sidePanelResize: ConversationSidePanelResizeState,


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
    onInspectRuntime: (RuntimeInspectionSection) -> Unit,


    onInsertCurrentLocation: (() -> Unit)? = null,

    // Dev mode
    isDev: Boolean = false,
    isCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
    contentPadding: Dp = 16.dp,
    slotInventory: SlotInventoryState,
    onOpenSlots: () -> Unit,
) {
    val localization = LocalTranslation.current
    val spacing = GromozekaTheme.spacing
    val controls = GromozekaTheme.controls
    // All data comes from ViewModel
    // Capture one immutable emission: paging callbacks must not re-read a newer State.value.
    val historyContent = viewModel.historyContent.collectAsState().value
    val filteredHistory = historyContent.messages
    val olderHistory by viewModel.olderHistory.collectAsState()
    val newerHistory by viewModel.newerHistory.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()
    val historyActivityRevision by viewModel.historyActivityRevision.collectAsState()
    val historyError by viewModel.historyError.collectAsState()
    val deferredHistory by viewModel.deferredHistoryMessages.collectAsState()
    val externalChannel by viewModel.externalChannel.collectAsState()
    val toolResultsMap by viewModel.toolResultsMap.collectAsState()
    val isWaitingForResponse by viewModel.isWaitingForResponse.collectAsState()
    val messageDeliveryMode by viewModel.messageDeliveryMode.collectAsState()
    val pendingMessagesCount by viewModel.pendingMessagesCount.collectAsState()
    val pendingMessages by viewModel.pendingMessages.collectAsState()
    val lastUserMessageAt by viewModel.lastUserMessageAt.collectAsState()
    val agentMentionCandidates by viewModel.agentMentionCandidates.collectAsState()
    val messageSubmissionError by viewModel.messageSubmissionError.collectAsState()
    val isSubmittingMessage by viewModel.isSubmittingMessage.collectAsState()
    val suggestedRepliesOverride by viewModel.suggestedRepliesOverride.collectAsState()
    val suggestedRepliesRegeneratingFor by viewModel.suggestedRepliesRegeneratingFor.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val runtimeSnapshot by viewModel.runtimeSnapshot.collectAsState()
    val attachmentReceiver = remember(viewModel) {
        ComposerAttachmentReceiver(viewModel.attachmentAcquisitionController, viewModel::acquireAndUploadAttachments)
    }
    val tokenStats by viewModel.tokenStats.collectAsState()
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
    val voiceError = listOfNotNull(
        pttStatusMessage?.takeIf { pttState == PttState.IDLE && pttTarget?.tabId?.value == uiState.tabId },
        liveVoiceInputStatusMessage?.takeIf { liveVoiceInputState == LiveVoiceInputState.IDLE && liveVoiceTarget?.tabId?.value == uiState.tabId },
        voiceTranscriptions.firstOrNull { it.target.tabId.value == uiState.tabId && it.error != null }?.error?.resolve(localization),
    ).firstOrNull()
    val jsonToShow = viewModel.jsonToShow
    val modelActivity = activityPresentation.modelRequest.takeIf { newerHistory == null }
    val messageEntries = rememberConversationTimeline(
        messages = filteredHistory,
        collapsedContentItems = uiState.collapsedContentItems,
        toolResultsMap = toolResultsMap,
        expandedActivityKeys = uiState.expandedActivityKeys,
        modelRequest = modelActivity,
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
            val availableWidth = maxWidth
            val density = androidx.compose.ui.platform.LocalDensity.current
            val narrow = isCompactLayout || maxWidth < 600.dp
            val resizable = !narrow && (clientPlatform == ClientPlatform.DESKTOP || clientPlatform == ClientPlatform.WEB_DESKTOP)
            val rightWidth = sidePanelResize.width(availableWidth)
            val lampContent: @Composable () -> Unit = {
                OptionalTooltip(activityPresentation.tooltip) {
                    Box(Modifier.size(controls.minHeight).testTag("composer-state-indicator")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            enabled = modelActivity == null,
                            role = Role.Button, onClickLabel = localization.text("chat.activity.details"),
                            onClick = { onInspectRuntime(RuntimeInspectionSection.OVERVIEW) }), contentAlignment = Alignment.Center) {
                        if (modelActivity == null) ConversationIndicator(activityPresentation.indicator, ConversationIndicatorDrawing.FULL, activityPresentation.tooltip)
                    }
                }
            }
            val controlsContent: @Composable () -> Unit = {
                ConversationExecutionControls(
                    activityPresentation.indicator.activity == ConversationActivity.PAUSED || activityPresentation.indicator.activity == ConversationActivity.PAUSING,
                    activityPresentation.indicator.activity == ConversationActivity.STOPPING, activityPresentation.canControl,
                    viewModel::pauseExecution, viewModel::resumeExecution, viewModel::stopExecution)
            }
            val chatContent: @Composable () -> Unit = {
                Column(modifier = Modifier.fillMaxSize()) {

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
                                onVisibleItemChanged = { item -> item?.messageId?.let(viewModel::rememberHistoryAnchor) },
                                itemKey = ConversationTimelineItem::key,
                                unreadKey = ConversationTimelineItem::messageId,
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
                                focusItemKey = { it.messageId?.value },
                                onFocusConsumed = {
                                    messageFocusRequest?.let(viewModel::consumeMessageFocus)
                                },
                                modifier = Modifier.weight(1f),
                            ) { timelineItem, pauseFollowingLatest ->
                                ConversationTimelineItemContent(timelineItem) { entry ->
                                    if ((entry.isFirstInMessage || entry.segment is MessageSegment.Activity) && viewModel.hasDeferredHistoryContent(entry.message, deferredHistory)) {
                                        TextButton(
                                            onClick = { pauseFollowingLatest(); viewModel.loadHistoryDetails(entry.message.id) },
                                            modifier = Modifier.testTag("history-details:${entry.message.id.value}"),
                                        ) { Text(localization.text("chat.history.loadFull")) }
                                    }
                                    MessageItem(
                                        entry = entry,
                                        workspaceRootPath = null,
                                        isCompactLayout = isCompactLayout,
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
                }
            }
            val composerContent: @Composable () -> Unit = {
                DisableSelection {
                    Column(Modifier.fillMaxWidth()) {
                        if (remoteConnectionState.status != RemoteConnectionState.Status.CONNECTED) {
                            com.gromozeka.presentation.ui.RemoteConnectionStatus(remoteConnectionState,
                                Modifier.padding(horizontal = spacing.panelPadding, vertical = 2.dp))
                        }
                        if (!voiceError.isNullOrBlank()) {
                            OptionalTooltip(voiceError) {
                                Text(voiceError, Modifier.padding(horizontal = spacing.panelPadding, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (externalChannel == null) {
                            // The ViewModel claims the composer draft atomically before asynchronous submission.
                            MessageInput(
                                leadingContent = lampContent,
                                beforeActionsContent = controlsContent,
                                messageDeliveryMode = messageDeliveryMode,
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
                                isSubmittingMessage = isSubmittingMessage,
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
                                attachmentReceiver = attachmentReceiver,
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
                            ComposerPanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    lampContent()
                                    Spacer(Modifier.weight(1f))
                                    controlsContent()
                                }
                            }
                        }
                    }
                }
            }
            ConversationWorkspaceLayout(
                summaryWidth = rightWidth,
                showSidePanel = conversationSidePanelVisible,
                narrow = narrow,
                modifier = Modifier.fillMaxSize(),
                onResize = if (resizable) { delta -> sidePanelResize.dragBy(availableWidth, with(density) { delta.toDp() }) } else null,
                onResetWidth = if (resizable) sidePanelResize::reset else null,
                chat = chatContent,
                sidePanel = conversationSidePanelContent,
                composer = composerContent,
                summary = {
                    ConversationFooterSummary(runtimeSnapshot, pendingMessages, tokenStats, lastUserMessageAt,
                        messageCount = filteredHistory.size, hasMoreMessages = olderHistory != null || newerHistory != null,
                        conversationId = viewModel.conversationId, slotLoad = slotInventory.load, onOpenSlots = onOpenSlots,
                        onInspect = onInspectRuntime)
                },
            )
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
