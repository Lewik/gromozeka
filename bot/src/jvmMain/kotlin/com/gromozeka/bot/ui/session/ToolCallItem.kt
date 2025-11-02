package com.gromozeka.bot.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.ui.CompactButton
import com.gromozeka.shared.domain.Conversation

@Composable
fun ToolCallItem(
    toolCall: Conversation.Message.ContentItem.ToolCall.Data,
    toolResult: Conversation.Message.ContentItem.ToolResult?,
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Determine status icon based on toolResult
    val statusIcon = when {
        toolResult == null -> Icons.Default.Schedule // No result yet - in progress
        toolResult.isError -> Icons.Default.Error // Error
        else -> Icons.Default.CheckCircle // Success
    }

    // Get tool name and descriptions for display
    val toolName = toolCall.name
    val toolDescription = toolName
    val fullToolDescription = "Tool: $toolName"

    Column {
        // Enhanced tool call button with status + name + description
        DisableSelection {
            CompactButton(
                onClick = {
                    if (toolResult != null) {
                        isExpanded = !isExpanded
                    }
                },
                modifier = Modifier.Companion,
                enabled = toolResult != null,
                tooltip = when {
                    toolResult == null -> "Выполняется..."
                    toolResult.isError -> "Ошибка - клик для просмотра"
                    else -> "Успешно - клик для просмотра результата"
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status icon
                    Icon(
                        statusIcon,
                        contentDescription = "Tool status"
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // Tool name and description
                    Text("$toolName: $toolDescription")
                }
            }
        }

        // Animated expandable result content
        AnimatedVisibility(
            visible = isExpanded && toolResult != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            toolResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Show full command/description
                        Text(
                            text = fullToolDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Show result content - now it's a list of Data items
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                result.result.forEach { dataItem ->
                                    when (dataItem) {
                                        is Conversation.Message.ContentItem.ToolResult.Data.Text -> {
                                            Text(
                                                text = dataItem.content,
                                                modifier = Modifier.fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> {
                                            when {
                                                dataItem.mediaType.type == "image" -> {
                                                    // Base64 image - show placeholder with truncation
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Image,
                                                            contentDescription = "Image"
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "[Image ${dataItem.mediaType.value} - ${dataItem.data.length} chars Base64]",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }

                                                else -> {
                                                    // Non-image Base64 data - show truncated version
                                                    val truncated = if (dataItem.data.length > 100) {
                                                        "${dataItem.data.take(50)}...[${dataItem.data.length - 100} chars]...${
                                                            dataItem.data.takeLast(
                                                                50
                                                            )
                                                        }"
                                                    } else {
                                                        dataItem.data
                                                    }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Description,
                                                            contentDescription = "Document"
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "[${dataItem.mediaType.value}] $truncated",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    Icons.Default.Link,
                                                    contentDescription = "URL"
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${dataItem.url}${dataItem.mediaType?.let { " (${it.value})" } ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        is Conversation.Message.ContentItem.ToolResult.Data.FileData -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    Icons.Default.Folder,
                                                    contentDescription = "File"
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "File: ${dataItem.fileId}${dataItem.mediaType?.let { " (${it.value})" } ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
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
    }
}