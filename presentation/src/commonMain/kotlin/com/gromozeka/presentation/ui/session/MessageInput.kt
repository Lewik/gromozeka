package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardHide
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.presentation.services.PttEventHandler
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
    isRecording: Boolean,
    showPttButton: Boolean,
    pttStatusMessage: String? = null,
    contextPercentage: Int? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var inputFocused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = onUserInputChange,
                modifier = Modifier
                    .onFocusChanged { inputFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        when {
                            // Shift+Enter для отправки сообщения
                            event.key == Key.Enter && event.isShiftPressed && event.type == KeyEventType.KeyDown && userInput.isNotBlank() -> {
                                coroutineScope.launch {
                                    onSendMessage(userInput)
                                }
                                true
                            }

                            // Блокируем символ § - используется для PTT хоткея
                            event.utf16CodePoint == 167 -> true // § параграф

                            else -> false
                        }
                    }
                    .weight(1f)
                    .testTag(UiTestTag.MessageInput.value),
                placeholder = { Text("") }
            )
            Spacer(modifier = Modifier.width(4.dp))

            if (inputFocused) {
                CompactButton(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                    },
                    modifier = Modifier.fillMaxHeight(),
                    tooltip = "Hide keyboard"
                ) {
                    Icon(Icons.Default.KeyboardHide, contentDescription = "Hide keyboard")
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Send button with queue badge
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
                }
            ) {
                CompactButton(
                    onClick = {
                        coroutineScope.launch {
                            onSendMessage(userInput)
                        }
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .testTag(UiTestTag.SendButton.value),
                    tooltip = when {
                        isWaitingForResponse && pendingMessagesCount > 0 -> "Добавить в очередь ($pendingMessagesCount уже ждёт)"
                        isWaitingForResponse -> "Добавить сообщение в очередь"
                        contextPercentage != null && contextPercentage >= 90 -> "${LocalTranslation.current.sendMessageTooltip} (критическое заполнение $contextPercentage%)"
                        contextPercentage != null && contextPercentage >= 75 -> "${LocalTranslation.current.sendMessageTooltip} (контекст заполнен на $contextPercentage%)"
                        else -> LocalTranslation.current.sendMessageTooltip
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = when {
                            contextPercentage != null && contextPercentage >= 90 -> MaterialTheme.colorScheme.error
                            contextPercentage != null && contextPercentage >= 75 -> MaterialTheme.colorScheme.tertiary
                            else -> LocalContentColor.current
                        }
                    )
                }
            }

            // PTT button - only show if STT is enabled
            if (showPttButton) {
                Spacer(modifier = Modifier.width(4.dp))

                CompactButton(
                    onClick = {},
                    modifier = Modifier
                        .zIndex(2f)
                        .fillMaxHeight()
                        .advancedPttGestures(pttEventHandler, coroutineScope)
                        .testTag(UiTestTag.PttButton.value),
                    tooltip = if (isRecording) LocalTranslation.current.recordingTooltip else LocalTranslation.current.pttButtonTooltip
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Mic,
                        contentDescription = if (isRecording) LocalTranslation.current.recordingText else LocalTranslation.current.pushToTalkText,
                        tint = if (isRecording) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            }
        }

        pttStatusMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
