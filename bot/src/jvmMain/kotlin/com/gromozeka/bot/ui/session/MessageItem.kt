package com.gromozeka.bot.ui.session

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.CompactButton
import com.gromozeka.bot.ui.GromozekaMarkdown
import com.gromozeka.bot.ui.LocalTranslation
import com.gromozeka.bot.utils.jsonPrettyPrint
import com.gromozeka.shared.domain.conversation.ConversationTree

@Composable
fun MessageItem(
    message: ConversationTree.Message,
    settings: Settings,
    toolResultsMap: Map<String, ConversationTree.Message.ContentItem.ToolResult>,
    onShowJson: (String) -> Unit = {},
    onSpeakRequest: (String, String) -> Unit = { _, _ -> },
) {
    val translation = LocalTranslation.current

    // Combined metadata button data
    val roleIcon = when (message.role) {
        ConversationTree.Message.Role.USER -> Icons.Default.Person
        ConversationTree.Message.Role.ASSISTANT -> Icons.Default.DeveloperBoard
        ConversationTree.Message.Role.SYSTEM -> Icons.Default.Settings
    }

    val contentIcons = message.content.mapNotNull { content ->
        when (content) {
            is ConversationTree.Message.ContentItem.UserMessage -> null
            is ConversationTree.Message.ContentItem.ToolCall -> Icons.Default.Build
            is ConversationTree.Message.ContentItem.ToolResult -> null // Don't show ToolResult icon - they're integrated into ToolCall
            is ConversationTree.Message.ContentItem.Thinking -> Icons.Default.Psychology
            is ConversationTree.Message.ContentItem.System -> Icons.Default.Settings
            is ConversationTree.Message.ContentItem.AssistantMessage -> null
            is ConversationTree.Message.ContentItem.ImageItem -> Icons.Default.Image
            is ConversationTree.Message.ContentItem.UnknownJson -> Icons.Default.Warning
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
                    is ConversationTree.Message.ContentItem.UserMessage -> "Message"
                    is ConversationTree.Message.ContentItem.ToolCall -> "ToolCall"
                    is ConversationTree.Message.ContentItem.ToolResult -> null // Don't show in tooltip - integrated into ToolCall
                    is ConversationTree.Message.ContentItem.Thinking -> "Thinking"
                    is ConversationTree.Message.ContentItem.System -> "System"
                    is ConversationTree.Message.ContentItem.AssistantMessage -> "Assistant"
                    is ConversationTree.Message.ContentItem.ImageItem -> "Image"
                    is ConversationTree.Message.ContentItem.UnknownJson -> "Unknown"
                }
            }.distinct()
            append(contentTypes.joinToString(", "))
        }

        // Add TTS info if available
        val assistantContent =
            message.content.filterIsInstance<ConversationTree.Message.ContentItem.AssistantMessage>().firstOrNull()
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Metadata button (left, fixed width) with context menu
        DisableSelection {
            val clipboardManager = LocalClipboardManager.current

            ContextMenuArea(
                items = {
                    val assistantContent = message.content
                        .filterIsInstance<ConversationTree.Message.ContentItem.AssistantMessage>()
                        .firstOrNull()
                    val hasTtsText = !assistantContent?.structured?.ttsText.isNullOrBlank()

                    buildList {
                        add(ContextMenuItem(translation.copyMarkdownMenuItem) {
                            val markdownContent = message.content
                                .filterIsInstance<ConversationTree.Message.ContentItem.AssistantMessage>()
                                .firstOrNull()?.structured?.fullText
                                ?: message.content
                                    .filterIsInstance<ConversationTree.Message.ContentItem.UserMessage>()
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
                    }
                }
            ) {
                CompactButton(
                    onClick = { },
                    tooltip = tooltipText,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            roleIcon,
                            contentDescription = message.role.name
                        )

                        // Content type icons
                        if (hasContentIcons) {
                            contentIcons.forEach { contentIcon ->
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    contentIcon,
                                    contentDescription = "Content type"
                                )
                            }
                        } else {
                            // Default chat bubble icon
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = "Message"
                            )
                        }
                    }
                }
            }
        }

        // Message content (right, expandable) - LazyColumn should provide proper constraints
        val hasToolCalls = message.content.any { it is ConversationTree.Message.ContentItem.ToolCall }

        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp) // Should work now with LazyColumn constraints
                .background(
                    color = if (message.role == ConversationTree.Message.Role.USER &&
                        message.content.any { it is ConversationTree.Message.ContentItem.UserMessage }
                    ) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else Color.Transparent
                )
                .padding(horizontal = if (hasToolCalls) 0.dp else 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            message.content.forEach { content ->
                when (content) {
                    is ConversationTree.Message.ContentItem.UserMessage -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                GromozekaMarkdown(content = content.text)
                            }
                            FlowRow(
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

                    is ConversationTree.Message.ContentItem.ToolCall -> {
                        // Find corresponding result from entire chat history
                        val correspondingResult = toolResultsMap[content.id.value]
                        ToolCallItem(
                            toolCall = content.call,
                            toolResult = correspondingResult
                        )
                    }

                    is ConversationTree.Message.ContentItem.ToolResult -> {
                        // Don't render ToolResult separately - it's shown in ToolCallItem
                    }

                    is ConversationTree.Message.ContentItem.ImageItem -> {
                        when (val source = content.source) {
                            is ConversationTree.Message.ImageSource.Base64ImageSource -> {
                                // Base64 too long - show placeholder
                                Text(
                                    LocalTranslation.current.imageDisplayText.format(
                                        source.mediaType,
                                        source.data.length
                                    )
                                )
                            }

                            is ConversationTree.Message.ImageSource.UrlImageSource -> {
                                // URL can be shown in full
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Image")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(source.url)
                                }
                            }

                            is ConversationTree.Message.ImageSource.FileImageSource -> {
                                // File ID can be shown in full
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Image")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("File: ${source.fileId}")
                                }
                            }
                        }
                    }

                    is ConversationTree.Message.ContentItem.Thinking -> GromozekaMarkdown(content = content.thinking)
                    is ConversationTree.Message.ContentItem.System -> Text(text = content.content)
                    is ConversationTree.Message.ContentItem.AssistantMessage -> {
                        val text = content.structured.fullText.trim()
                        if (text.isNotEmpty()) {
                            GromozekaMarkdown(content = text)
                        }
                    }
                    is ConversationTree.Message.ContentItem.UnknownJson -> Column {
                        Text(text = jsonPrettyPrint(content.json))
                        Text(text = LocalTranslation.current.parseErrorText)
                    }
                }
            }
        }
    }
}
