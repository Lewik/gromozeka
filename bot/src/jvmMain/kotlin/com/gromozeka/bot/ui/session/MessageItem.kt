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
import com.gromozeka.shared.domain.message.ChatMessage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: ChatMessage,
    settings: Settings,
    toolResultsMap: Map<String, ChatMessage.ContentItem.ToolResult>,
    onShowJson: (String) -> Unit = {},
    onSpeakRequest: (String, String) -> Unit = { _, _ -> },
) {
    val translation = LocalTranslation.current

    // Combined metadata button data
    val roleIcon = when (message.role) {
        ChatMessage.Role.USER -> Icons.Default.Person
        ChatMessage.Role.ASSISTANT -> Icons.Default.DeveloperBoard
        ChatMessage.Role.SYSTEM -> Icons.Default.Settings
    }

    val contentIcons = message.content.mapNotNull { content ->
        when (content) {
            is ChatMessage.ContentItem.UserMessage -> null
            is ChatMessage.ContentItem.ToolCall -> Icons.Default.Build
            is ChatMessage.ContentItem.ToolResult -> null // Don't show ToolResult icon - they're integrated into ToolCall
            is ChatMessage.ContentItem.Thinking -> Icons.Default.Psychology
            is ChatMessage.ContentItem.System -> Icons.Default.Settings
            is ChatMessage.ContentItem.AssistantMessage -> null
            is ChatMessage.ContentItem.ImageItem -> Icons.Default.Image
            is ChatMessage.ContentItem.UnknownJson -> Icons.Default.Warning
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
                    is ChatMessage.ContentItem.UserMessage -> "Message"
                    is ChatMessage.ContentItem.ToolCall -> "ToolCall"
                    is ChatMessage.ContentItem.ToolResult -> null // Don't show in tooltip - integrated into ToolCall
                    is ChatMessage.ContentItem.Thinking -> "Thinking"
                    is ChatMessage.ContentItem.System -> "System"
                    is ChatMessage.ContentItem.AssistantMessage -> "Assistant"
                    is ChatMessage.ContentItem.ImageItem -> "Image"
                    is ChatMessage.ContentItem.UnknownJson -> "Unknown"
                }
            }.distinct()
            append(contentTypes.joinToString(", "))
        }

        // Add TTS info if available
        val assistantContent =
            message.content.filterIsInstance<ChatMessage.ContentItem.AssistantMessage>().firstOrNull()
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
                        .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
                        .firstOrNull()
                    val hasTtsText = !assistantContent?.structured?.ttsText.isNullOrBlank()

                    buildList {
                        if (settings.showOriginalJson) {
                            add(ContextMenuItem(translation.showJsonMenuItem) {
                                val jsonToShow = (message.originalJson ?: "No JSON available")
                                onShowJson(jsonToShow)
                            })
                        }

                        add(ContextMenuItem(translation.copyMarkdownMenuItem) {
                            val markdownContent = message.content
                                .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
                                .firstOrNull()?.structured?.fullText
                                ?: message.content
                                    .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
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
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp) // Should work now with LazyColumn constraints
                .background(
                    color = if (message.role == ChatMessage.Role.USER &&
                        message.content.any { it is ChatMessage.ContentItem.UserMessage }
                    ) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else Color.Transparent
                )
                .padding(horizontal = if (message.content.any { it is ChatMessage.ContentItem.ToolCall }) 0.dp else 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            message.content.forEach { content ->
                when (content) {
                    is ChatMessage.ContentItem.UserMessage -> {
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

                    is ChatMessage.ContentItem.ToolCall -> {
                        // Find corresponding result from entire chat history
                        val correspondingResult = toolResultsMap[content.id]
                        ToolCallItem(
                            toolCall = content.call,
                            toolResult = correspondingResult
                        )
                    }

                    is ChatMessage.ContentItem.ToolResult -> {
                        // Don't render ToolResult separately - it's shown in ToolCallItem
                    }

                    is ChatMessage.ContentItem.ImageItem -> {
                        when (val source = content.source) {
                            is ChatMessage.ImageSource.Base64ImageSource -> {
                                // Base64 too long - show placeholder
                                Text(
                                    LocalTranslation.current.imageDisplayText.format(
                                        source.mediaType,
                                        source.data.length
                                    )
                                )
                            }

                            is ChatMessage.ImageSource.UrlImageSource -> {
                                // URL can be shown in full
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Image")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(source.url)
                                }
                            }

                            is ChatMessage.ImageSource.FileImageSource -> {
                                // File ID can be shown in full
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Image")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("File: ${source.fileId}")
                                }
                            }
                        }
                    }

                    is ChatMessage.ContentItem.Thinking -> GromozekaMarkdown(content = content.thinking)
                    is ChatMessage.ContentItem.System -> Text(text = content.content)
                    is ChatMessage.ContentItem.AssistantMessage -> GromozekaMarkdown(content = content.structured.fullText)
                    is ChatMessage.ContentItem.UnknownJson -> Column {
                        Text(text = jsonPrettyPrint(content.json))
                        Text(text = LocalTranslation.current.parseErrorText)
                    }
                }
            }
        }
    }
}
