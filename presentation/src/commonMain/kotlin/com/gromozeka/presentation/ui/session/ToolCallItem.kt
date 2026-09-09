package com.gromozeka.presentation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.format
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.serialization.json.*

private fun formatPath(path: String, workspaceRootPath: String?): String {
    val normalizedPath = path.trim()
    if (normalizedPath.isBlank()) return normalizedPath
    val normalizedWorkspaceRootPath = workspaceRootPath?.trimEnd('/')?.takeIf(String::isNotBlank)
        ?: return normalizedPath
    return if (normalizedPath.startsWith(normalizedWorkspaceRootPath)) {
        normalizedPath.removePrefix(normalizedWorkspaceRootPath).removePrefix("/")
    } else {
        normalizedPath
    }
}

private fun truncateText(text: String, localization: Translation, maxLines: Int = 5): String {
    val lines = text.lines()
    val lineBounded = if (lines.size > maxLines * 2) {
        val omittedCount = lines.size - maxLines * 2
        lines.take(maxLines).joinToString("\n") + "\n\n" +
            localization.plural("chat.tool.linesOmitted", omittedCount.toLong()) + "\n\n" +
            lines.takeLast(maxLines).joinToString("\n")
    } else {
        text
    }
    if (lineBounded.length <= MaxExpandedToolResultChars) return lineBounded
    val halfLimit = MaxExpandedToolResultChars / 2
    return lineBounded.take(halfLimit) + "\n\n" +
        localization.plural("chat.tool.charactersOmitted", (lineBounded.length - MaxExpandedToolResultChars).toLong()) +
        "\n\n" + lineBounded.takeLast(halfLimit)
}

private const val MaxExpandedToolResultChars = 12_000

private fun buildDetailedParameters(
    toolName: String,
    input: JsonElement,
    workspaceRootPath: String?,
    localization: Translation,
): String = try {
    val json = input.jsonObject
    when (toolName) {
        "grz_read_file" -> localization.text(
            "chat.tool.fileParameter", "path" to formatPath(json["file_path"]?.jsonPrimitive?.content.orEmpty(), workspaceRootPath)
        )
        "grz_write_file" -> {
            val path = formatPath(json["file_path"]?.jsonPrimitive?.content.orEmpty(), workspaceRootPath)
            val size = json["content"]?.jsonPrimitive?.content.orEmpty().encodeToByteArray().size
            localization.text("chat.tool.writeFileParameters", "path" to path,
                "size" to localization.plural("chat.tool.contentBytes", size.toLong()))
        }
        "grz_edit_file" -> listOf(
            localization.text("chat.tool.fileParameter", "path" to formatPath(json["file_path"]?.jsonPrimitive?.content.orEmpty(), workspaceRootPath)),
            localization.text("chat.tool.replacement", "oldText" to json["old_string"]?.jsonPrimitive?.content.orEmpty(),
                "newText" to json["new_string"]?.jsonPrimitive?.content.orEmpty()),
            localization.text("chat.tool.replacementMode", "mode" to localization.text(
                if (json["replace_all"]?.jsonPrimitive?.boolean == true) "chat.tool.replaceAll" else "chat.tool.replaceFirst"
            )),
        ).joinToString("\n")
        "grz_execute_command" -> listOfNotNull(
            localization.text("chat.tool.command", "command" to json["command"]?.jsonPrimitive?.content.orEmpty()),
            json["working_directory"]?.jsonPrimitive?.content?.let {
                localization.text("chat.tool.workingDirectory", "path" to formatPath(it, workspaceRootPath))
            },
            json["timeout_seconds"]?.jsonPrimitive?.longOrNull?.let { localization.text("chat.tool.timeout", "seconds" to it) },
        ).joinToString("\n")
        "brave_web_search", "brave_local_search" -> listOfNotNull(
            localization.text("chat.tool.query", "query" to json["query"]?.jsonPrimitive?.content.orEmpty()),
            json["count"]?.jsonPrimitive?.intOrNull?.let { localization.text("chat.tool.resultLimit", "count" to it) },
        ).joinToString("\n")
        "jina_read_url" -> localization.text("chat.tool.url", "url" to json["url"]?.jsonPrimitive?.content.orEmpty())
        "create_agent" -> listOfNotNull(
            localization.text("chat.tool.agent", "agentName" to json["agent_name"]?.jsonPrimitive?.content.orEmpty()),
            localization.text("chat.tool.project", "projectId" to json["project_id"]?.jsonPrimitive?.content.orEmpty()),
            localization.text("chat.tool.workspace", "workspaceId" to json["workspace_id"]?.jsonPrimitive?.content.orEmpty()),
            json["initial_message"]?.jsonPrimitive?.content?.let {
                localization.text("chat.tool.initialMessage", "message" to if (it.length > 100) it.take(100) + "…" else it)
            },
        ).joinToString("\n")
        "tell_agent" -> listOfNotNull(
            json["target_tab_id"]?.jsonPrimitive?.content?.let { localization.text("chat.tool.targetTab", "tabId" to it.take(8)) },
            localization.text("chat.tool.message", "message" to json["message"]?.jsonPrimitive?.content.orEmpty()),
        ).joinToString("\n")
        "switch_tab" -> localization.text("chat.tool.tabId", "tabId" to json["tab_id"]?.jsonPrimitive?.content.orEmpty())
        else -> json.toString()
    }
} catch (e: Exception) {
    localization.text("chat.tool.parametersError", "message" to e.message.orEmpty())
}

