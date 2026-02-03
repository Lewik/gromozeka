package com.gromozeka.presentation.ui.plan

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
 * Dialog for creating a new plan.
 *
 * @param isOpen whether the dialog is open
 * @param isSaving whether save operation is in progress
 * @param onConfirm callback when plan is created (name, description, isTemplate)
 * @param onDismiss callback when dialog is dismissed
 */
@Composable
fun CreatePlanDialog(
    isOpen: Boolean,
    isSaving: Boolean = false,
    onConfirm: (name: String, description: String, isTemplate: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (isOpen) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var isTemplate by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }

        // Auto-focus when dialog opens
        LaunchedEffect(isOpen) {
            if (isOpen) {
                delay(100)
                focusRequester.requestFocus()
            }
        }

        // Reset fields when dialog opens
        LaunchedEffect(isOpen) {
            if (isOpen) {
                name = ""
                description = ""
                isTemplate = false
            }
        }

        fun confirmCreate() {
            val trimmedName = name.trim()
            val trimmedDescription = description.trim()
            
            if (trimmedName.isNotBlank()) {
                onConfirm(trimmedName, trimmedDescription, isTemplate)
            }
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .width(500.dp)
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Create New Plan",
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Plan Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { confirmCreate() }
                        ),
                        maxLines = 5
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isTemplate,
                            onCheckedChange = { isTemplate = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save as template")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = { confirmCreate() },
                            enabled = name.trim().isNotBlank() && !isSaving
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Create")
                            }
                        }
                    }
                }
            }
        }
    }
}
