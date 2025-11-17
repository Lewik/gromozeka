package com.gromozeka.bot.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.ui.CompactButton
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.*
import java.io.File

private fun formatPath(path: String, projectPath: String): String {
    val file = File(path)
    val absolutePath = if (file.isAbsolute) path else File(projectPath, path).absolutePath
    
    return if (absolutePath.startsWith(projectPath)) {
        absolutePath.removePrefix(projectPath).removePrefix("/")
    } else {
        absolutePath
    }
}

private fun truncateText(text: String, toolName: String, maxLines: Int = 5): String {
    if (toolName == "grz_execute_command") {
        return text
    }
    
    val lines = text.lines()
    return if (lines.size > maxLines * 2) {
        val firstLines = lines.take(maxLines)
        val lastLines = lines.takeLast(maxLines)
        val omittedCount = lines.size - (maxLines * 2)
        
        firstLines.joinToString("\n") + 
        "\n\n... ($omittedCount lines omitted) ...\n\n" + 
        lastLines.joinToString("\n")
    } else {
        text
    }
}

private fun buildDetailedParameters(toolName: String, input: JsonElement, projectPath: String): String {
    return try {
        val json = input.jsonObject
        when (toolName) {
            "grz_read_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: ""
                "File: ${formatPath(path, projectPath)}"
            }
            "grz_write_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: ""
                val content = json["content"]?.jsonPrimitive?.content ?: ""
                val size = (content.length / 1024.0).let { 
                    if (it < 1) "${content.length} bytes" 
                    else "%.1f KB".format(it) 
                }
                "File: ${formatPath(path, projectPath)}\nSize: $size"
            }
            "grz_edit_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: ""
                val oldString = json["old_string"]?.jsonPrimitive?.content ?: ""
                val newString = json["new_string"]?.jsonPrimitive?.content ?: ""
                val replaceAll = json["replace_all"]?.jsonPrimitive?.boolean ?: false
                
                buildString {
                    append("File: ${formatPath(path, projectPath)}\n")
                    append("Replace: \"$oldString\" → \"$newString\"\n")
                    append("Mode: ${if (replaceAll) "Replace all occurrences" else "Replace first occurrence"}")
                }
            }
            "grz_execute_command" -> {
                val command = json["command"]?.jsonPrimitive?.content ?: ""
                val workingDir = json["working_directory"]?.jsonPrimitive?.content
                val timeout = json["timeout_seconds"]?.jsonPrimitive?.longOrNull
                
                buildString {
                    append("Command: $command\n")
                    workingDir?.let { append("Working directory: ${formatPath(it, projectPath)}\n") }
                    timeout?.let { append("Timeout: ${it}s") }
                }
            }
            "brave_web_search", "brave_local_search" -> {
                val query = json["query"]?.jsonPrimitive?.content ?: ""
                val count = json["count"]?.jsonPrimitive?.intOrNull
                "Query: $query${count?.let { "\nResults limit: $it" } ?: ""}"
            }
            "jina_read_url" -> {
                val url = json["url"]?.jsonPrimitive?.content ?: ""
                "URL: $url"
            }
            "unified_search" -> {
                val query = json["query"]?.jsonPrimitive?.content ?: ""
                val searchVector = json["search_vector"]?.jsonPrimitive?.booleanOrNull ?: true
                val searchGraph = json["search_graph"]?.jsonPrimitive?.booleanOrNull ?: true
                val limit = json["limit"]?.jsonPrimitive?.intOrNull ?: 5
                
                buildString {
                    append("Query: $query\n")
                    append("Sources: ")
                    val sources = mutableListOf<String>()
                    if (searchVector) sources.add("conversation history")
                    if (searchGraph) sources.add("knowledge graph")
                    append(sources.joinToString(", "))
                    append("\nLimit: $limit results")
                }
            }
            "build_memory_from_text" -> {
                val content = json["content"]?.jsonPrimitive?.content ?: ""
                "Content length: ${content.length} characters"
            }
            "add_memory_link" -> {
                val from = json["from"]?.jsonPrimitive?.content ?: ""
                val relation = json["relation"]?.jsonPrimitive?.content ?: ""
                val to = json["to"]?.jsonPrimitive?.content ?: ""
                val summary = json["summary"]?.jsonPrimitive?.content
                
                buildString {
                    append("$from → $relation → $to")
                    summary?.let { append("\nSummary: $it") }
                }
            }
            "get_memory_object", "update_memory_object", "delete_memory_object" -> {
                val name = json["name"]?.jsonPrimitive?.content ?: ""
                "Entity: $name"
            }
            "invalidate_memory_link" -> {
                val from = json["from"]?.jsonPrimitive?.content ?: ""
                val relation = json["relation"]?.jsonPrimitive?.content ?: ""
                val to = json["to"]?.jsonPrimitive?.content ?: ""
                "$from → $relation → $to"
            }
            "mcp__gromozeka__create_agent" -> {
                val agentName = json["agent_name"]?.jsonPrimitive?.content ?: ""
                val agentProjectPath = json["project_path"]?.jsonPrimitive?.content ?: ""
                val initialMessage = json["initial_message"]?.jsonPrimitive?.content
                
                buildString {
                    append("Agent: $agentName\n")
                    append("Project: ${formatPath(agentProjectPath, projectPath)}")
                    initialMessage?.let { append("\nInitial message: ${it.take(100)}${if (it.length > 100) "..." else ""}") }
                }
            }
            "mcp__gromozeka__tell_agent" -> {
                val message = json["message"]?.jsonPrimitive?.content ?: ""
                val targetTabId = json["target_tab_id"]?.jsonPrimitive?.content
                
                buildString {
                    targetTabId?.let { append("Target: Tab #${it.take(8)}\n") }
                    append("Message: $message")
                }
            }
            "mcp__gromozeka__switch_tab" -> {
                val tabId = json["tab_id"]?.jsonPrimitive?.content ?: ""
                "Tab ID: $tabId"
            }
            "mcp__gromozeka__list_contexts" -> {
                val contextProjectPath = json["project_path"]?.jsonPrimitive?.content
                contextProjectPath?.let { "Project: ${formatPath(it, projectPath)}" } ?: "All contexts"
            }
            "mcp__gromozeka__delete_context" -> {
                val contextName = json["context_name"]?.jsonPrimitive?.content ?: ""
                "Context: $contextName"
            }
            "mcp__gromozeka__save_contexts" -> {
                "Saving extracted contexts"
            }
            else -> json.toString()
        }
    } catch (e: Exception) {
        "Error parsing parameters: ${e.message}"
    }
}

