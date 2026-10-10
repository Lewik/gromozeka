package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RectangleShape
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.PTTEvent
import com.gromozeka.presentation.services.LiveVoiceInputService
import com.gromozeka.presentation.services.LiveVoiceInputState
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.EnterKeyAction
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.presentation.ui.displayLabel
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.AgentMentionCandidate
import com.gromozeka.presentation.ui.AgentMentionResolution
import com.gromozeka.presentation.ui.resolveAgentMention
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.CompactIconButton
import com.gromozeka.presentation.ui.CompactTextField
import com.gromozeka.presentation.ui.joinedButtonShape
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.advancedPttGestures
import com.gromozeka.presentation.ui.messageInputShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator

@Composable
internal fun MessageInput(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    isWaitingForResponse: Boolean,
    pendingMessagesCount: Int,
    agentMentionCandidates: List<AgentMentionCandidate>,
    messageSubmissionError: String?,
    suggestedReplies: SuggestedReplyOptions?,
    suggestedRepliesRegenerating: Boolean,
    onRegenerateSuggestedReplies: (Conversation.Message.Id) -> Unit,
    onSendMessage: suspend () -> Unit,
    coroutineScope: CoroutineScope,
    pttEventHandler: PttEventHandler,
    pttState: PttState,
    pttUnavailableReason: String?,
    liveVoiceInputService: LiveVoiceInputService,
    liveVoiceInputState: LiveVoiceInputState,
    liveVoiceInputUnavailableReason: String?,
    showLiveVoiceButton: Boolean,
    showPttButton: Boolean,
    clientPlatform: ClientPlatform,
    instructionGroups: List<MessageInstructionGroup>,
    activeInstructionIds: Set<String>,
    onSelectInstruction: (MessageInstructionGroup, Int) -> Unit,
    composerArtifacts: List<Artifact.Reference>,
    artifactUploadInProgress: Boolean,
    artifactError: String?,
    canPickAttachments: Boolean,
    canCaptureScreenshot: Boolean,
    onPickAttachments: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onRemoveArtifact: (Artifact.Id) -> Unit,
    onInsertCurrentLocation: (() -> Unit)? = null,
    enterKeyAction: EnterKeyAction = EnterKeyAction.NEW_LINE,
    editLastMessageShortcut: KeyboardShortcutBinding? = null,
    onEditLastUserMessage: () -> Boolean = { false },
    voiceAutoSend: Boolean = true,
    onVoiceAutoSendChange: (Boolean) -> Unit = {},
    statusContent: @Composable () -> Unit = {},
    messageDeliveryMode: com.gromozeka.domain.model.UserMessageDeliveryMode = com.gromozeka.domain.model.UserMessageDeliveryMode.STEER,
    modifier: Modifier = Modifier,
) {
    val localization = LocalTranslation.current
    val spacing = GromozekaTheme.spacing
    val controls = GromozekaTheme.controls
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }
    val hapticFeedback = LocalHapticFeedback.current
    var inputFocused by remember { mutableStateOf(false) }
    var previousPttState by remember { mutableStateOf(pttState) }
    var mentionFocusRequest by remember { mutableIntStateOf(0) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(userInput, selection = TextRange(userInput.length)))
    }

    LaunchedEffect(userInput) {
        if (textFieldValue.text != userInput) {
            textFieldValue = TextFieldValue(userInput, selection = TextRange(userInput.length))
        }
    }

    LaunchedEffect(mentionFocusRequest) {
        if (mentionFocusRequest > 0) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(pttState) {
        when {
            pttState == PttState.RECORDING && previousPttState != PttState.RECORDING ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOn)

            pttState == PttState.TRANSCRIBING && previousPttState == PttState.RECORDING ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOff)
        }
        previousPttState = pttState
    }

    val submitInput: () -> Unit = {
        if ((userInput.isNotBlank() || composerArtifacts.isNotEmpty()) && !artifactUploadInProgress) {
            coroutineScope.launch { onSendMessage() }
        }
    }

    val hideKeyboard: () -> Unit = { keyboardController?.hide(); focusManager.clearFocus(force = true) }
    val liveActive = liveVoiceInputState != LiveVoiceInputState.IDLE
    val showLiveControl = liveActive || (showLiveVoiceButton && liveVoiceInputUnavailableReason == null)
    val pttAvailable = showPttButton && pttUnavailableReason == null
    val showVoiceMode = pttAvailable || liveActive || pttState == PttState.RECORDING || pttState == PttState.PREPARING
    val mentionError = (resolveAgentMention(userInput, agentMentionCandidates) as? AgentMentionResolution.Invalid)
        ?.message?.resolve(localization)
    val inputError = messageSubmissionError?.takeIf(String::isNotBlank) ?: mentionError

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compactControls = maxWidth < 600.dp
        // Keep the editor's scroll viewport stable when the software keyboard opens.
        val editorMaxLines = 6
        Column(Modifier.fillMaxWidth()) {
            SuggestedReplyChips(
                options = suggestedReplies,
                modifier = Modifier.padding(horizontal = spacing.panelPadding),
                onSuggestionSelected = { suggestion ->
                    textFieldValue = insertSuggestedReply(textFieldValue, suggestion)
                    onUserInputChange(textFieldValue.text)
                },
            )
            ComposerPanel {
                statusContent()
                artifactError?.takeIf(String::isNotBlank)?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                inputError?.takeIf(String::isNotBlank)?.let { error ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTestTag.MessageSubmissionError.value),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (composerArtifacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.controlGap),
                    ) {
                        composerArtifacts.forEach { artifact ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = {
                                    Text(
                                        text = "${artifact.fileName} · ${artifact.sizeBytes.formatArtifactSize()}",
                                        maxLines = 1,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = localization.text("chat.input.removeAttachment", "fileName" to artifact.fileName),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { onRemoveArtifact(artifact.id) },
                                    )
                                },
                            )
                        }
                    }
                }

                val mentionSuggestions = remember(textFieldValue, agentMentionCandidates) {
                    findAgentMentionSuggestions(textFieldValue, agentMentionCandidates)
                }
                if (mentionSuggestions != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTestTag.AgentMentionSuggestions.value),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp,
                    ) {
                        Column(modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                            mentionSuggestions.candidates.take(8).forEach { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            textFieldValue = insertAgentMention(
                                                textFieldValue,
                                                mentionSuggestions.markerIndex,
                                                candidate.mentionText,
                                            )
                                            onUserInputChange(textFieldValue.text)
                                            mentionFocusRequest++
                                        }
                                        .testTag(UiTestTag.AgentMentionOption(candidate.agentDefinitionId.value).value)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(candidate.mentionText, style = MaterialTheme.typography.bodyMedium)
                                        Text(candidate.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        text = localization.text(if (candidate.connected) "chat.mention.participating" else "chat.mention.notParticipating"),
                                        color = if (candidate.connected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().testTag("composer-controls"),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(spacing.controlGap),
                ) {
                    ComposerActionsMenu(
                        canPickAttachments = canPickAttachments,
                        canCaptureScreenshot = canCaptureScreenshot,
                        artifactUploadInProgress = artifactUploadInProgress,
                        suggestedReplies = suggestedReplies,
                        suggestedRepliesRegenerating = suggestedRepliesRegenerating,
                        onPickAttachments = onPickAttachments,
                        onCaptureScreenshot = onCaptureScreenshot,
                        onRegenerateSuggestedReplies = onRegenerateSuggestedReplies,
                        onToggleLiveVoice = if (compactControls && showLiveControl && !liveActive) {
                            { coroutineScope.launch { liveVoiceInputService.toggle() } }
                        } else null,
                        onInsertCurrentLocation = onInsertCurrentLocation.takeIf { compactControls },
                        onHideKeyboard = hideKeyboard.takeIf { compactControls && clientPlatform.showSoftwareKeyboardControls },
                    )
                    CompactTextField(
                        value = textFieldValue,
                        onValueChange = { value -> textFieldValue = value; onUserInputChange(value.text) },
                        modifier = Modifier.weight(1f)
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { inputFocused = it.isFocused }
                            .messageInputShortcuts(
                                enterKeyAction = enterKeyAction,
                                isComposing = { textFieldValue.composition != null },
                                isEmpty = { textFieldValue.text.isEmpty() },
                                editLastMessageShortcut = editLastMessageShortcut,
                                onEditLastUserMessage = onEditLastUserMessage,
                                onSubmit = submitInput,
                            ).testTag(UiTestTag.MessageInput.value),
                        placeholder = {
                            Text(localization.text("chat.composer.placeholder"), style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        maxLines = editorMaxLines,
                        shape = RectangleShape,
                        errorMessage = inputError,
                    )
                    Row(Modifier.testTag("composer-voice-controls"), verticalAlignment = Alignment.CenterVertically) {
                        val recording = pttState == PttState.RECORDING
                        // Joined styling does not change recording or interruption gestures.
                        CompactButton(
                            onClick = { coroutineScope.launch { pttEventHandler.handlePTTEvent(PTTEvent.SINGLE_CLICK) } },
                            contentPadding = PaddingValues(spacing.controlGap),
                            modifier = Modifier.size(controls.minHeight)
                                .advancedPttGestures(pttEventHandler, coroutineScope)
                                .testTag(UiTestTag.PttButton.value),
                            shape = joinedButtonShape(0, if (showVoiceMode) 2 else 1),
                            elevation = null,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (recording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                            ),
                            tooltip = listOfNotNull(
                                localization.text(if (pttAvailable) "chat.voice.control.hint" else "chat.voice.control.interruptHint"),
                                pttUnavailableReason,
                            ).joinToString("\n"),
                        ) {
                            if (pttState == PttState.PREPARING) GromozekaLoadingIndicator(Modifier.size(controls.iconSize))
                            else Icon(if (recording || !pttAvailable || pttState == PttState.TRANSCRIBING) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (pttAvailable) localization.pushToTalkText else localization.runtime.stopButton,
                                modifier = Modifier.size(controls.iconSize))
                        }
                        if (showVoiceMode) {
                            val modeLabel = localization.text(if (voiceAutoSend) "chat.voice.afterDictation.send" else "chat.voice.afterDictation.input")
                            val modeHint = localization.text(if (voiceAutoSend) "chat.voice.afterDictation.sendHint" else "chat.voice.afterDictation.inputHint")
                            CompactButton(
                                onClick = { onVoiceAutoSendChange(!voiceAutoSend) },
                                enabled = pttState != PttState.RECORDING && pttState != PttState.PREPARING && liveVoiceInputState != LiveVoiceInputState.SPEECH,
                                modifier = Modifier.size(controls.minHeight)
                                    .testTag("voice-send-mode")
                                    .semantics { contentDescription = modeHint; stateDescription = modeLabel },
                                shape = joinedButtonShape(1, 2),
                                elevation = null,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentPadding = PaddingValues(spacing.controlGap),
                                tooltip = modeHint,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (voiceAutoSend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (voiceAutoSend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Icon(if (voiceAutoSend) Icons.Default.DictationSend else Icons.Default.DictationInput,
                                    null, Modifier.size(controls.iconSize))
                            }
                        }
                    }
                    // On a narrow pane Live starts from the menu; an active session retains its visible stop control.
                    if (showLiveControl && (!compactControls || liveActive)) {
                        CompactButton(
                            onClick = { coroutineScope.launch { liveVoiceInputService.toggle() } },
                            contentPadding = PaddingValues(spacing.controlGap),
                            enabled = liveActive || liveVoiceInputUnavailableReason == null,
                            modifier = Modifier.size(controls.minHeight).testTag(UiTestTag.LiveVoiceButton.value),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (liveActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (liveActive) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                            ),
                            tooltip = if (liveActive) localization.text("chat.voice.continuous.stop")
                                else liveVoiceInputUnavailableReason ?: localization.text("chat.voice.continuous.start"),
                        ) {
                            Icon(Icons.Default.VoiceWave,
                                localization.text(if (liveActive) "chat.voice.continuous.stop" else "chat.voice.continuous.start"), Modifier.size(controls.iconSize))
                        }
                    }
                    val quickInstructions = instructionGroups.filter { it.showInComposer }
                    if (quickInstructions.isNotEmpty()) {
                        Row(Modifier.widthIn(max = controls.minHeight * if (compactControls) 1 else 3).horizontalScroll(rememberScrollState())) {
                            quickInstructions.forEach { group ->
                                QuickMessageInstructionButton(group, activeInstructionIds, onSelectInstruction,
                                    modifier = Modifier.testTag("composer-instruction:${group.id}"))
                            }
                        }
                    }
                    if (!compactControls) {
                        onInsertCurrentLocation?.let { insert ->
                            CompactIconButton(onClick = insert, icon = Icons.Default.LocationOn,
                                contentDescription = localization.text("chat.input.insertLocation"), modifier = Modifier.testTag("composer-location"))
                        }
                        if (clientPlatform.showSoftwareKeyboardControls && inputFocused) {
                            CompactIconButton(onClick = hideKeyboard, icon = Icons.Default.KeyboardHide,
                                contentDescription = localization.text("chat.input.hideKeyboard"), modifier = Modifier.testTag("composer-hide-keyboard"))
                        }
                    }
                    BadgedBox(badge = { if (pendingMessagesCount > 0) Badge { Text("$pendingMessagesCount") } }) {
                        CompactButton(
                            onClick = submitInput, modifier = Modifier.size(controls.minHeight).testTag(UiTestTag.SendButton.value),
                            contentPadding = PaddingValues(spacing.controlGap),
                            tooltip = messageDeliveryMode.displayLabel(localization),
                        ) { Icon(Icons.Default.Send, localization.text("chat.input.send"), Modifier.size(controls.iconSize)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ComposerActionsMenu(
    canPickAttachments: Boolean,
    canCaptureScreenshot: Boolean,
    artifactUploadInProgress: Boolean,
    suggestedReplies: SuggestedReplyOptions?,
    suggestedRepliesRegenerating: Boolean,
    onPickAttachments: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onRegenerateSuggestedReplies: (Conversation.Message.Id) -> Unit,
    onToggleLiveVoice: (() -> Unit)? = null,
    onInsertCurrentLocation: (() -> Unit)? = null,
    onHideKeyboard: (() -> Unit)? = null,
) {
    if (!canPickAttachments && !canCaptureScreenshot && suggestedReplies == null &&
        onToggleLiveVoice == null && onInsertCurrentLocation == null && onHideKeyboard == null) return
    val translation = LocalTranslation.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        CompactIconButton(
            onClick = { expanded = true },
            icon = Icons.Default.Add,
            contentDescription = translation.text("chat.input.moreActions"),
            modifier = Modifier.testTag("composer-more-actions"),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
        ) {
            if (canPickAttachments) DropdownMenuItem(
                text = { Text(translation.text("chat.input.attachFiles")) },
                leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                enabled = !artifactUploadInProgress,
                onClick = { expanded = false; onPickAttachments() },
                modifier = Modifier.testTag("composer-attach-file"),
            )
            if (canCaptureScreenshot) DropdownMenuItem(
                text = { Text(translation.screenshotTooltip) },
                leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                enabled = !artifactUploadInProgress,
                onClick = { expanded = false; onCaptureScreenshot() },
                modifier = Modifier.testTag("composer-capture-screenshot"),
            )
            onToggleLiveVoice?.let { toggle ->
                DropdownMenuItem(
                    text = { Text(translation.text("chat.voice.continuous.start")) },
                    leadingIcon = { Icon(Icons.Default.VoiceWave, null) },
                    onClick = { expanded = false; toggle() },
                    modifier = Modifier.testTag(UiTestTag.LiveVoiceButton.value),
                )
            }
            onInsertCurrentLocation?.let { insert ->
                DropdownMenuItem(
                    text = { Text(translation.text("chat.input.insertLocation")) },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                    onClick = { expanded = false; insert() },
                )
            }
            onHideKeyboard?.let { hide ->
                DropdownMenuItem(
                    text = { Text(translation.text("chat.input.hideKeyboard")) },
                    leadingIcon = { Icon(Icons.Default.KeyboardHide, null) },
                    onClick = { expanded = false; hide() },
                    modifier = Modifier.testTag("composer-hide-keyboard"),
                )
            }
            suggestedReplies?.let { options ->
                if (canPickAttachments || canCaptureScreenshot) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(translation.text("chat.suggestions.regenerate")) },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    enabled = !suggestedRepliesRegenerating,
                    onClick = { expanded = false; onRegenerateSuggestedReplies(options.sourceMessageId) },
                    modifier = Modifier.testTag(UiTestTag.SuggestedRepliesRefresh.value),
                )
            }
        }
    }
}

private data class AgentMentionSuggestions(
    val markerIndex: Int,
    val candidates: List<AgentMentionCandidate>,
)

private fun findAgentMentionSuggestions(
    value: TextFieldValue,
    candidates: List<AgentMentionCandidate>,
): AgentMentionSuggestions? {
    if (!value.selection.collapsed || candidates.isEmpty()) return null
    val cursor = value.selection.start
    val markerIndex = value.text.lastIndexOf('@', startIndex = cursor - 1)
    if (markerIndex < 0) return null
    if (markerIndex > 0 && !value.text[markerIndex - 1].isMentionBoundary()) return null

    val typedMention = value.text.substring(markerIndex, cursor)
    if ('\n' in typedMention || '\r' in typedMention) return null
    if (candidates.any { candidate ->
            typedMention.length > candidate.mentionText.length &&
                typedMention.regionMatches(0, candidate.mentionText, 0, candidate.mentionText.length, ignoreCase = true) &&
                typedMention[candidate.mentionText.length].isMentionBoundary()
        }
    ) {
        return null
    }

    val matches = candidates.filter { candidate ->
        candidate.mentionText.startsWith(typedMention, ignoreCase = true)
    }
    return matches.takeIf(List<*>::isNotEmpty)?.let { AgentMentionSuggestions(markerIndex, it) }
}

private fun insertAgentMention(
    value: TextFieldValue,
    markerIndex: Int,
    mentionText: String,
): TextFieldValue {
    val replacement = "$mentionText "
    val text = value.text.replaceRange(markerIndex, value.selection.start, replacement)
    val cursor = markerIndex + replacement.length
    return TextFieldValue(text, TextRange(cursor))
}

private fun Char.isMentionBoundary(): Boolean = !isLetterOrDigit() && this != '_'

internal fun insertSuggestedReply(
    input: TextFieldValue,
    suggestion: String,
): TextFieldValue {
    val start = input.selection.min.coerceIn(0, input.text.length)
    val end = input.selection.max.coerceIn(start, input.text.length)
    val normalizedSuggestion = suggestion.trim()
    if (normalizedSuggestion.isEmpty()) return input

    val leadingSpace = if (start > 0 && !input.text[start - 1].isWhitespace()) " " else ""
    val trailingSpace = if (end < input.text.length && !input.text[end].isWhitespace()) " " else ""
    val insertion = leadingSpace + normalizedSuggestion + trailingSpace
    val updatedText = input.text.replaceRange(start, end, insertion)
    return TextFieldValue(
        text = updatedText,
        selection = TextRange(start + insertion.length),
    )
}


private fun Long?.formatArtifactSize(): String = when {
    this == null -> "—"
    this >= 1024 * 1024 -> "${this / (1024 * 1024)} MB"
    this >= 1024 -> "${this / 1024} KB"
    else -> "$this B"
}
