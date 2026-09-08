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
import com.gromozeka.domain.model.AgentDefinition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListItem(
    agent: AgentDefinition,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onSetDefault: (() -> Unit)?,
    onDelete: () -> Unit,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        when (agent.type) {
                            is AgentDefinition.Type.Global -> {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = {},
                                    label = { Text(if (isDefault) translation.text("agents.scope.global_default") else translation.text("agents.scope.global")) },
                                    enabled = false
                                )
                            }
                            is AgentDefinition.Type.Project -> {
                                // No chip for project agents
                            }
                        }
                    }
                    
                    agent.description?.let { desc ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = translation.text("agents.duplicate"))
                    }
                    onSetDefault?.let { action ->
                        IconButton(onClick = action, enabled = !isDefault) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (isDefault) translation.text("agents.default_agent") else translation.text("agents.set_default"),
                                tint = if (isDefault) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = translation.text("agents.edit"))
                    }
                    IconButton(onClick = onDelete, enabled = !isDefault) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = translation.text("agents.delete"),
                            tint = if (isDefault) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = translation.plural("agents.prompt_count", agent.prompts.size.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
