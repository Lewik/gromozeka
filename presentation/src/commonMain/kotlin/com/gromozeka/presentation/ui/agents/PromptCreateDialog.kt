package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.PromptTemplate
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

@Composable
fun PromptEditorDialog(
    prompt: Prompt?,
    template: PromptTemplate?,
    onSave: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(prompt?.name ?: template?.name.orEmpty()) }
    var content by remember { mutableStateOf(prompt?.content ?: template?.content.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier.width(760.dp).heightIn(max = 760.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    when {
                        prompt != null -> "Edit prompt"
                        template != null -> "Create prompt from template"
                        else -> "Create prompt"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Prompt content (Markdown)") },
                    minLines = 16,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (name.isBlank() || content.isBlank()) {
                            error = "Name and content are required"
                        } else {
                            onSave(name.trim(), content.trim())
                        }
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
