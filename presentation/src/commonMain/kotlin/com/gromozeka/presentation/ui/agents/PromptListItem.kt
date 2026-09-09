package com.gromozeka.presentation.ui.agents

import com.gromozeka.presentation.ui.LocalTranslation
import androidx.compose.foundation.layout.*
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
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
    modifier: Modifier = Modifier
) {
    val translation = LocalTranslation.current
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
                        val typeIcon = when (prompt.type) {
                            is Prompt.Type.Global -> Icons.Default.Public
                            is Prompt.Type.Project -> Icons.Default.Folder
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
                        text = when (prompt.type) {
                            is Prompt.Type.Global -> translation.text("prompts.type.global")
                            is Prompt.Type.Project -> translation.text("prompts.type.project")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row {
                    IconButton(onClick = onView) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = translation.text("prompts.view")
                        )
                    }
                    
                    onEdit?.let { editAction ->
                        IconButton(onClick = editAction) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = translation.text("prompts.edit_idea")
                            )
                        }
                    }
                    
                    onDelete?.let { deleteAction ->
                        IconButton(onClick = deleteAction) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = translation.text("prompts.delete"),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
