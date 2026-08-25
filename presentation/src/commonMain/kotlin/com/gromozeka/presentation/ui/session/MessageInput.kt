package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.LiveVoiceInputService
import com.gromozeka.presentation.services.LiveVoiceInputState
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.advancedPttGestures
import com.gromozeka.presentation.ui.matches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MessageInput(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    isWaitingForResponse: Boolean,
    pendingMessagesCount: Int,
    suggestedReplies: SuggestedReplyOptions?,
    suggestedRepliesRegenerating: Boolean,
    onRegenerateSuggestedReplies: (Conversation.Message.Id) -> Unit,
    onSendMessage: suspend () -> Unit,
    coroutineScope: CoroutineScope,
    pttEventHandler: PttEventHandler,
    pttState: PttState,
    pttStatusMessage: String?,
    pttUnavailableReason: String?,
    liveVoiceInputService: LiveVoiceInputService,
    liveVoiceInputState: LiveVoiceInputState,
    liveVoiceInputStatusMessage: String?,
    liveVoiceInputUnavailableReason: String?,
    showLiveVoiceButton: Boolean,
    showPttButton: Boolean,
    compactVoiceMode: Boolean,
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
    editLastMessageShortcut: KeyboardShortcutBinding? = null,
    onEditLastUserMessage: () -> Boolean = { false },
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    var inputFocused by remember { mutableStateOf(false) }
    var previousPttState by remember { mutableStateOf(pttState) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(userInput, selection = TextRange(userInput.length)))
    }

    LaunchedEffect(userInput) {
        if (textFieldValue.text != userInput) {
            textFieldValue = TextFieldValue(userInput, selection = TextRange(userInput.length))
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

    val textFieldPadding = OutlinedTextFieldDefaults.contentPadding()
    val textFieldLineHeight = with(LocalDensity.current) {
        MaterialTheme.typography.bodyLarge.lineHeight.toDp()
    }
    val actionButtonSize = maxOf(
        OutlinedTextFieldDefaults.MinHeight,
        textFieldLineHeight + textFieldPadding.calculateTopPadding() + textFieldPadding.calculateBottomPadding(),
    )

    fun submitInput() {
        if ((userInput.isBlank() && composerArtifacts.isEmpty()) || artifactUploadInProgress) return
        coroutineScope.launch {
            onSendMessage()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showPttButton) {
            VoiceCaptureStatus(
                state = pttState,
                statusMessage = pttStatusMessage,
                unavailableReason = pttUnavailableReason,
                expandedIdle = compactVoiceMode,
                pttEventHandler = pttEventHandler,
                coroutineScope = coroutineScope,
            )
        }

        if (showLiveVoiceButton) {
            LiveVoiceStatus(
                state = liveVoiceInputState,
                statusMessage = liveVoiceInputStatusMessage,
                unavailableReason = liveVoiceInputUnavailableReason,
            )
        }

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

        if (composerArtifacts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                contentDescription = "Remove ${artifact.fileName}",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRemoveArtifact(artifact.id) },
                            )
                        },
                    )
                }
            }
        }

        SuggestedReplyChips(
            options = suggestedReplies,
            onSuggestionSelected = { suggestion ->
                textFieldValue = insertSuggestedReply(textFieldValue, suggestion)
                onUserInputChange(textFieldValue.text)
            },
            onRegenerate = onRegenerateSuggestedReplies,
            isRegenerating = suggestedRepliesRegenerating,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val actionAreaMaxWidth = maxWidth * 0.64f

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { value ->
                        textFieldValue = value
                        onUserInputChange(value.text)
                    },
                    modifier = Modifier
                        .onFocusChanged { inputFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            when {
                                editLastMessageShortcut != null &&
                                    textFieldValue.text.isEmpty() &&
                                    event.matches(editLastMessageShortcut) -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        onEditLastUserMessage()
                                    }
                                    editLastMessageShortcut.consumeEvent
                                }

                                event.key == Key.Enter &&
                                    event.isShiftPressed -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        submitInput()
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        .weight(1f)
                        .testTag(UiTestTag.MessageInput.value),
                    placeholder = { Text("") },
                )
                Spacer(modifier = Modifier.width(4.dp))

                Row(
                    modifier = Modifier
                        .widthIn(max = actionAreaMaxWidth)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (clientPlatform.showSoftwareKeyboardControls && inputFocused) {
                        CompactButton(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus(force = true)
                            },
                            modifier = Modifier.size(actionButtonSize),
                            tooltip = "Hide keyboard",
                        ) {
                            Icon(Icons.Default.KeyboardHide, contentDescription = "Hide keyboard")
                        }
                    }

                    BadgedBox(
                        modifier = Modifier.zIndex(1f),
                        badge = {
                            if (pendingMessagesCount > 0) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text("$pendingMessagesCount")
                                }
                            }
                        },
                    ) {
                        CompactButton(
                            onClick = ::submitInput,
                            modifier = Modifier
                                .size(actionButtonSize)
                                .testTag(UiTestTag.SendButton.value),
                            tooltip = when {
                                isWaitingForResponse && pendingMessagesCount > 0 ->
                                    "Поставить в очередь ($pendingMessagesCount уже ждёт)"
                                isWaitingForResponse -> "Поставить в очередь"
                                else -> LocalTranslation.current.sendMessageTooltip
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                            )
                        }
                    }

                    if (canPickAttachments) {
                        CompactButton(
                            onClick = onPickAttachments,
                            enabled = !artifactUploadInProgress,
                            modifier = Modifier.size(actionButtonSize),
                            tooltip = "Attach files",
                        ) {
                            if (artifactUploadInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach files")
                            }
                        }
                    }

                    if (showPttButton && !compactVoiceMode) {
                        val isRecording = pttState == PttState.RECORDING
                        CompactButton(
                            onClick = {},
                            enabled = pttState != PttState.TRANSCRIBING &&
                                (pttState != PttState.IDLE || pttUnavailableReason == null),
                            modifier = Modifier
                                .zIndex(2f)
                                .size(actionButtonSize)
                                .then(
                                    if (
                                        pttState == PttState.TRANSCRIBING ||
                                        (pttState == PttState.IDLE && pttUnavailableReason != null)
                                    ) {
                                        Modifier
                                    } else {
                                        Modifier.advancedPttGestures(pttEventHandler, coroutineScope)
                                    }
                                )
                                .testTag(UiTestTag.PttButton.value),
                            colors = if (isRecording) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            tooltip = when (pttState) {
                                PttState.IDLE -> pttUnavailableReason
                                    ?: LocalTranslation.current.pttButtonTooltip
                                PttState.PREPARING -> LocalTranslation.current.runtime.preparingVoiceStatus
                                PttState.RECORDING -> LocalTranslation.current.recordingTooltip
                                PttState.TRANSCRIBING -> LocalTranslation.current.runtime.transcribingVoiceStatus
                            },
                        ) {
                            if (pttState == PttState.PREPARING || pttState == PttState.TRANSCRIBING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (isRecording) {
                                        Icons.Default.Stop
                                    } else {
                                        Icons.Default.Mic
                                    },
                                    contentDescription = if (isRecording) {
                                        LocalTranslation.current.recordingText
                                    } else {
                                        LocalTranslation.current.pushToTalkText
                                    },
                                )
                            }
                        }
                    }

                    if (showLiveVoiceButton) {
                        val isActive = liveVoiceInputState != LiveVoiceInputState.IDLE
                        CompactButton(
                            onClick = {
                                coroutineScope.launch {
                                    liveVoiceInputService.toggle()
                                }
                            },
                            enabled = liveVoiceInputState != LiveVoiceInputState.STARTING &&
                                (liveVoiceInputState != LiveVoiceInputState.IDLE ||
                                    liveVoiceInputUnavailableReason == null),
                            modifier = Modifier
                                .size(actionButtonSize)
                                .testTag(UiTestTag.LiveVoiceButton.value),
                            colors = if (isActive) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            tooltip = when (liveVoiceInputState) {
                                LiveVoiceInputState.IDLE -> liveVoiceInputUnavailableReason
                                    ?: "Start continuous voice input"
                                LiveVoiceInputState.STARTING -> "Starting continuous voice input"
                                LiveVoiceInputState.LISTENING -> "Stop continuous voice input"
                                LiveVoiceInputState.SPEECH -> "Listening to phrase"
                                LiveVoiceInputState.TRANSCRIBING -> "Transcribing phrase"
                            },
                        ) {
                            if (
                                liveVoiceInputState == LiveVoiceInputState.STARTING ||
                                liveVoiceInputState == LiveVoiceInputState.TRANSCRIBING
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (isActive) {
                                        "Stop continuous voice input"
                                    } else {
                                        "Start continuous voice input"
                                    },
                                )
                            }
                        }
                    }

                    if (canCaptureScreenshot) {
                        CompactButton(
                            onClick = onCaptureScreenshot,
                            enabled = !artifactUploadInProgress,
                            modifier = Modifier.size(actionButtonSize),
                            tooltip = LocalTranslation.current.screenshotTooltip,
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = LocalTranslation.current.screenshotTooltip,
                            )
                        }
                    }

                    onInsertCurrentLocation?.let { insertCurrentLocation ->
                        CompactButton(
                            onClick = insertCurrentLocation,
                            modifier = Modifier.size(actionButtonSize),
                            tooltip = "Insert current device location",
                        ) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = "Insert location",
                            )
                        }
                    }

                    instructionGroups
                        .filter { it.showInComposer }
                        .forEach { group ->
                            QuickMessageInstructionButton(
                                group = group,
                                activeInstructionIds = activeInstructionIds,
                                onSelect = onSelectInstruction,
                                modifier = Modifier.size(actionButtonSize),
                            )
                        }
                }
            }
        }
    }
}

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

