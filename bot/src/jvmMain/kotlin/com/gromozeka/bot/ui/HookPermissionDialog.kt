package com.gromozeka.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.bot.model.ClaudeHookPayload
import com.gromozeka.bot.model.HookDecision

/**
 * Modal dialog for Claude Code CLI hook permission requests
 * Using official Anthropic format
 *
 * @param hookPayload Current Claude hook payload (null = dialog closed)
 * @param onDecision Callback when user makes a decision
 * @param onDismiss Callback when dialog is dismissed without decision
 */
@Composable
fun ClaudeHookPermissionDialog(
    hookPayload: ClaudeHookPayload?,
    onDecision: (HookDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    if (hookPayload != null) {
        var customReason by remember { mutableStateOf("") }

        // Reset reason when new request appears
        LaunchedEffect(hookPayload.session_id) {
            customReason = ""
        }

        fun makeDecision(allow: Boolean, reason: String = "") {
            onDecision(HookDecision(allow, reason))
        }

        Dialog(
            onDismissRequest = {
                makeDecision(false, "User dismissed dialog")
                onDismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false, // Don't allow accidental dismissal
                usePlatformDefaultWidth = false
            )
        ) {
            Card(
                modifier = Modifier
                    .width(500.dp)
                    .padding(16.dp)
                    .onKeyEvent { event ->
                        when {
                            event.key == Key.Enter && event.type == KeyEventType.KeyDown -> {
                                makeDecision(true, customReason)
                                onDismiss()
                                true
                            }

                            event.key == Key.Escape && event.type == KeyEventType.KeyDown -> {
                                makeDecision(false, "User pressed Escape")
                                onDismiss()
                                true
                            }

                            else -> false
                        }
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header with security icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Hook Permission Request",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider()

                    // Warning message
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Claude Code hook is requesting permission to execute a tool. Review the details below before deciding.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Tool information
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Tool: ${hookPayload.tool_name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                text = "Session: ${hookPayload.session_id}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            hookPayload.cwd?.let { workingDir ->
                                Text(
                                    text = "Working Directory: $workingDir",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (hookPayload.tool_input.isNotEmpty()) {
                                Text(
                                    text = "Parameters:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        hookPayload.tool_input.forEach { (key, value) ->
                                            Row {
                                                Text(
                                                    text = "$key: ",
                                                    fontFamily = FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = value.toString(),
                                                    fontFamily = FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Session context information removed - not available in new API

                    // Custom reason field
                    OutlinedTextField(
                        value = customReason,
                        onValueChange = { customReason = it },
                        label = { Text("Reason (optional)") },
                        placeholder = { Text("Why are you allowing/denying this request?") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )

                    HorizontalDivider()

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        Button(
                            onClick = {
                                makeDecision(false, customReason.takeIf { it.isNotBlank() } ?: "User denied")
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text("Deny")
                        }

                        Button(
                            onClick = {
                                makeDecision(true, customReason.takeIf { it.isNotBlank() } ?: "User approved")
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("Allow")
                        }
                    }

                    // Timeout indicator
                    Text(
                        text = "This request will timeout in 30 seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}