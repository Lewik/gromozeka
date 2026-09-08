package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.ui.LocalTranslation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.UiTestTag

@Composable
fun EditMessageDialog(
    messageText: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val localization = LocalTranslation.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localization.text("chat.editMessage.title")) },
        text = {
            Column {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .testTag(UiTestTag.EditMessageInput.value),
                    placeholder = { Text(localization.text("chat.editMessage.placeholder")) },
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(UiTestTag.EditMessageSaveButton.value),
            ) {
                Text(localization.text("saveButton"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localization.text("cancelButton"))
            }
        }
    )
}
