package com.gromozeka.presentation.ui.plan

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep

/**
 * Confirmation dialog for deleting a step.
 *
 * @param step step to delete (null if dialog is closed)
 * @param onConfirm callback when deletion is confirmed (stepId, planId)
 * @param onDismiss callback when dialog is dismissed
 */
@Composable
fun DeleteStepConfirmDialog(
    step: PlanStep?,
    onConfirm: (stepId: PlanStep.Id, planId: Plan.Id) -> Unit,
    onDismiss: () -> Unit
) {
    if (step != null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .width(400.dp)
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Delete Step?",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = when (step) {
                            is PlanStep.Text -> "Are you sure you want to delete this step? \"${step.instruction.take(100)}...\""
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = { onConfirm(step.id, step.planId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