private fun extractKeyParameters(
    toolName: String,
    input: JsonElement,
    workspaceRootPath: String?,
    localization: Translation,
): String = try {
    val json = input.jsonObject
    when (toolName) {
        "grz_read_file" -> formatPath(json["file_path"]?.jsonPrimitive?.content.orEmpty(), workspaceRootPath)
        "grz_write_file" -> {
            val path = formatPath(json["file_path"]?.jsonPrimitive?.content.orEmpty(), workspaceRootPath)
            val size = json["content"]?.jsonPrimitive?.content.orEmpty().encodeToByteArray().size
            val sizeLabel = localization.plural("chat.tool.contentBytes", size.toLong())
            "$path ($sizeLabel)"
        }
        "grz_edit_file" -> {
            val path = formatPath(json["file_path"]?.jsonPrimitive?.content.orEmpty(), workspaceRootPath)
            if (json["replace_all"]?.jsonPrimitive?.boolean == true) {
                localization.text("chat.tool.replaceAllSummary", "path" to path)
            } else path
        }
        "grz_execute_command" -> json["command"]?.jsonPrimitive?.content.orEmpty().let {
            if (it.length > 60) it.take(57) + "..." else it
        }
        "brave_web_search", "brave_local_search" -> json["query"]?.jsonPrimitive?.content.orEmpty()
        "jina_read_url" -> json["url"]?.jsonPrimitive?.content.orEmpty().let {
            if (it.length > 50) it.take(47) + "..." else it
        }
        "create_agent" -> {
            val agentName = json["agent_name"]?.jsonPrimitive?.content.orEmpty()
            val workspaceId = json["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            "$agentName ($workspaceId)"
        }
        "tell_agent" -> json["message"]?.jsonPrimitive?.content.orEmpty().let {
            if (it.length > 50) it.take(47) + "..." else it
        }
        "switch_tab" -> localization.text("chat.tool.tabSummary", "tabId" to json["tab_id"]?.jsonPrimitive?.content.orEmpty().take(8))
        else -> ""
    }
} catch (e: Exception) {
    ""
}

@Composable
internal fun ToolCallItem(
    toolCall: Conversation.Message.ContentItem.ToolCall.Data,
    toolResult: Conversation.Message.ContentItem.ToolResult?,
    workspaceRootPath: String?,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    activityState: ActivityState,
    modifier: Modifier = Modifier,
    loadArtifactContent: suspend (com.gromozeka.domain.model.Artifact.Id) -> ByteArray,
) {
    val localization = LocalTranslation.current
    // Get tool display information
    val toolName = toolCall.name
    val displayName = toolDisplayName(toolName, LocalTranslation.current.runtime)
    val keyParameters = extractKeyParameters(toolName, toolCall.input, workspaceRootPath, localization)
    val toolDescription = if (keyParameters.isNotEmpty()) "$displayName: $keyParameters" else displayName
    val detailedParameters = buildDetailedParameters(toolName, toolCall.input, workspaceRootPath, localization)

    Column {
        ActivityHeader(
            kind = ActivityKind.Tool(toolName),
            title = toolDescription,
            state = activityState,
            canExpand = toolResult != null,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            modifier = modifier,
        )

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
                                        val prettyJsonContent = remember(dataItem.content) {
                                            val firstNonWhitespace = dataItem.content.firstOrNull { !it.isWhitespace() }
                                            if (firstNonWhitespace == '{' || firstNonWhitespace == '[') {
                                                jsonPrettyPrintOrNull(dataItem.content)
                                            } else {
                                                null
                                            }
                                        }
                                        val displayText = truncateText(
                                            text = prettyJsonContent ?: dataItem.content,
                                            localization = localization,
                                            maxLines = if (prettyJsonContent == null) 5 else 40,
                                        )
                                        Text(
                                            text = displayText,
                                            modifier = Modifier.fillMaxWidth(),
                                            style = if (prettyJsonContent == null) {
                                                MaterialTheme.typography.bodySmall
                                            } else {
                                                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                            },
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
                                                            contentDescription = localization.text("chat.attachment.image")
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = localization.format("imageDisplayText", dataItem.mediaType.value, dataItem.data.length),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }

                                                else -> {
                                                    // Non-image Base64 data - show truncated version
                                                    val truncated = if (dataItem.data.length > 100) {
                                                        localization.plural("chat.tool.dataTruncation", (dataItem.data.length - 100).toLong(),
                                                            "prefix" to dataItem.data.take(50), "suffix" to dataItem.data.takeLast(50))
                                                    } else {
                                                        dataItem.data
                                                    }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Description,
                                                            contentDescription = localization.text("chat.attachment.document")
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
                                                    contentDescription = localization.text("chat.attachment.url")
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${dataItem.url}${dataItem.mediaType?.let { " (${it.value})" } ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData -> {
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        if (dataItem.artifact.kind == com.gromozeka.domain.model.Artifact.Kind.IMAGE) {
                                                            Icons.Default.Image
                                                        } else {
                                                            Icons.Default.Folder
                                                        },
                                                        contentDescription = localization.text("chat.attachment.file")
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "${dataItem.artifact.fileName} (${dataItem.artifact.mediaType})",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                if (dataItem.artifact.kind == com.gromozeka.domain.model.Artifact.Kind.IMAGE) {
                                                    ArtifactImagePreview(dataItem.artifact, loadArtifactContent)
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
