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
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import com.gromozeka.shared.domain.message.ToolCallData

@Composable
fun ToolCallItem(
    toolCall: ToolCallData,
    toolResult: ChatMessage.ContentItem.ToolResult?,
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Determine status icon based on toolResult
    val statusIcon = when {
        toolResult == null -> Icons.Default.Schedule // No result yet - in progress
        toolResult.isError -> Icons.Default.Error // Error
        else -> Icons.Default.CheckCircle // Success
    }

    // Get tool name for display
    val toolName = when (toolCall) {
        is ClaudeCodeToolCallData.Read -> "Read"
        is ClaudeCodeToolCallData.Edit -> "Edit"
        is ClaudeCodeToolCallData.Bash -> "Bash"
        is ClaudeCodeToolCallData.Grep -> "Grep"
        is ClaudeCodeToolCallData.TodoWrite -> "TodoWrite"
        is ClaudeCodeToolCallData.WebSearch -> "WebSearch"
        is ClaudeCodeToolCallData.WebFetch -> "WebFetch"
        is ClaudeCodeToolCallData.Task -> "Task"
        is ToolCallData.Generic -> toolCall.name
    }

    // Get tool description for display (short version for button)
    val toolDescription = when (toolCall) {
        is ClaudeCodeToolCallData.Read -> toolCall.filePath
        is ClaudeCodeToolCallData.Edit -> toolCall.filePath
        is ClaudeCodeToolCallData.Bash -> toolCall.command.take(30) + if (toolCall.command.length > 30) "..." else ""
        is ClaudeCodeToolCallData.Grep -> toolCall.pattern.take(25) + if (toolCall.pattern.length > 25) "..." else ""
        is ClaudeCodeToolCallData.TodoWrite -> "todo list"
        is ClaudeCodeToolCallData.WebSearch -> toolCall.query.take(30) + if (toolCall.query.length > 30) "..." else ""
        is ClaudeCodeToolCallData.WebFetch -> toolCall.url.take(40) + if (toolCall.url.length > 40) "..." else ""
        is ClaudeCodeToolCallData.Task -> toolCall.description.take(35) + if (toolCall.description.length > 35) "..." else ""
        is ToolCallData.Generic -> toolCall.name
    }

    // Get full description for expanded view
    val fullToolDescription = when (toolCall) {
        is ClaudeCodeToolCallData.Read -> "Read file ${toolCall.filePath}"
        is ClaudeCodeToolCallData.Edit -> "Edit file ${toolCall.filePath}"
        is ClaudeCodeToolCallData.Bash -> "Execute: ${toolCall.command}"
        is ClaudeCodeToolCallData.Grep -> "Search: ${toolCall.pattern}"
        is ClaudeCodeToolCallData.TodoWrite -> "Update todo list"
        is ClaudeCodeToolCallData.WebSearch -> "Search: ${toolCall.query}"
        is ClaudeCodeToolCallData.WebFetch -> "Fetch: ${toolCall.url}"
        is ClaudeCodeToolCallData.Task -> "Task: ${toolCall.description}"
        is ToolCallData.Generic -> "Tool: ${toolCall.name}"
    }

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
                                        is ChatMessage.ContentItem.ToolResult.Data.Text -> {
                                            Text(
                                                text = dataItem.content,
                                                modifier = Modifier.fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        is ChatMessage.ContentItem.ToolResult.Data.Base64Data -> {
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

                                        is ChatMessage.ContentItem.ToolResult.Data.UrlData -> {
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

                                        is ChatMessage.ContentItem.ToolResult.Data.FileData -> {
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