private fun getToolDisplayName(toolName: String): String = when (toolName) {
    "grz_read_file" -> "Read File"
    "grz_write_file" -> "Write File"
    "grz_edit_file" -> "Edit File"
    "grz_execute_command" -> "Execute Command"
    "brave_web_search" -> "Web Search"
    "brave_local_search" -> "Local Search"
    "jina_read_url" -> "Read URL"
    "unified_search" -> "Memory Search"
    "build_memory_from_text" -> "Update Memory From Text"
    "add_memory_link" -> "Add Memory Link"
    "get_memory_object" -> "Get Memory Object"
    "update_memory_object" -> "Update Memory Object"
    "invalidate_memory_link" -> "Invalidate Memory Link"
    "delete_memory_object" -> "Delete Memory Object"
    "mcp__gromozeka__create_agent" -> "Create Agent"
    "mcp__gromozeka__tell_agent" -> "Tell Agent"
    "mcp__gromozeka__switch_tab" -> "Switch Tab"
    "mcp__gromozeka__list_tabs" -> "List Tabs"
    "mcp__gromozeka__list_contexts" -> "List Contexts"
    "mcp__gromozeka__extract_contexts" -> "Extract Contexts"
    "mcp__gromozeka__save_contexts" -> "Save Contexts"
    "mcp__gromozeka__delete_context" -> "Delete Context"
    "mcp__gromozeka__hello_world" -> "Test"
    else -> toolName
}

private fun getToolIcon(toolName: String): ImageVector = when (toolName) {
    "grz_read_file" -> Icons.Default.Description
    "grz_write_file" -> Icons.Default.Description
    "grz_edit_file" -> Icons.Default.Description
    "grz_execute_command" -> Icons.Default.Terminal
    "brave_web_search" -> Icons.Default.Public
    "brave_local_search" -> Icons.Default.Public
    "jina_read_url" -> Icons.Default.Public
    "unified_search" -> Icons.Default.Psychology
    "build_memory_from_text" -> Icons.Default.Psychology
    "add_memory_link" -> Icons.Default.Psychology
    "get_memory_object" -> Icons.Default.Psychology
    "update_memory_object" -> Icons.Default.Psychology
    "invalidate_memory_link" -> Icons.Default.Psychology
    "delete_memory_object" -> Icons.Default.Psychology
    "mcp__gromozeka__create_agent" -> Icons.Default.DeveloperBoard
    "mcp__gromozeka__tell_agent" -> Icons.Default.DeveloperBoard
    "mcp__gromozeka__switch_tab" -> Icons.Default.Tab
    "mcp__gromozeka__list_tabs" -> Icons.Default.ViewList
    "mcp__gromozeka__list_contexts" -> Icons.Default.ListAlt
    "mcp__gromozeka__extract_contexts" -> Icons.Default.AutoFixHigh
    "mcp__gromozeka__save_contexts" -> Icons.Default.Save
    "mcp__gromozeka__delete_context" -> Icons.Default.DeleteOutline
    "mcp__gromozeka__hello_world" -> Icons.Default.BugReport
    else -> Icons.Default.Build
}

