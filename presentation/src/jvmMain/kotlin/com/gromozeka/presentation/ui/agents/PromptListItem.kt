package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Prompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptListItem(
    prompt: Prompt,
    onView: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onCopyToUser: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Type icon
                        val typeIcon = when (prompt.source) {
                            is Prompt.Source.Builtin -> Icons.Default.Lock
                            is Prompt.Source.LocalFile.User -> Icons.Default.Folder
                            is Prompt.Source.LocalFile.ClaudeGlobal -> Icons.Default.CloudQueue
                            is Prompt.Source.LocalFile.ClaudeProject -> Icons.Default.AccountTree
                            is Prompt.Source.LocalFile.Imported -> Icons.Default.Input
                            is Prompt.Source.Remote.Url -> Icons.Default.Link
                            is Prompt.Source.Text -> Icons.Default.Description
                        }
                        
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Text(
                            text = prompt.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = when (val source = prompt.source) {
                            is Prompt.Source.Builtin -> "Built-in: ${source.resourcePath.value}"
                            is Prompt.Source.LocalFile.User -> "User: ${source.path.value}"
                            is Prompt.Source.LocalFile.ClaudeGlobal -> "Claude Global: ${source.path.value}"
                            is Prompt.Source.LocalFile.ClaudeProject -> "Claude Project: ${source.promptPath.value}"
                            is Prompt.Source.LocalFile.Imported -> "Imported: ${source.path.value}"
                            is Prompt.Source.Remote.Url -> "URL: ${source.url}"
                            is Prompt.Source.Text -> "Inline prompt"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row {
                    IconButton(onClick = onView) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = "View prompt"
                        )
                    }
                    
                    onCopyToUser?.let { copyAction ->
                        IconButton(onClick = copyAction) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy to User"
                            )
                        }
                    }
                    
                    onEdit?.let { editAction ->
                        IconButton(onClick = editAction) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit in IDEA"
                            )
                        }
                    }
                    
                    onDelete?.let { deleteAction ->
                        IconButton(onClick = deleteAction) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete prompt",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
