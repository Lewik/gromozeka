package com.gromozeka.bot.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.bot.ui.CompactButton
import com.gromozeka.bot.ui.LocalTranslation
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
    modifierWithPushToTalk: Modifier,
    isRecording: Boolean,
    showPttButton: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
    ) {
        OutlinedTextField(
            value = userInput,
            onValueChange = onUserInputChange,
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    
                    when {
                        // Shift+Enter для отправки сообщения
                        event.key == Key.Enter && event.isShiftPressed && event.type == KeyEventType.KeyDown && userInput.isNotBlank() -> {
                            coroutineScope.launch {
                                onSendMessage(userInput)
                            }
                            true
                        }
                        
                        
                        else -> false
                    }
                }
                .weight(1f),
            placeholder = { Text("") }
        )
        Spacer(modifier = Modifier.width(4.dp))

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
                modifier = Modifier.fillMaxHeight(),
                tooltip = if (isWaitingForResponse && pendingMessagesCount > 0) {
                    "Отправляется... ($pendingMessagesCount в очереди)"
                } else if (isWaitingForResponse) {
                    LocalTranslation.current.sendingMessageTooltip
                } else {
                    LocalTranslation.current.sendMessageTooltip
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }

        // PTT button - only show if STT is enabled
        if (showPttButton) {
            Spacer(modifier = Modifier.width(4.dp))

            CompactButton(
                onClick = {},
                modifier = modifierWithPushToTalk.fillMaxHeight(),
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
}