private fun getToolSecondaryIcon(toolName: String): ImageVector? = when (toolName) {
    "grz_read_file" -> Icons.Default.ArrowForward
    "grz_write_file" -> Icons.Default.ArrowBack
    "grz_edit_file" -> Icons.Default.ArrowBack
    "brave_web_search" -> Icons.Default.Search
    "brave_local_search" -> Icons.Default.Search
    "jina_read_url" -> Icons.Default.ArrowForward
    "unified_search" -> Icons.Default.Search
    "build_memory_from_text" -> Icons.Default.Description
    "add_memory_link" -> Icons.Default.Link
    "get_memory_object" -> Icons.Default.ArrowForward
    "update_memory_object" -> Icons.Default.ArrowBack
    "invalidate_memory_link" -> Icons.Default.LinkOff
    "delete_memory_object" -> Icons.Default.Close
    "mcp__gromozeka__create_agent" -> Icons.Default.Add
    "mcp__gromozeka__tell_agent" -> Icons.Default.ArrowBack
    else -> null
}

sealed class ToolAction {
    data class OpenInIde(val path: String, val line: Int? = null) : ToolAction()
    data class OpenUrl(val url: String) : ToolAction()
}

private fun getToolAction(toolName: String, input: JsonElement, projectPath: String): ToolAction? {
    return try {
        val json = input.jsonObject
        when (toolName) {
            "grz_read_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: return null
                val offset = json["offset"]?.jsonPrimitive?.intOrNull
                val absolutePath = File(path).let { if (it.isAbsolute) path else File(projectPath, path).absolutePath }
                ToolAction.OpenInIde(absolutePath, offset?.plus(1))
            }
            "grz_write_file", "grz_edit_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: return null
                val absolutePath = File(path).let { if (it.isAbsolute) path else File(projectPath, path).absolutePath }
                ToolAction.OpenInIde(absolutePath)
            }
            "jina_read_url" -> {
                val url = json["url"]?.jsonPrimitive?.content ?: return null
                ToolAction.OpenUrl(url)
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private fun executeToolAction(action: ToolAction) {
    try {
        when (action) {
            is ToolAction.OpenInIde -> {
                val command = if (action.line != null) {
                    arrayOf("idea", "--line", action.line.toString(), action.path)
                } else {
                    arrayOf("idea", action.path)
                }
                ProcessBuilder(*command).start()
            }
            is ToolAction.OpenUrl -> {
                ProcessBuilder("open", action.url).start()
            }
        }
    } catch (e: Exception) {
        // Silently fail - user will notice if nothing opens
    }
}

private fun extractKeyParameters(toolName: String, input: JsonElement, projectPath: String): String {
    return try {
        val json = input.jsonObject
        when (toolName) {
            "grz_read_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: ""
                formatPath(path, projectPath)
            }
            "grz_write_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: ""
                val content = json["content"]?.jsonPrimitive?.content ?: ""
                val size = (content.length / 1024.0).let { if (it < 1) "${content.length} B" else "%.1f KB".format(it) }
                "${formatPath(path, projectPath)} ($size)"
            }
            "grz_edit_file" -> {
                val path = json["file_path"]?.jsonPrimitive?.content ?: ""
                val replaceAll = json["replace_all"]?.jsonPrimitive?.boolean ?: false
                "${formatPath(path, projectPath)}${if (replaceAll) " (replace all)" else ""}"
            }
            "grz_execute_command" -> {
                val command = json["command"]?.jsonPrimitive?.content ?: ""
                if (command.length > 60) command.take(57) + "..." else command
            }
            "brave_web_search", "brave_local_search" -> {
                json["query"]?.jsonPrimitive?.content ?: ""
            }
            "jina_read_url" -> {
                val url = json["url"]?.jsonPrimitive?.content ?: ""
                if (url.length > 50) url.take(47) + "..." else url
            }
            "unified_search" -> {
                json["query"]?.jsonPrimitive?.content ?: ""
            }
            "build_memory_from_text" -> {
                val content = json["content"]?.jsonPrimitive?.content ?: ""
                if (content.length > 40) content.take(37) + "..." else content
            }
            "add_memory_link" -> {
                val from = json["from"]?.jsonPrimitive?.content ?: ""
                val relation = json["relation"]?.jsonPrimitive?.content ?: ""
                val to = json["to"]?.jsonPrimitive?.content ?: ""
                "$from → $relation → $to"
            }
            "get_memory_object", "update_memory_object", "delete_memory_object" -> {
                json["name"]?.jsonPrimitive?.content ?: ""
            }
            "invalidate_memory_link" -> {
                val from = json["from"]?.jsonPrimitive?.content ?: ""
                val relation = json["relation"]?.jsonPrimitive?.content ?: ""
                val to = json["to"]?.jsonPrimitive?.content ?: ""
                "$from → $relation → $to"
            }
            "mcp__gromozeka__create_agent" -> {
                val agentName = json["agent_name"]?.jsonPrimitive?.content ?: ""
                val projectPath = json["project_path"]?.jsonPrimitive?.content ?: ""
                "$agentName (${projectPath.substringAfterLast('/')})"
            }
            "mcp__gromozeka__tell_agent" -> {
                val message = json["message"]?.jsonPrimitive?.content ?: ""
                if (message.length > 50) message.take(47) + "..." else message
            }
            "mcp__gromozeka__switch_tab" -> {
                val tabId = json["tab_id"]?.jsonPrimitive?.content ?: ""
                "Tab #${tabId.take(8)}"
            }
            "mcp__gromozeka__list_contexts" -> {
                json["project_path"]?.jsonPrimitive?.content?.substringAfterLast('/') ?: "all"
            }
            "mcp__gromozeka__delete_context" -> {
                json["context_name"]?.jsonPrimitive?.content ?: ""
            }
            "mcp__gromozeka__save_contexts" -> {
                "XML contexts"
            }
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}

@Composable
fun ToolCallItem(
    toolCall: Conversation.Message.ContentItem.ToolCall.Data,
    toolResult: Conversation.Message.ContentItem.ToolResult?,
    projectPath: String,
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Determine status icon based on toolResult (no icon on success)
    val statusIcon = when {
        toolResult == null -> Icons.Default.Schedule // No result yet - in progress
        toolResult.isError -> Icons.Default.Error // Error
        else -> null // Success - no status icon
    }

    // Get tool display information
    val toolName = toolCall.name
    val displayName = getToolDisplayName(toolName)
    val toolIcon = getToolIcon(toolName)
    val secondaryIcon = getToolSecondaryIcon(toolName)
    val keyParameters = extractKeyParameters(toolName, toolCall.input, projectPath)
    val toolDescription = if (keyParameters.isNotEmpty()) "$displayName: $keyParameters" else displayName
    val detailedParameters = buildDetailedParameters(toolName, toolCall.input, projectPath)
    val toolAction = getToolAction(toolName, toolCall.input, projectPath)

    Column {
        // Row with main tool button + optional action button
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main tool call button with status + name + description
            DisableSelection {
                CompactButton(
                onClick = {
                    if (toolResult != null) {
                        isExpanded = !isExpanded
                    }
                },
                modifier = Modifier.Companion,
                enabled = toolResult != null,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                elevation = null,
                tooltip = when {
                    toolResult == null -> "Выполняется..."
                    toolResult.isError -> "Ошибка - клик для просмотра"
                    else -> "Успешно - клик для просмотра результата"
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tool primary icon
                    Icon(
                        toolIcon,
                        contentDescription = "Tool type"
                    )
                    
                    // Secondary icon (if exists)
                    secondaryIcon?.let { icon ->
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            icon,
                            contentDescription = "Tool action"
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    // Status icon (only for in-progress or error)
                    statusIcon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = "Tool status"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    // Tool description with parameters
                    Text(toolDescription)
                }
            }
        }
            
            // Action button (Open in IDE / Open URL)
            toolAction?.let { action ->
                DisableSelection {
                    CompactButton(
                        onClick = { executeToolAction(action) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                        elevation = null,
                        tooltip = when (action) {
                            is ToolAction.OpenInIde -> "Open in IDE"
                            is ToolAction.OpenUrl -> "Open URL"
                        }
                    ) {
                        Icon(
                            when (action) {
                                is ToolAction.OpenInIde -> Icons.Default.Code
                                is ToolAction.OpenUrl -> Icons.Default.OpenInBrowser
                            },
                            contentDescription = "Action"
                        )
                    }
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
                        // Show detailed parameters
                        Text(
                            text = detailedParameters,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Show result content - now it's a list of Data items
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            result.result.forEach { dataItem ->
                                    when (dataItem) {
                                        is Conversation.Message.ContentItem.ToolResult.Data.Text -> {
                                            val displayText = truncateText(dataItem.content, toolName)
                                            Text(
                                                text = displayText,
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