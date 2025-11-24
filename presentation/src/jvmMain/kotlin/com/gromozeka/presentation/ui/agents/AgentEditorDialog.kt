package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Prompt

@Composable
fun AgentEditorDialog(
    agent: Agent? = null,
    prompts: List<Prompt>,
    onSave: (name: String, selectedPrompts: List<Prompt.Id>, description: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(agent?.name ?: "") }
    var description by remember { mutableStateOf(agent?.description ?: "") }
    var selectedPromptIds by remember { mutableStateOf(agent?.prompts ?: emptyList()) }
    var nameError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(800.dp)
                .heightIn(max = 800.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = if (agent == null) "Create New Agent" else "Edit Agent",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = if (it.isBlank()) "Name cannot be empty" else null
                    },
                    label = { Text("Agent Name") },
                    placeholder = { Text("e.g., Code Reviewer, Security Expert") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("What does this agent do?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left panel: Available prompts
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Available Prompts",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(prompts) { prompt ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selectedPromptIds.contains(prompt.id),
                                        onCheckedChange = { checked ->
                                            selectedPromptIds = if (checked) {
                                                selectedPromptIds + prompt.id
                                            } else {
                                                selectedPromptIds.filter { it != prompt.id }
                                            }
                                        }
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = prompt.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )

                                        Text(
                                            text = when (val type = prompt.type) {
                                                is Prompt.Type.Builtin -> "Built-in"
                                                is Prompt.Type.Global -> "Global"
                                                is Prompt.Type.Project -> "Project"
                                                is Prompt.Type.Environment -> "Inline"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right panel: Selected prompts in order
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Selected Prompts (${selectedPromptIds.size})",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (selectedPromptIds.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No prompts selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(selectedPromptIds) { index, promptId ->
                                    val prompt = prompts.find { it.id == promptId }
                                    if (prompt != null) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${index + 1}.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.width(32.dp)
                                                )

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = prompt.name,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }

                                                Column {
                                                    IconButton(
                                                        onClick = {
                                                            if (index > 0) {
                                                                selectedPromptIds = selectedPromptIds.toMutableList().apply {
                                                                    val temp = this[index]
                                                                    this[index] = this[index - 1]
                                                                    this[index - 1] = temp
                                                                }
                                                            }
                                                        },
                                                        enabled = index > 0,
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.KeyboardArrowUp,
                                                            contentDescription = "Move up",
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            if (index < selectedPromptIds.size - 1) {
                                                                selectedPromptIds = selectedPromptIds.toMutableList().apply {
                                                                    val temp = this[index]
                                                                    this[index] = this[index + 1]
                                                                    this[index + 1] = temp
                                                                }
                                                            }
                                                        },
                                                        enabled = index < selectedPromptIds.size - 1,
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "Move down",
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                nameError = "Name cannot be empty"
                                return@Button
                            }

                            onSave(
                                name.trim(),
                                selectedPromptIds,
                                description.trim().takeIf { it.isNotEmpty() }
                            )
                        }
                    ) {
                        Text(if (agent == null) "Create" else "Save")
                    }
                }
            }
        }
    }
}
