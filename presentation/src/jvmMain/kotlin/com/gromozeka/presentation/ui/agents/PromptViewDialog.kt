package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gromozeka.domain.model.Prompt
import com.gromozeka.presentation.ui.GromozekaMarkdown

@Composable
fun PromptViewDialog(
    prompt: Prompt,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxSize(0.95f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = prompt.name,
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (val source = prompt.source) {
                        is Prompt.Source.Builtin -> "Built-in prompt"
                        is Prompt.Source.LocalFile.User -> "User prompt: ${source.path.value}"
                        is Prompt.Source.LocalFile.ClaudeGlobal -> "Claude Global: ${source.path.value}"
                        is Prompt.Source.LocalFile.ClaudeProject -> "Claude Project: ${source.promptPath.value}"
                        is Prompt.Source.LocalFile.Imported -> "Imported: ${source.path.value}"
                        is Prompt.Source.Remote.Url -> "URL: ${source.url}"
                        is Prompt.Source.Text -> "Inline prompt"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        GromozekaMarkdown(
                            content = prompt.content,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    onEdit?.let { editAction ->
                        Button(onClick = editAction) {
                            Text("Open in IDEA")
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
