package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun PromptCreateDialog(
    onSave: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var contentError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(700.dp)
                .heightIn(max = 700.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Create Inline Prompt",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = if (it.isBlank()) "Name cannot be empty" else null
                    },
                    label = { Text("Prompt Name") },
                    placeholder = { Text("e.g., Code Review Instructions") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        contentError = if (it.isBlank()) "Content cannot be empty" else null
                    },
                    label = { Text("Prompt Content (Markdown)") },
                    placeholder = { Text("Enter prompt text in markdown format...") },
                    isError = contentError != null,
                    supportingText = contentError?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    minLines = 10
                )

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
                            
                            if (content.isBlank()) {
                                contentError = "Content cannot be empty"
                                return@Button
                            }

                            onSave(name.trim(), content.trim())
                        }
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
