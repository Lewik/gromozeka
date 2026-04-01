package com.gromozeka.presentation.ui.session

import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.model.Settings
import com.gromozeka.presentation.ui.GromozekaMarkdown
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.shared.utils.jsonPrettyPrint

@Composable
fun MessageItem(
    message: Conversation.Message,
    settings: Settings,
    toolResultsMap: Map<String, Conversation.Message.ContentItem.ToolResult>,
    projectPath: String,
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
            is Conversation.Message.ContentItem.UnknownJson -> true
        }
    }

    if (!hasRenderableContent && message.error == null) {
        return
    }

    // Combined metadata button data
    val roleIcon = when (message.role) {
        Conversation.Message.Role.USER -> Icons.Default.Person
        Conversation.Message.Role.ASSISTANT -> Icons.Default.DeveloperBoard
        Conversation.Message.Role.SYSTEM -> Icons.Default.Settings
    }

    val contentIcons = message.content.mapNotNull { content ->
        when (content) {
            is Conversation.Message.ContentItem.UserMessage -> null
            is Conversation.Message.ContentItem.ToolCall -> Icons.Default.Build
            is Conversation.Message.ContentItem.ToolResult -> null // Don't show ToolResult icon - they're integrated into ToolCall
            is Conversation.Message.ContentItem.Thinking -> if (content.isVisible) Icons.Default.Psychology else null
            is Conversation.Message.ContentItem.System -> Icons.Default.Settings
            is Conversation.Message.ContentItem.AssistantMessage -> null
            is Conversation.Message.ContentItem.ImageItem -> Icons.Default.Image
            is Conversation.Message.ContentItem.UnknownJson -> Icons.Default.Warning
        }
    }.distinct()

    val hasContentIcons = contentIcons.isNotEmpty()

    val tooltipText = buildString {
        // Role / Type format
        append(message.role.name)
        if (contentIcons.isNotEmpty()) {
            append(" / ")
            val contentTypes = message.content.mapNotNull { content ->
                when (content) {
                    is Conversation.Message.ContentItem.UserMessage -> "Message"
                    is Conversation.Message.ContentItem.ToolCall -> "ToolCall"
                    is Conversation.Message.ContentItem.ToolResult -> null // Don't show in tooltip - integrated into ToolCall
                    is Conversation.Message.ContentItem.Thinking -> if (content.isVisible) "Thinking" else null
                    is Conversation.Message.ContentItem.System -> "System"
                    is Conversation.Message.ContentItem.AssistantMessage -> "Assistant"
                    is Conversation.Message.ContentItem.ImageItem -> "Image"
                    is Conversation.Message.ContentItem.UnknownJson -> "Unknown"
                }
            }.distinct()
            append(contentTypes.joinToString(", "))
        }

        // Add TTS info if available
        val assistantContent =
            message.content.filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>().firstOrNull()
        assistantContent?.structured?.let { structured ->
            if (structured.ttsText != null || structured.voiceTone != null) {
                append("\n\n")
                structured.ttsText?.let { append("🎵 TTS: $it") }
                if (structured.ttsText != null && structured.voiceTone != null) append("\n")
                structured.voiceTone?.let { append("🎭 Tone: $it") }
            }
        }

        append(translation.contextMenuHint)
    }

    // Compact horizontal layout - simple Row without IntrinsicSize.Min
    val clipboardManager = LocalClipboardManager.current

    ContextMenuArea(
        items = {
            val assistantContent = message.content
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .firstOrNull()
            val hasTtsText = !assistantContent?.structured?.ttsText.isNullOrBlank()

            buildList {
                add(ContextMenuItem(translation.copyMarkdownMenuItem) {
                    val markdownContent = message.content
                        .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                        .firstOrNull()?.structured?.fullText
                        ?: message.content
                            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                            .firstOrNull()?.text
                        ?: translation.contentUnavailable
                    clipboardManager.setText(AnnotatedString(markdownContent))
                })

                if (hasTtsText) {
                    add(ContextMenuItem(translation.speakMenuItem) {
                        val ttsText = assistantContent.structured.ttsText!!
                        val voiceTone = assistantContent.structured.voiceTone ?: ""
                        onSpeakRequest(ttsText, voiceTone)
                    })
                }

                val hasThinking = message.content.any {
                    (it as? Conversation.Message.ContentItem.Thinking)?.isVisible == true
                }
                if (!hasThinking) {
                    add(ContextMenuItem("Edit") {
                        onEditRequest(message.id)
                    })
                }

                add(ContextMenuItem("Delete") {
                    onDeleteRequest(message.id)
                })
            }
        }
    ) {
        // Message content - Box without collapse (collapse is now per-content-item)
        val hasToolCalls = message.content.any { it is Conversation.Message.ContentItem.ToolCall }
        val selectionBorderColor = MaterialTheme.colorScheme.primary

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    org.slf4j.LoggerFactory.getLogger("MessageItem").trace(
                                        "Rendering UserMessage markdown | msg_id={} | content_length={} | preview={}",
                                        message.id.value,
                                        content.text.length,
                                        content.text.take(100).replace("\n", "\\n")
                                    )
                                    GromozekaMarkdown(content = content.text)
                                }
                                DisableSelection {
                                    FlowRow(
                                        modifier = Modifier.align(Alignment.Top),
                                        maxItemsInEachRow = 4,
                                        verticalArrangement = Arrangement.Top,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        message.instructions.forEach { instruction ->
                                            TooltipArea(
                                                tooltip = {
                                                    Surface(
                                                        modifier = Modifier.shadow(4.dp),
                                                        color = MaterialTheme.colorScheme.inverseOnSurface,
                                                        shape = MaterialTheme.shapes.small
                                                    ) {
                                                        Text(
                                                            modifier = Modifier.padding(12.dp),
                                                            text = instruction.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.inverseSurface
                                                        )
                                                    }
                                                }
                                            ) {
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
                            }
                        }

                        is Conversation.Message.ContentItem.ToolCall -> {
                            // Find corresponding result from entire chat history
                            val correspondingResult = toolResultsMap[content.id.value]
                            ToolCallItem(
                                toolCall = content.call,
                                toolResult = correspondingResult,
                                projectPath = projectPath
                            )
                        }

                        is Conversation.Message.ContentItem.ToolResult -> {
                            // Don't render ToolResult separately - it's shown in ToolCallItem
                        }

                        is Conversation.Message.ContentItem.ImageItem -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                                                Icon(Icons.Default.Image, contentDescription = "Image")
                                                Spacer(modifier = Modifier.width(4.dp))
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
                                        .animateContentSize()
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
                                            org.slf4j.LoggerFactory.getLogger("MessageItem").trace(
                                                "Rendering Thinking markdown | msg_id={} | content_length={} | preview={}",
                                                message.id.value,
                                                content.thinking.length,
                                                content.thinking.take(100).replace("\n", "\\n")
                                            )
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
                                                    modifier = Modifier.clickable { onToggleContentItemCollapse(message.id, contentIndex) }
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
                                        .animateContentSize()
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
                                            org.slf4j.LoggerFactory.getLogger("MessageItem").trace(
                                                "Rendering AssistantMessage markdown | msg_id={} | content_length={} | preview={}",
                                                message.id.value,
                                                text.length,
                                                text.take(100).replace("\n", "\\n")
                                            )
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
                                                    modifier = Modifier.clickable { onToggleContentItemCollapse(message.id, contentIndex) }
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

                        is Conversation.Message.ContentItem.UnknownJson -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
}
