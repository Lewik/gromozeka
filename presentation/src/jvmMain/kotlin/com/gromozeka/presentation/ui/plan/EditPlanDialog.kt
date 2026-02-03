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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.domain.model.plan.Plan
import kotlinx.coroutines.delay

/**
 * Dialog for editing an existing plan.
 *
 * @param plan plan to edit (null if dialog is closed)
 * @param onConfirm callback when plan is updated (planId, name, description, isTemplate)
 * @param onDismiss callback when dialog is dismissed
 */
@Composable
fun EditPlanDialog(
    plan: Plan?,
    isSaving: Boolean = false,
    onConfirm: (planId: Plan.Id, name: String, description: String, isTemplate: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (plan != null) {
        var name by remember(plan.id) { mutableStateOf(plan.name) }
        var description by remember(plan.id) { mutableStateOf(plan.description) }
        var isTemplate by remember(plan.id) { mutableStateOf(plan.isTemplate) }
        val focusRequester = remember { FocusRequester() }

        // Auto-focus when dialog opens
        LaunchedEffect(plan.id) {
            delay(100)
            focusRequester.requestFocus()
        }

        fun confirmEdit() {
            val trimmedName = name.trim()
            val trimmedDescription = description.trim()
            
            if (trimmedName.isNotBlank()) {
                onConfirm(plan.id, trimmedName, trimmedDescription, isTemplate)
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
                        text = "Edit Plan",
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
                            onDone = { confirmEdit() }
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
                            onClick = { confirmEdit() },
                            enabled = name.trim().isNotBlank()
                        ) {
                            if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save")
                        }
                        }
                    }
                }
            }
        }
    }
}
