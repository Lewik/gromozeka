package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MessageInput(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    isWaitingForResponse: Boolean,
    pendingMessagesCount: Int,
    onSendMessage: suspend () -> Unit,
    coroutineScope: CoroutineScope,
    pttEventHandler: PttEventHandler,
    pttState: PttState,
    pttStatusMessage: String?,
    pttUnavailableReason: String?,
    showPttButton: Boolean,
    compactVoiceMode: Boolean,
    clientPlatform: ClientPlatform,
    instructionGroups: List<MessageInstructionGroup>,
    activeInstructionIds: Set<String>,
    onSelectInstruction: (MessageInstructionGroup, Int) -> Unit,
    onCaptureScreenshot: suspend () -> Unit,
    onInsertCurrentLocation: (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    var inputFocused by remember { mutableStateOf(false) }
    var previousPttState by remember { mutableStateOf(pttState) }
    var previousPttStatusMessage by remember { mutableStateOf(pttStatusMessage) }

    LaunchedEffect(pttState, pttStatusMessage) {
        when {
            pttState == PttState.IDLE &&
                pttStatusMessage != null &&
                (previousPttState != PttState.IDLE || previousPttStatusMessage != pttStatusMessage) ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Reject)

            pttState == PttState.RECORDING && previousPttState != PttState.RECORDING ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOn)

            pttState == PttState.TRANSCRIBING && previousPttState == PttState.RECORDING ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOff)
        }
        previousPttState = pttState
        previousPttStatusMessage = pttStatusMessage
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
        if (userInput.isBlank()) return
        coroutineScope.launch {
            onSendMessage()
        }
    }

    fun toggleVoiceCapture() {
        coroutineScope.launch {
            pttEventHandler.toggleVoiceCapture()
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
                onToggle = ::toggleVoiceCapture,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val actionAreaMaxWidth = maxWidth * 0.64f

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = userInput,
                    onValueChange = onUserInputChange,
                    modifier = Modifier
                        .onFocusChanged { inputFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.key == Key.Enter &&
                                    event.isShiftPressed -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        submitInput()
                                    }
                                    true
                                }

                                event.utf16CodePoint == 167 -> true
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

                    if (showPttButton && !compactVoiceMode) {
                        val isRecording = pttState == PttState.RECORDING
                        CompactButton(
                            onClick = ::toggleVoiceCapture,
                            enabled = pttState != PttState.TRANSCRIBING &&
                                (pttState != PttState.IDLE || pttUnavailableReason == null),
                            modifier = Modifier
                                .zIndex(2f)
                                .size(actionButtonSize)
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

                    CompactButton(
                        onClick = {
                            coroutineScope.launch {
                                onCaptureScreenshot()
                            }
                        },
                        modifier = Modifier.size(actionButtonSize),
                        tooltip = LocalTranslation.current.screenshotTooltip,
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = LocalTranslation.current.screenshotTooltip,
                        )
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

@Composable
private fun VoiceCaptureStatus(
    state: PttState,
    statusMessage: String?,
    unavailableReason: String?,
    expandedIdle: Boolean,
    onToggle: () -> Unit,
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
        Modifier.clickable(onClick = onToggle)
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
