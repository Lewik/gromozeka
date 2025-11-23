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
                        val typeIcon = when (val type = prompt.type) {
                            is Prompt.Type.Builtin -> Icons.Default.Lock
                            is Prompt.Type.Global -> Icons.Default.Home
                            is Prompt.Type.Project -> Icons.Default.Folder
                            is Prompt.Type.Inline -> Icons.Default.Description
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
                        text = when (val type = prompt.type) {
                            is Prompt.Type.Builtin -> "Built-in prompt"
                            is Prompt.Type.Global -> "Global prompt"
                            is Prompt.Type.Project -> "Project prompt"
                            is Prompt.Type.Inline -> "Inline prompt"
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
