package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.PttState
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.WorkspaceContextReference
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.advancedPttGestures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun MessageInput(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    isWaitingForResponse: Boolean,
    pendingMessagesCount: Int,
    onSendMessage: suspend (String) -> Unit,
    coroutineScope: CoroutineScope,
    pttEventHandler: PttEventHandler,
    pttState: PttState,
    showPttButton: Boolean,
    clientPlatform: ClientPlatform,
    instructionGroups: List<MessageInstructionGroup>,
    activeInstructionIds: Set<String>,
    onSelectInstruction: (MessageInstructionGroup, Int) -> Unit,
    workspaceContextReferences: List<WorkspaceContextReference>,
    onAddWorkspaceContext: () -> Unit,
    onRemoveWorkspaceContext: (WorkspaceContextReference) -> Unit,
    onCaptureScreenshot: suspend () -> Unit,
    onInsertCurrentLocation: (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var inputFocused by remember { mutableStateOf(false) }

    val textFieldPadding = OutlinedTextFieldDefaults.contentPadding()
    val textFieldLineHeight = with(LocalDensity.current) {
        MaterialTheme.typography.bodyLarge.lineHeight.toDp()
    }
    val actionButtonSize = maxOf(
        OutlinedTextFieldDefaults.MinHeight,
        textFieldLineHeight + textFieldPadding.calculateTopPadding() + textFieldPadding.calculateBottomPadding(),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (workspaceContextReferences.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTag.ContextReferenceChips.value),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                workspaceContextReferences.forEach { reference ->
                    InputChip(
                        selected = true,
                        onClick = {},
                        label = { Text("@${reference.name}") },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${reference.name} from context",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRemoveWorkspaceContext(reference) },
                            )
                        },
                    )
                }
            }
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
                                    event.isShiftPressed &&
                                    event.type == KeyEventType.KeyDown &&
                                    userInput.isNotBlank() -> {
                                    coroutineScope.launch {
                                        onSendMessage(userInput)
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

                    CompactButton(
                        onClick = onAddWorkspaceContext,
                        modifier = Modifier
                            .size(actionButtonSize)
                            .testTag(UiTestTag.ContextPickerButton.value),
                        tooltip = "Add file or folder context",
                    ) {
                        Text("@", style = MaterialTheme.typography.titleMedium)
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
                            onClick = {
                                coroutineScope.launch {
                                    onSendMessage(userInput)
                                }
                            },
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

                    if (showPttButton) {
                        CompactButton(
                            onClick = {},
                            modifier = Modifier
                                .zIndex(2f)
                                .size(actionButtonSize)
                                .then(
                                    if (pttState == PttState.TRANSCRIBING) {
                                        Modifier
                                    } else {
                                        Modifier.advancedPttGestures(pttEventHandler, coroutineScope)
                                    }
                                )
                                .testTag(UiTestTag.PttButton.value),
                            tooltip = when (pttState) {
                                PttState.IDLE -> LocalTranslation.current.pttButtonTooltip
                                PttState.RECORDING -> LocalTranslation.current.recordingTooltip
                                PttState.TRANSCRIBING -> "Распознавание голоса"
                            },
                        ) {
                            if (pttState == PttState.TRANSCRIBING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                val isRecording = pttState == PttState.RECORDING
                                Icon(
                                    imageVector = if (isRecording) {
                                        Icons.Default.FiberManualRecord
                                    } else {
                                        Icons.Default.Mic
                                    },
                                    contentDescription = if (isRecording) {
                                        LocalTranslation.current.recordingText
                                    } else {
                                        LocalTranslation.current.pushToTalkText
                                    },
                                    tint = if (isRecording) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        LocalContentColor.current
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
