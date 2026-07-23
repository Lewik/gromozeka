package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.presentation.ui.GromozekaMarkdown
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

@Composable
fun AgentSkillCreateDialog(
    onSave: (name: String, description: String, instructions: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(720.dp)
                .heightIn(max = 760.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Create Agent Skill", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Name") },
                    placeholder = { Text("lowercase-hyphenated-name") },
                    supportingText = { Text("Must match the Agent Skills package directory name.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        error = null
                    },
                    label = { Text("Description") },
                    supportingText = { Text("The compact catalog uses this text to decide when to activate the skill.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = {
                        instructions = it
                        error = null
                    },
                    label = { Text("SKILL.md instructions") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    minLines = 8,
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank() || description.isBlank() || instructions.isBlank()) {
                                error = "Name, description, and instructions are required"
                            } else {
                                onSave(name.trim(), description.trim(), instructions.trim())
                            }
                        },
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}

@Composable
fun AgentSkillDetailsDialog(
    skill: AgentSkill,
    onDismiss: () -> Unit,
) {
    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(720.dp)
                .heightIn(max = 760.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(skill.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                skill.compatibility?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Compatibility: $it", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                GromozekaMarkdown(
                    content = skill.instructions,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
