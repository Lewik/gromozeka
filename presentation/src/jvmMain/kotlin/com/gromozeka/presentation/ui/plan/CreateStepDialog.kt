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
import com.gromozeka.domain.model.plan.PlanStep
import kotlinx.coroutines.delay

/**
 * Dialog for creating a new step.
 *
 * @param planIdAndParent pair of (planId, parentStepId?) or null if dialog is closed
 * @param availableSteps list of available steps for parent selection
 * @param onConfirm callback when step is created (planId, instruction, parentId)
 * @param onDismiss callback when dialog is dismissed
 */
@Composable
fun CreateStepDialog(
    planIdAndParent: Pair<Plan.Id, PlanStep.Id?>?,
    availableSteps: List<PlanStep>,
    isSaving: Boolean = false,
    onConfirm: (planId: Plan.Id, instruction: String, parentId: PlanStep.Id?) -> Unit,
    onDismiss: () -> Unit
) {
    if (planIdAndParent != null) {
        val (planId, initialParentId) = planIdAndParent
        
        var instruction by remember(planId) { mutableStateOf("") }
        var selectedParentId by remember(planId, initialParentId) { mutableStateOf(initialParentId) }
        var showParentDropdown by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }

        // Auto-focus when dialog opens
        LaunchedEffect(planId) {
            delay(100)
            focusRequester.requestFocus()
        }

        fun confirmCreate() {
            val trimmedInstruction = instruction.trim()
            
            if (trimmedInstruction.isNotBlank()) {
                onConfirm(planId, trimmedInstruction, selectedParentId)
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
                        text = "Add Step",
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = { Text("Instruction") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { confirmCreate() }
                        ),
                        maxLines = 8
                    )

                    // Parent step dropdown
                    if (availableSteps.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = showParentDropdown,
                            onExpandedChange = { showParentDropdown = it }
                        ) {
                            OutlinedTextField(
                                value = selectedParentId?.let { parentId ->
                                    availableSteps.find { it.id == parentId }?.let { step ->
                                        when (step) {
                                            is PlanStep.Text -> step.instruction.take(50)
                                        }
                                    } ?: "None"
                                } ?: "None",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Parent Step (optional)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showParentDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = showParentDropdown,
                                onDismissRequest = { showParentDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None") },
                                    onClick = {
                                        selectedParentId = null
                                        showParentDropdown = false
                                    }
                                )
                                
                                availableSteps.forEach { step ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (step) {
                                                    is PlanStep.Text -> step.instruction.take(50)
                                                }
                                            )
                                        },
                                        onClick = {
                                            selectedParentId = step.id
                                            showParentDropdown = false
                                        }
                                    )
                                }
                            }
                        }
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
                            enabled = instruction.trim().isNotBlank()
                        ) {
                            if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Add")
                        }
                        }
                    }
                }
            }
        }
    }
}
