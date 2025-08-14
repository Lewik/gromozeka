package com.gromozeka.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Modal dialog for renaming a tab
 * 
 * @param isOpen Whether the dialog is open
 * @param currentName Current tab name
 * @param onRename Callback when tab is renamed
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun TabRenameDialog(
    isOpen: Boolean,
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isOpen) {
        var editingText by remember { mutableStateOf(currentName) }
        val focusRequester = remember { FocusRequester() }

        // Auto-focus when dialog opens
        LaunchedEffect(isOpen) {
            if (isOpen) {
                delay(100) // Small delay to ensure TextField is composed
                focusRequester.requestFocus()
            }
        }

        // Reset text when dialog opens
        LaunchedEffect(isOpen, currentName) {
            if (isOpen) {
                editingText = currentName
            }
        }

        fun confirmRename() {
            val newName = editingText.trim()
            val finalName = newName.takeIf { it.isNotBlank() } ?: ""
            onRename(finalName)
            onDismiss()
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier.padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Переименовать таб",
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        label = { Text("Имя таба") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onKeyEvent { event ->
                                when {
                                    event.key == Key.Enter && event.type == KeyEventType.KeyDown -> {
                                        confirmRename()
                                        true
                                    }
                                    event.key == Key.Escape && event.type == KeyEventType.KeyDown -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { confirmRename() }
                        ),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss
                        ) {
                            Text("Отмена")
                        }

                        Button(
                            onClick = { confirmRename() }
                        ) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }
}