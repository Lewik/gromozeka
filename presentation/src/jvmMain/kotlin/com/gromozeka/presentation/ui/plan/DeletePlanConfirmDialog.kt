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

/**
 * Confirmation dialog for deleting a plan.
 *
 * @param plan plan to delete (null if dialog is closed)
 * @param onConfirm callback when deletion is confirmed
 * @param onDismiss callback when dialog is dismissed
 */
@Composable
fun DeletePlanConfirmDialog(
    plan: Plan?,
    onConfirm: (planId: Plan.Id) -> Unit,
    onDismiss: () -> Unit
) {
    if (plan != null) {
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
                        text = "Delete Plan?",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = "Are you sure you want to delete \"${plan.name}\"? This will also delete all steps.",
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
                            onClick = { onConfirm(plan.id) },
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
