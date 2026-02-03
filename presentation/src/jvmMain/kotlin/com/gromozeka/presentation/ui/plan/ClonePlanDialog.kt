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
 * Dialog for cloning a plan.
 *
 * @param plan plan to clone (null if dialog is closed)
 * @param onConfirm callback when plan is cloned (planId, newName)
 * @param onDismiss callback when dialog is dismissed
 */
@Composable
fun ClonePlanDialog(
    plan: Plan?,
    isSaving: Boolean = false,
    onConfirm: (planId: Plan.Id, newName: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (plan != null) {
        var newName by remember(plan.id) { mutableStateOf("Copy of ${plan.name}") }
        val focusRequester = remember { FocusRequester() }

        // Auto-focus when dialog opens
        LaunchedEffect(plan.id) {
            delay(100)
            focusRequester.requestFocus()
        }

        fun confirmClone() {
            val trimmedName = newName.trim()
            
            if (trimmedName.isNotBlank()) {
                onConfirm(plan.id, trimmedName)
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
                    .width(450.dp)
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Clone Plan",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = "Cloning: ${plan.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New Plan Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { confirmClone() }
                        ),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = { confirmClone() },
                            enabled = newName.trim().isNotBlank()
                        ) {
                            if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Clone")
                        }
                        }
                    }
                }
            }
        }
    }
}
