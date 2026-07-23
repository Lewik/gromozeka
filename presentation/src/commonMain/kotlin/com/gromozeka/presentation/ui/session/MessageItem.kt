package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Settings
import com.gromozeka.presentation.ui.GromozekaMarkdown
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.format
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Composable
fun MessageItem(
    message: Conversation.Message,
    settings: Settings,
    toolResultsMap: Map<String, Conversation.Message.ContentItem.ToolResult>,
    workspaceRootPath: String? = null,
    isSelected: Boolean = false,
    collapsedContentItems: Set<Int> = emptySet(), // indices of collapsed content items
    onToggleSelection: (Conversation.Message.Id, Boolean) -> Unit = { _, _ -> },
    onToggleContentItemCollapse: (Conversation.Message.Id, Int) -> Unit = { _, _ -> }, // messageId, contentItemIndex
    onShowJson: (String) -> Unit = {},
    onSpeakRequest: (String, String) -> Unit = { _, _ -> },
    onEditRequest: (Conversation.Message.Id) -> Unit = {},
    onDeleteRequest: (Conversation.Message.Id) -> Unit = {},
) {
    val translation = LocalTranslation.current
    val hasRenderableContent = message.content.any { content ->
        when (content) {
            is Conversation.Message.ContentItem.UserMessage -> content.text.isNotBlank()
            is Conversation.Message.ContentItem.ToolCall -> true
            is Conversation.Message.ContentItem.ToolResult -> false
            is Conversation.Message.ContentItem.Thinking -> content.isVisible
            is Conversation.Message.ContentItem.System -> content.content.isNotBlank()
            is Conversation.Message.ContentItem.AssistantMessage -> content.structured.fullText.isNotBlank()
            is Conversation.Message.ContentItem.ImageItem -> true
            is Conversation.Message.ContentItem.ContextCompactionResult -> true
            is Conversation.Message.ContentItem.UnknownJson -> true
        }
    }

    if (!hasRenderableContent && message.error == null) {
        return
    }

    val selectionBorderColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(UiTestTag.MessageItem(message.id.value).value)
                .background(
                    color = if (message.role == Conversation.Message.Role.USER &&
                        message.content.any { it is Conversation.Message.ContentItem.UserMessage }
                    ) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else Color.Transparent
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            // Обрабатывать только левый клик (Primary button), правый клик пропускать для контекстного меню
                            if (down.changes.any { it.pressed && !it.previousPressed } && down.buttons.isPrimaryPressed) {
                                val downPosition = down.changes.first().position
                                val isShiftPressed = down.keyboardModifiers.isShiftPressed

                                // Ждем release и проверяем, не было ли движения мыши (drag для text selection)
                                var isDrag = false
                                do {
                                    val event = awaitPointerEvent()
                                    val currentPosition = event.changes.first().position
                                    val distance = (currentPosition - downPosition).getDistance()

                                    // Если мышь сдвинулась больше чем на 10 пикселей, это drag для text selection
                                    if (distance > 10f) {
                                        isDrag = true
                                        break
                                    }
                                } while (event.changes.any { it.pressed })

                                // Если мышь не двигалась, это клик для selection
                                if (!isDrag) {
                                    down.changes.forEach { it.consume() }
                                    onToggleSelection(message.id, isShiftPressed)
                                }
                            }
                        }
                    }
                }
                .drawBehind {
                    if (isSelected) {
                        drawRoundRect(
                            color = selectionBorderColor,
                            style = Stroke(width = 3.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier.padding(
                    start = if (message.role == Conversation.Message.Role.USER) 12.dp else 4.dp,
                    end = 4.dp
                ),
                verticalArrangement = Arrangement.Center
            ) {
                message.content.forEachIndexed { contentIndex, content ->
                    val isContentCollapsed = contentIndex in collapsedContentItems
                    when (content) {
                        is Conversation.Message.ContentItem.UserMessage -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                GromozekaMarkdown(
                                    content = content.text,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                InstructionChips(
                                    instructions = message.instructions,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        is Conversation.Message.ContentItem.ToolCall -> {
                            // Find corresponding result from entire chat history
                            val correspondingResult = toolResultsMap[content.id.value]
                            ToolCallItem(
                                toolCall = content.call,
                                toolResult = correspondingResult,
                                workspaceRootPath = workspaceRootPath,
                            )
                        }

                        is Conversation.Message.ContentItem.ToolResult -> {
                            // Don't render ToolResult separately - it's shown in ToolCallItem
                        }

                        is Conversation.Message.ContentItem.ImageItem -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.Image, contentDescription = "Image")
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    when (val source = content.source) {
                                        is Conversation.Message.ImageSource.Base64ImageSource -> {
                                            // Base64 too long - show placeholder
                                            Text(
                                                LocalTranslation.current.imageDisplayText.format(
                                                    source.mediaType,
                                                    source.data.length
                                                )
                                            )
                                        }

                                        is Conversation.Message.ImageSource.UrlImageSource -> {
                                            // URL can be shown in full
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(source.url)
                                            }
                                        }

                                        is Conversation.Message.ImageSource.FileImageSource -> {
                                            // File ID can be shown in full
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Image, contentDescription = "Image")
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("File: ${source.fileId}")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is Conversation.Message.ContentItem.Thinking -> {
                            if (content.isVisible) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            shape = MaterialTheme.shapes.small
                                        )
                                        .padding(8.dp)
                                        .then(
                                            if (isContentCollapsed) {
                                                Modifier
                                                    .height(48.dp)
                                                    .clipToBounds()
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .alpha(if (isContentCollapsed) 0.5f else 1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            GromozekaMarkdown(content = content.thinking)
                                        }
                                        DisableSelection {
                                            FlowRow(
                                                modifier = Modifier.align(Alignment.Top),
                                                maxItemsInEachRow = 4,
                                                verticalArrangement = Arrangement.Top,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier.clickable {
                                                        onToggleContentItemCollapse(message.id, contentIndex)
                                                    }
                                                        .padding(8.dp)
                                                ) {
                                                    Icon(
                                                        if (isContentCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                                        contentDescription = if (isContentCollapsed) "Expand" else "Collapse",
                                                        tint = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is Conversation.Message.ContentItem.System -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(text = content.content)
                                }
                            }
                        }

                        is Conversation.Message.ContentItem.AssistantMessage -> {
                            val text = content.structured.fullText.trim()
                            if (text.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .then(
                                            if (isContentCollapsed) {
                                                Modifier
                                                    .height(48.dp)
                                                    .clipToBounds()
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .alpha(if (isContentCollapsed) 0.5f else 1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            GromozekaMarkdown(content = text)
                                        }
                                        DisableSelection {
                                            FlowRow(
                                                modifier = Modifier.align(Alignment.Top),
                                                maxItemsInEachRow = 4,
                                                verticalArrangement = Arrangement.Top,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                // Chevron for collapse/expand
                                                Box(
                                                    modifier = Modifier.clickable {
                                                        onToggleContentItemCollapse(message.id, contentIndex)
                                                    }
                                                        .padding(8.dp)
                                                ) {
                                                    Icon(
                                                        if (isContentCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                                        contentDescription = if (isContentCollapsed) "Expand" else "Collapse",
                                                        tint = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is Conversation.Message.ContentItem.ContextCompactionResult -> {
                            ContextCompactionResultItem(content)
                        }

                        is Conversation.Message.ContentItem.UnknownJson -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Parse error",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(text = jsonPrettyPrint(content.json))
                                    Text(text = LocalTranslation.current.parseErrorText)
                                }
                            }
                        }
                    }
                }

                // Show error if present
                if (message.error != null) {
                    val error = message.error!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = error.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
}

@Composable
private fun ContextCompactionResultItem(
    content: Conversation.Message.ContentItem.ContextCompactionResult,
) {
    val title = when (content.origin) {
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED -> "Context compacted"
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY -> "Context compacted by policy"
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO -> "Provider compacted context"
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.RUNTIME_MIGRATION -> "Migration compact created"
    }
    val details = when (val payload = content.payload) {
        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
            payload.text.trim()
        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
            "Opaque provider state: ${content.providerScope?.provider ?: "unknown provider"}"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun InstructionChips(
    instructions: List<Conversation.Message.Instruction>,
    modifier: Modifier = Modifier,
) {
    if (instructions.isEmpty()) {
        return
    }

    DisableSelection {
        FlowRow(
            modifier = modifier,
            maxItemsInEachRow = 4,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            instructions.forEach { instruction ->
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = instruction.title,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

private val prettyJson = Json {
    prettyPrint = true
    isLenient = true
}

private fun jsonPrettyPrint(json: JsonElement): String =
    prettyJson.encodeToString(JsonElement.serializer(), json)