@Composable
private fun LiveVoiceStatus(
    state: LiveVoiceInputState,
    statusMessage: String?,
    unavailableReason: String?,
) {
    if (state == LiveVoiceInputState.IDLE && statusMessage.isNullOrBlank() && unavailableReason == null) return

    val isActive = state != LiveVoiceInputState.IDLE
    val isError = state == LiveVoiceInputState.IDLE && !statusMessage.isNullOrBlank()
    val title = when (state) {
        LiveVoiceInputState.IDLE -> statusMessage
            ?.takeIf(String::isNotBlank)
            ?: unavailableReason
            ?: "Continuous voice input ready"
        LiveVoiceInputState.STARTING -> "Starting continuous voice input"
        LiveVoiceInputState.LISTENING -> statusMessage ?: "Listening continuously"
        LiveVoiceInputState.SPEECH -> statusMessage ?: "Listening to phrase"
        LiveVoiceInputState.TRANSCRIBING -> statusMessage ?: "Transcribing phrase"
    }
    val hint = when (state) {
        LiveVoiceInputState.IDLE -> if (unavailableReason == null) "Tap the live microphone to keep listening" else null
        LiveVoiceInputState.STARTING -> null
        LiveVoiceInputState.LISTENING -> "Say a phrase; it will be queued after silence"
        LiveVoiceInputState.SPEECH -> "Finish speaking to send this phrase"
        LiveVoiceInputState.TRANSCRIBING -> null
    }
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        state == LiveVoiceInputState.SPEECH -> MaterialTheme.colorScheme.tertiaryContainer
        isActive -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        unavailableReason != null -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        state == LiveVoiceInputState.SPEECH -> MaterialTheme.colorScheme.onTertiaryContainer
        isActive -> MaterialTheme.colorScheme.onSecondaryContainer
        unavailableReason != null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag(UiTestTag.LiveVoiceStatus.value),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                LiveVoiceInputState.STARTING,
                LiveVoiceInputState.TRANSCRIBING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )

                LiveVoiceInputState.SPEECH -> Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )

                LiveVoiceInputState.IDLE,
                LiveVoiceInputState.LISTENING -> Icon(
                    if (unavailableReason == null) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceCaptureStatus(
    state: PttState,
    statusMessage: String?,
    unavailableReason: String?,
    expandedIdle: Boolean,
    pttEventHandler: PttEventHandler,
    coroutineScope: CoroutineScope,
) {
    if (state == PttState.IDLE && statusMessage.isNullOrBlank() && !expandedIdle) return

    val translation = LocalTranslation.current.runtime
    val isError = state == PttState.IDLE && !statusMessage.isNullOrBlank()
    val isUnavailable = state == PttState.IDLE && statusMessage.isNullOrBlank() && unavailableReason != null
    val isInteractive = when (state) {
        PttState.IDLE -> unavailableReason == null
        PttState.PREPARING,
        PttState.RECORDING -> true
        PttState.TRANSCRIBING -> false
    }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(state) {
        recordingSeconds = 0
        if (state == PttState.RECORDING) {
            while (true) {
                delay(1_000)
                recordingSeconds += 1
            }
        }
    }

    val title = when (state) {
        PttState.IDLE -> statusMessage
            ?.takeIf(String::isNotBlank)
            ?: unavailableReason
            ?: translation.voiceInputReadyStatus
        PttState.PREPARING -> translation.preparingVoiceStatus
        PttState.RECORDING -> translation.recordingVoiceStatus
        PttState.TRANSCRIBING -> translation.transcribingVoiceStatus
    }
    val hint = when (state) {
        PttState.IDLE -> if (isError || isUnavailable) null else translation.startVoiceCaptureHint
        PttState.PREPARING -> translation.cancelVoiceCaptureHint
        PttState.RECORDING -> translation.stopVoiceCaptureHint
        PttState.TRANSCRIBING -> null
    }
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        state == PttState.RECORDING -> MaterialTheme.colorScheme.errorContainer
        isUnavailable -> MaterialTheme.colorScheme.surfaceVariant
        state == PttState.IDLE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    }
    val contentColor = when {
        isError || state == PttState.RECORDING -> MaterialTheme.colorScheme.onErrorContainer
        isUnavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        state == PttState.IDLE -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val interactionModifier = if (isInteractive) {
        Modifier
            .clickable(onClick = {})
            .advancedPttGestures(pttEventHandler, coroutineScope)
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (expandedIdle) 72.dp else 60.dp)
            .testTag(UiTestTag.VoiceCaptureStatus.value)
            .then(interactionModifier),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                PttState.PREPARING,
                PttState.TRANSCRIBING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )

                PttState.RECORDING -> Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )

                PttState.IDLE -> Icon(
                    when {
                        isError -> Icons.Default.ErrorOutline
                        isUnavailable -> Icons.Default.MicOff
                        else -> Icons.Default.Mic
                    },
                    contentDescription = null,
                    modifier = Modifier.size(if (expandedIdle) 28.dp else 24.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.78f),
                    )
                }
            }
            if (state == PttState.RECORDING) {
                Text(
                    text = recordingSeconds.asRecordingDuration(),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}

private fun Int.asRecordingDuration(): String =
    "${this / 60}:${(this % 60).toString().padStart(2, '0')}"

private fun Long.formatArtifactSize(): String = when {
    this >= 1024 * 1024 -> "${this / (1024 * 1024)} MB"
    this >= 1024 -> "${this / 1024} KB"
    else -> "$this B"
}
