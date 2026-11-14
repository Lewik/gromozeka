package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.UserMessageDeliveryMode
import com.gromozeka.presentation.services.*
import com.gromozeka.presentation.ui.AgentMentionCandidate
import com.gromozeka.presentation.ui.ClientPlatform

@Composable
internal fun TestMessageInput(
    draft: String,
    onDraft: (String) -> Unit,
    options: SuggestedReplyOptions? = null,
    waiting: Boolean = false,
    uploading: Boolean = false,
    regenerating: Boolean = false,
    candidates: List<AgentMentionCandidate> = emptyList(),
    instructions: List<MessageInstructionGroup> = emptyList(),
    onInsertLocation: (() -> Unit)? = null,
    voiceAvailable: Boolean = false,
    autoSend: Boolean = true,
    onAutoSend: (Boolean) -> Unit = {},
    pttHandler: PttEventHandler = NoOpPttEventHandler,
    pttState: PttState = PttState.IDLE,
    showLive: Boolean = false,
    liveState: LiveVoiceInputState = LiveVoiceInputState.IDLE,
    liveService: LiveVoiceInputService = NoOpLiveVoiceInputService(),
    liveUnavailableReason: String? = null,
    platform: ClientPlatform = ClientPlatform.DESKTOP,
    deliveryMode: UserMessageDeliveryMode = UserMessageDeliveryMode.STEER,
    onSend: suspend () -> Unit = {},
    onPick: () -> Unit = {},
    onCapture: () -> Unit = {},
    onRegenerate: (Conversation.Message.Id) -> Unit = {},
    leadingContent: @Composable () -> Unit = {},
    beforeActionsContent: @Composable () -> Unit = {},
    artifacts: List<com.gromozeka.domain.model.Artifact.Reference> = emptyList(),
    inputError: String? = null,
    artifactError: String? = null,
) {
    MessageInput(
        userInput = draft, onUserInputChange = onDraft,
        isWaitingForResponse = waiting, pendingMessagesCount = 0,
        agentMentionCandidates = candidates, messageSubmissionError = inputError,
        suggestedReplies = options, suggestedRepliesRegenerating = regenerating,
        onRegenerateSuggestedReplies = onRegenerate, onSendMessage = onSend,
        coroutineScope = rememberCoroutineScope(), pttEventHandler = pttHandler,
        pttState = pttState, pttUnavailableReason = null,
        voiceAutoSend = autoSend, onVoiceAutoSendChange = onAutoSend,
        liveVoiceInputService = liveService, liveVoiceInputState = liveState,
        liveVoiceInputUnavailableReason = liveUnavailableReason, showLiveVoiceButton = showLive, showPttButton = voiceAvailable,
        clientPlatform = platform, instructionGroups = instructions,
        activeInstructionIds = emptySet(), onSelectInstruction = { _, _ -> },
        composerArtifacts = artifacts, artifactUploadInProgress = uploading, artifactError = artifactError,
        canPickAttachments = true, canCaptureScreenshot = true, onPickAttachments = onPick,
        onCaptureScreenshot = onCapture, onRemoveArtifact = {},
        onInsertCurrentLocation = onInsertLocation,
        leadingContent = leadingContent, beforeActionsContent = beforeActionsContent,
        messageDeliveryMode = deliveryMode,
    )
}

@Composable
internal fun TestFooterSummary(
    runtime: com.gromozeka.domain.service.ConversationRuntimeSnapshot?,
    pending: List<com.gromozeka.presentation.ui.viewmodel.PendingUserMessage>,
    tokenStats: com.gromozeka.domain.model.TokenUsageStatistics.ThreadTotals?,
    lastUserMessageAt: kotlin.time.Instant?,
    onInspect: (RuntimeInspectionSection) -> Unit,
    messageCount: Int = 0,
    hasMoreMessages: Boolean = false,
    conversationId: Conversation.Id = Conversation.Id("footer"),
    slotLoad: SlotInventoryLoad = SlotInventoryLoad.Ready(com.gromozeka.domain.slot.SlotCatalogSnapshot()),
    onOpenSlots: () -> Unit = {},
) {
    ConversationFooterSummary(runtime, pending, tokenStats, lastUserMessageAt, messageCount, hasMoreMessages,
        conversationId, slotLoad, onOpenSlots, onInspect)
}
