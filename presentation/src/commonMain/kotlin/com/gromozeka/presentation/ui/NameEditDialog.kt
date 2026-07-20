package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog
import kotlinx.coroutines.delay

@Composable
fun NameEditDialog(
    isOpen: Boolean,
    currentName: String,
    title: String,
    label: String,
    maxLength: Int? = null,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isOpen) return

    var editingText by remember(isOpen, currentName) { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isOpen) {
        delay(100)
        focusRequester.requestFocus()
    }

    fun confirmRename() {
        onRename(editingText.trim())
        onDismiss()
    }

    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = editingText,
                    onValueChange = { value ->
                        if (maxLength == null || value.length <= maxLength) {
                            editingText = value
                        }
                    },
                    label = { Text(label) },
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
                    keyboardActions = KeyboardActions(onDone = { confirmRename() }),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(LocalTranslation.current.cancelButton)
                    }
                    Button(onClick = { confirmRename() }) {
                        Text(LocalTranslation.current.saveButton)
                    }
                }
            }
        }
    }
}
