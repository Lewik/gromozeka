package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.services.translation.data.Translation
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.GromozekaMarkdown
import com.gromozeka.presentation.ui.GromozekaMarkdownNode
import com.gromozeka.presentation.ui.visibleMarkdownBlocks
import com.gromozeka.presentation.ui.displayTitle
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.format
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.intellij.markdown.ast.ASTNode

internal data class MessageListEntry(
    val message: Conversation.Message,
    val segment: MessageSegment,
    val isFirstInMessage: Boolean,
    val isLastInMessage: Boolean,
    val activityDepth: Int = 0,
    val groupContentKey: String? = null,
) {
    val sourceKey: String = "${message.id.value}:${segment.key}"
    val key: String = if (activityDepth > 0 && segment is MessageSegment.Activity) "$sourceKey:member" else sourceKey
}

internal sealed interface MessageSegment {
    val key: String

    data class MarkdownBlock(
        val kind: MarkdownKind,
        val contentIndex: Int,
        val state: State.Success,
        val node: ASTNode,
        val nodeIndex: Int,
        val isFirstInContent: Boolean,
        val isLastInContent: Boolean,
    ) : MessageSegment {
        override val key: String = "$contentIndex:markdown:$nodeIndex"
    }

    data class RawMarkdownBlock(
        val kind: MarkdownKind,
        val contentIndex: Int,
        val text: String,
        val chunkIndex: Int,
        val isFirstInContent: Boolean,
        val isLastInContent: Boolean,
    ) : MessageSegment {
        override val key: String = "$contentIndex:raw-markdown:$chunkIndex"
    }

    data class CollapsedMarkdown(
        val kind: MarkdownKind,
        val contentIndex: Int,
        val text: String,
    ) : MessageSegment {
        override val key: String = "$contentIndex:collapsed-markdown"
    }

    data class Content(
        val contentIndex: Int,
        val content: Conversation.Message.ContentItem,
    ) : MessageSegment {
        override val key: String = "$contentIndex:content"
    }

    data class Activity(val contentIndex: Int, val activity: ChatActivity) : MessageSegment {
        override val key: String = "$contentIndex:content"
    }

    data class ActivityGroup(val activities: List<ActivityReference>) : MessageSegment {
        init {
            require(activities.size >= 2) { "An activity group must contain at least two activities" }
        }
        override val key: String = "${activities.first().contentIndex}:content"
    }

    data class Instructions(
        val contentIndex: Int,
    ) : MessageSegment {
        override val key: String = "$contentIndex:instructions"
    }

    data object Error : MessageSegment {
        override val key: String = "error"
    }
}

internal enum class MarkdownKind {
    USER,
    DETAIL,
    ASSISTANT,
}

@Composable
internal fun rememberMessageListEntries(
    messages: List<Conversation.Message>,
    collapsedContentItems: Map<Conversation.Message.Id, Set<Int>>,
    toolResultsMap: Map<String, Conversation.Message.ContentItem.ToolResult>,
    expandedActivityKeys: Set<String> = emptySet(),
): List<MessageListEntry> {
    val entries = mutableListOf<MessageListEntry>()

    for (message in messages) {
        val segments = mutableListOf<MessageSegment>()
        val collapsedItems = collapsedContentItems[message.id].orEmpty()

        for (contentIndex in message.content.indices) {
            val content = message.content[contentIndex]
            when (content) {
                is Conversation.Message.ContentItem.UserMessage -> {
                    if (content.text.isNotBlank()) {
                        segments += rememberMarkdownSegments(
                            messageId = message.id,
                            contentIndex = contentIndex,
                            kind = MarkdownKind.USER,
                            text = content.text,
                            isCollapsed = false,
                        )
                        if (message.instructions.isNotEmpty()) {
                            segments += MessageSegment.Instructions(contentIndex)
                        }
                    }
                }

                is Conversation.Message.ContentItem.Thinking ->
                    segments += MessageSegment.Activity(contentIndex, ChatActivity.Reasoning(content))

                is Conversation.Message.ContentItem.ToolCall ->
                    segments += MessageSegment.Activity(contentIndex, ChatActivity.Tool(content, toolResultsMap[content.id.value]))

                is Conversation.Message.ContentItem.AssistantMessage -> {
                    val text = content.structured.fullText.trim()
                    if (text.isNotEmpty()) {
                        segments += rememberMarkdownSegments(
                            messageId = message.id,
                            contentIndex = contentIndex,
                            kind = MarkdownKind.ASSISTANT,
                            text = text,
                            isCollapsed = contentIndex in collapsedItems,
                        )
                    }
                }

                is Conversation.Message.ContentItem.ToolResult -> Unit
                else -> segments += MessageSegment.Content(contentIndex, content)
            }
        }

        if (message.error != null) {
            segments += MessageSegment.Error
        }

        segments.forEachIndexed { index, segment ->
            entries += MessageListEntry(
                message = message,
                segment = segment,
                isFirstInMessage = index == 0,
                isLastInMessage = index == segments.lastIndex,
            )
        }
    }

    return expandActivityEntries(groupActivityEntries(entries), expandedActivityKeys)
}

@Composable
private fun expandActivityEntries(
    entries: List<MessageListEntry>,
    expandedActivityKeys: Set<String>,
): List<MessageListEntry> {
    val result = mutableListOf<MessageListEntry>()
    for (entry in entries) {
        val group = entry.segment as? MessageSegment.ActivityGroup
        if (group == null) {
            result += rememberActivityEntries(entry, expandedActivityKeys)
        } else {
            val isExpanded = activityGroupExpansionKey(entry.key) in expandedActivityKeys
            result += entry.copy(isLastInMessage = entry.isLastInMessage && !isExpanded)
            if (isExpanded) {
                for ((index, reference) in group.activities.withIndex()) {
                    val child = reference.toEntry().copy(
                        isFirstInMessage = false,
                        activityDepth = 1,
                        groupContentKey = entry.key.takeIf { index == 0 },
                    )
                    result += rememberActivityEntries(child, expandedActivityKeys)
                }
            }
        }
    }
    return result
}

@Composable
private fun rememberActivityEntries(
    entry: MessageListEntry,
    expandedActivityKeys: Set<String>,
): List<MessageListEntry> {
    val segment = entry.segment as? MessageSegment.Activity ?: return listOf(entry)
    val reasoning = segment.activity as? ChatActivity.Reasoning ?: return listOf(entry)
    if (!reasoning.canExpand || activityExpansionKey(entry.sourceKey) !in expandedActivityKeys) return listOf(entry)
    val details = rememberMarkdownSegments(
        messageId = entry.message.id,
        contentIndex = segment.contentIndex,
        kind = MarkdownKind.DETAIL,
        text = reasoning.block.thinking,
        isCollapsed = false,
    )
    return listOf(entry.copy(isLastInMessage = entry.isLastInMessage && details.isEmpty())) +
        details.mapIndexed { index, detail ->
            entry.copy(
                segment = detail,
                isFirstInMessage = false,
                isLastInMessage = entry.isLastInMessage && index == details.lastIndex,
                activityDepth = entry.activityDepth + 1,
                groupContentKey = null,
            )
        }
}

@Composable
private fun rememberMarkdownSegments(
    messageId: Conversation.Message.Id,
    contentIndex: Int,
    kind: MarkdownKind,
    text: String,
    isCollapsed: Boolean,
): List<MessageSegment> {
    val parsedState = key(messageId.value, contentIndex) {
        val markdownState = rememberMarkdownState(
            content = text,
            retainState = true,
        )
        val state by markdownState.state.collectAsState()
        state
    }

    if (isCollapsed) {
        return listOf(MessageSegment.CollapsedMarkdown(kind, contentIndex, text))
    }

    if (parsedState is State.Success) {
        val blocks = parsedState.node.visibleMarkdownBlocks(parsedState.content)
        return blocks.mapIndexed { nodeIndex, node ->
            MessageSegment.MarkdownBlock(
                kind = kind,
                contentIndex = contentIndex,
                state = parsedState,
                node = node,
                nodeIndex = nodeIndex,
                isFirstInContent = nodeIndex == 0,
                isLastInContent = nodeIndex == blocks.lastIndex,
            )
        }
    }

    val chunks = splitRawMarkdown(text)
    return chunks.mapIndexed { chunkIndex, chunk ->
        MessageSegment.RawMarkdownBlock(
            kind = kind,
            contentIndex = contentIndex,
            text = chunk,
            chunkIndex = chunkIndex,
            isFirstInContent = chunkIndex == 0,
            isLastInContent = chunkIndex == chunks.lastIndex,
        )
    }
}

private fun splitRawMarkdown(text: String, maxChunkLength: Int = 2_000): List<String> {
    if (text.length <= maxChunkLength) {
        return listOf(text)
    }

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val limit = minOf(start + maxChunkLength, text.length)
        val lineBreak = text.lastIndexOf('\n', limit - 1).takeIf { it >= start + maxChunkLength / 2 }
        val end = lineBreak?.plus(1) ?: limit
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

@Composable
internal fun MessageItem(
    entry: MessageListEntry,
    workspaceRootPath: String? = null,
    isSelected: Boolean = false,
    onToggleSelection: (Conversation.Message.Id, Boolean) -> Unit = { _, _ -> },
    onToggleContentItemCollapse: (Conversation.Message.Id, Int) -> Unit = { _, _ -> },
    onManualContentResize: () -> Unit = {},
    expandedActivityKeys: Set<String> = emptySet(),
    onToggleActivityExpansion: (String) -> Unit = {},
    activitySummaryStyle: ActivitySummaryStyle = ActivitySummaryStyle.ICONS,
    loadArtifactContent: suspend (com.gromozeka.domain.model.Artifact.Id) -> ByteArray,
) {
    val message = entry.message
    val selectionBorderColor = MaterialTheme.colorScheme.primary
    val userBackground = message.role == Conversation.Message.Role.USER &&
        message.content.any { it is Conversation.Message.ContentItem.UserMessage }

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(start = (entry.activityDepth * 12).dp)
            .then(entry.groupContentKey?.let { Modifier.testTag(UiTestTag.ActivityGroupContent(it).value) } ?: Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (entry.isFirstInMessage) {
                        Modifier.testTag(UiTestTag.MessageItem(message.id.value).value)
                    } else {
                        Modifier
                    }
                )
                .background(
                    color = if (userBackground) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    } else {
                        Color.Transparent
                    }
                )
                .messageSelectionInput(message.id, onToggleSelection)
                .drawBehind {
                    if (!isSelected) return@drawBehind

                    val strokeWidth = 3.dp.toPx()
                    if (entry.isFirstInMessage && entry.isLastInMessage) {
                        drawRoundRect(
                            color = selectionBorderColor,
                            style = Stroke(width = strokeWidth),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    } else {
                        val halfStroke = strokeWidth / 2
                        drawLine(
                            color = selectionBorderColor,
                            start = androidx.compose.ui.geometry.Offset(halfStroke, 0f),
                            end = androidx.compose.ui.geometry.Offset(halfStroke, size.height),
                            strokeWidth = strokeWidth,
                        )
                        drawLine(
                            color = selectionBorderColor,
                            start = androidx.compose.ui.geometry.Offset(size.width - halfStroke, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width - halfStroke, size.height),
                            strokeWidth = strokeWidth,
                        )
                        if (entry.isFirstInMessage) {
                            drawLine(
                                color = selectionBorderColor,
                                start = androidx.compose.ui.geometry.Offset(0f, halfStroke),
                                end = androidx.compose.ui.geometry.Offset(size.width, halfStroke),
                                strokeWidth = strokeWidth,
                            )
                        }
                        if (entry.isLastInMessage) {
                            drawLine(
                                color = selectionBorderColor,
                                start = androidx.compose.ui.geometry.Offset(0f, size.height - halfStroke),
                                end = androidx.compose.ui.geometry.Offset(size.width, size.height - halfStroke),
                                strokeWidth = strokeWidth,
                            )
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier.padding(
                    start = if (message.role == Conversation.Message.Role.USER) 12.dp else 4.dp,
                    end = 4.dp,
                )
            ) {
                if (entry.isFirstInMessage) {
                    message.author?.displayName?.takeIf(String::isNotBlank)?.let { displayName ->
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                SelectionContainer {
                    MessageSegmentContent(
                        entry = entry,
                        workspaceRootPath = workspaceRootPath,
                        onToggleContentItemCollapse = onToggleContentItemCollapse,
                        onManualContentResize = onManualContentResize,
                        expandedActivityKeys = expandedActivityKeys,
                        onToggleActivityExpansion = onToggleActivityExpansion,
                        activitySummaryStyle = activitySummaryStyle,
                        loadArtifactContent = loadArtifactContent,
                    )
                }
            }
        }

        if (entry.isLastInMessage) {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun Modifier.messageSelectionInput(
    messageId: Conversation.Message.Id,
    onToggleSelection: (Conversation.Message.Id, Boolean) -> Unit,
): Modifier = pointerInput(messageId) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            if (down.changes.any { it.pressed && !it.previousPressed } && down.buttons.isPrimaryPressed) {
                val downPosition = down.changes.first().position
                val isShiftPressed = down.keyboardModifiers.isShiftPressed
                var isDrag = false
                do {
                    val event = awaitPointerEvent()
                    val currentPosition = event.changes.first().position
                    if ((currentPosition - downPosition).getDistance() > 10f) {
                        isDrag = true
                        break
                    }
                } while (event.changes.any { it.pressed })

                if (!isDrag) {
                    down.changes.forEach { it.consume() }
                    onToggleSelection(messageId, isShiftPressed)
                }
            }
        }
    }
}

@Composable
private fun MessageSegmentContent(
    entry: MessageListEntry,
    workspaceRootPath: String?,
    onToggleContentItemCollapse: (Conversation.Message.Id, Int) -> Unit,
    onManualContentResize: () -> Unit,
    expandedActivityKeys: Set<String>,
    onToggleActivityExpansion: (String) -> Unit,
    activitySummaryStyle: ActivitySummaryStyle,
    loadArtifactContent: suspend (com.gromozeka.domain.model.Artifact.Id) -> ByteArray,
) {
    when (val segment = entry.segment) {
        is MessageSegment.MarkdownBlock -> MarkdownSegmentLayout(
            messageId = entry.message.id,
            kind = segment.kind,
            contentIndex = segment.contentIndex,
            isFirstInContent = segment.isFirstInContent,
            isLastInContent = segment.isLastInContent,
            onToggleContentItemCollapse = onToggleContentItemCollapse,
            onManualContentResize = onManualContentResize,
        ) {
            GromozekaMarkdownNode(
                state = segment.state,
                node = segment.node,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is MessageSegment.RawMarkdownBlock -> MarkdownSegmentLayout(
            messageId = entry.message.id,
            kind = segment.kind,
            contentIndex = segment.contentIndex,
            isFirstInContent = segment.isFirstInContent,
            isLastInContent = segment.isLastInContent,
            onToggleContentItemCollapse = onToggleContentItemCollapse,
            onManualContentResize = onManualContentResize,
        ) {
            Text(text = segment.text)
        }

        is MessageSegment.CollapsedMarkdown -> CollapsedMarkdownContent(
            messageId = entry.message.id,
            segment = segment,
            onToggleContentItemCollapse = onToggleContentItemCollapse,
            onManualContentResize = onManualContentResize,
        )

        is MessageSegment.Content -> GenericContentItem(
            content = segment.content,
            loadArtifactContent = loadArtifactContent,
        )

        is MessageSegment.Activity -> ChatActivityItem(
            activity = segment.activity,
            activityKey = entry.sourceKey,
            isExpanded = activityExpansionKey(entry.sourceKey) in expandedActivityKeys,
            onToggleExpanded = {
                onManualContentResize()
                onToggleActivityExpansion(activityExpansionKey(entry.sourceKey))
            },
            workspaceRootPath = workspaceRootPath,
            loadArtifactContent = loadArtifactContent,
        )

        is MessageSegment.ActivityGroup -> ActivityGroupItem(
            group = segment,
            groupKey = entry.key,
            isExpanded = activityGroupExpansionKey(entry.key) in expandedActivityKeys,
            onToggleExpanded = {
                onManualContentResize()
                onToggleActivityExpansion(activityGroupExpansionKey(entry.key))
            },
            summaryStyle = activitySummaryStyle,
        )

        is MessageSegment.Instructions -> InstructionChips(
            instructions = entry.message.instructions,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )

        MessageSegment.Error -> MessageError(entry.message)
    }
}

@Composable
private fun MarkdownSegmentLayout(
    messageId: Conversation.Message.Id,
    kind: MarkdownKind,
    contentIndex: Int,
    isFirstInContent: Boolean,
    isLastInContent: Boolean,
    onToggleContentItemCollapse: (Conversation.Message.Id, Int) -> Unit,
    onManualContentResize: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (kind) {
        MarkdownKind.USER -> content()

        MarkdownKind.ASSISTANT -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        content()
                    }
                    if (isFirstInContent) {
                        CollapseButton(
                            isCollapsed = false,
                            onClick = {
                                onManualContentResize()
                                onToggleContentItemCollapse(messageId, contentIndex)
                            },
                        )
                    }
                }
                if (isLastInContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        MarkdownKind.DETAIL -> Column(modifier = Modifier.fillMaxWidth()) {
            content()
            if (isLastInContent) Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CollapsedMarkdownContent(
    messageId: Conversation.Message.Id,
    segment: MessageSegment.CollapsedMarkdown,
    onToggleContentItemCollapse: (Conversation.Message.Id, Int) -> Unit,
    onManualContentResize: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(48.dp)
            .clipToBounds()
            .alpha(0.5f),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                GromozekaMarkdown(content = segment.text)
            }
            CollapseButton(
                isCollapsed = true,
                onClick = {
                    onManualContentResize()
                    onToggleContentItemCollapse(messageId, segment.contentIndex)
                },
            )
        }
    }
}

@Composable
private fun CollapseButton(
    isCollapsed: Boolean,
    onClick: () -> Unit,
) {
    val localization = LocalTranslation.current
    DisableSelection {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        ) {
            Icon(
                imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (isCollapsed) localization.text("runtime.expandDescription") else localization.text("runtime.collapseDescription"),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun GenericContentItem(
    content: Conversation.Message.ContentItem,
    loadArtifactContent: suspend (com.gromozeka.domain.model.Artifact.Id) -> ByteArray,
) {
    val localization = LocalTranslation.current
    when (content) {
        is Conversation.Message.ContentItem.ToolCall -> error("Tool calls require an activity segment")

        is Conversation.Message.ContentItem.ImageItem -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Image, contentDescription = localization.text("chat.attachment.image"))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                when (val source = content.source) {
                    is Conversation.Message.ImageSource.Base64ImageSource -> Text(
                        LocalTranslation.current.format("imageDisplayText",
                            source.mediaType,
                            source.data.length,
                        )
                    )

                    is Conversation.Message.ImageSource.UrlImageSource -> Text(source.url)
                    is Conversation.Message.ImageSource.FileImageSource -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Image, contentDescription = localization.text("chat.attachment.image"))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(localization.text("chat.attachment.remoteFile", "fileId" to source.fileId))
                    }
                }
            }
        }

        is Conversation.Message.ContentItem.DocumentItem -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.AttachFile, contentDescription = localization.text("chat.attachment.document"))
            when (val source = content.source) {
                is Conversation.Message.DocumentSource.Base64DocumentSource ->
                    Text("${source.fileName} · ${source.mediaType}")
            }
        }

        is Conversation.Message.ContentItem.ArtifactItem -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (content.artifact.kind == com.gromozeka.domain.model.Artifact.Kind.IMAGE) {
                        Icons.Default.Image
                    } else {
                        Icons.Default.AttachFile
                    },
                    contentDescription = localization.text("chat.attachment.generic"),
                )
                Column {
                    Text(content.artifact.fileName)
                    Text(
                        "${content.artifact.mediaType} · ${content.artifact.sizeBytes.formatArtifactSize()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (content.artifact.kind == com.gromozeka.domain.model.Artifact.Kind.IMAGE) {
                ArtifactImagePreview(content.artifact, loadArtifactContent)
            }
        }

        is Conversation.Message.ContentItem.System -> Text(text = content.content)
        is Conversation.Message.ContentItem.ContextCompactionResult -> ContextCompactionResultItem(content)
        is Conversation.Message.ContentItem.UnknownJson -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = localization.text("chat.attachment.parseError"),
                tint = MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = jsonPrettyPrint(content.json))
                Text(text = LocalTranslation.current.parseErrorText)
            }
        }

        is Conversation.Message.ContentItem.UserMessage,
        is Conversation.Message.ContentItem.Thinking,
        is Conversation.Message.ContentItem.AssistantMessage,
        is Conversation.Message.ContentItem.ToolResult -> Unit
    }
}

@Composable
internal fun ArtifactImagePreview(
    artifact: com.gromozeka.domain.model.Artifact.Reference,
    loadArtifactContent: suspend (com.gromozeka.domain.model.Artifact.Id) -> ByteArray,
) {
    val localization = LocalTranslation.current
    var bitmap by remember(artifact.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(artifact.id) { mutableStateOf(false) }

    LaunchedEffect(artifact.id) {
        runCatching { loadArtifactContent(artifact.id).decodeToImageBitmap() }
            .onSuccess { bitmap = it }
            .onFailure { failed = true }
    }

    when {
        bitmap != null -> Image(
            bitmap = requireNotNull(bitmap),
            contentDescription = artifact.fileName,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )

        failed -> Text(
            text = localization.text("chat.attachment.previewUnavailable"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

private fun Long?.formatArtifactSize(): String = when {
    this == null -> "—"
    this >= 1024 * 1024 -> "${this / (1024 * 1024)} MB"
    this >= 1024 -> "${this / 1024} KB"
    else -> "$this B"
}

@Composable
private fun MessageError(message: Conversation.Message) {
    val localization = LocalTranslation.current
    val error = message.error ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = localization.text("chat.error.title"),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = error.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ContextCompactionResultItem(
    content: Conversation.Message.ContentItem.ContextCompactionResult,
) {
    val localization = LocalTranslation.current
    val title = when (content.origin) {
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED -> localization.text("chat.compaction.userRequested")
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY -> localization.text("chat.compaction.policy")
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO -> localization.text("chat.compaction.provider")
        Conversation.Message.ContentItem.ContextCompactionResult.Origin.RUNTIME_MIGRATION -> localization.text("chat.compaction.migration")
    }
    val details = when (val payload = content.payload) {
        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text.trim()
        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
            providerCompactionDetails(content.providerScope?.provider, payload.state, localization)
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

private fun providerCompactionDetails(provider: String?, state: JsonObject, localization: Translation): String {
    val metadata = state["compact_metadata"] as? JsonObject
    val trigger = metadata?.get("trigger")?.jsonPrimitive?.contentOrNull
        ?: state["trigger"]?.jsonPrimitive?.contentOrNull
    val preTokens = metadata?.get("pre_tokens")?.jsonPrimitive?.longOrNull
        ?: metadata?.get("preTokens")?.jsonPrimitive?.longOrNull
    return buildList {
        add(provider ?: localization.text("chat.compaction.unknownProvider"))
        add(localization.text("chat.compaction.providerManaged"))
        trigger?.let(::add)
        preTokens?.let { add(localization.plural("chat.compaction.tokensBefore", it)) }
    }.joinToString(" · ")
}

@Composable
private fun InstructionChips(
    instructions: List<Conversation.Message.Instruction>,
    modifier: Modifier = Modifier,
) {
    if (instructions.isEmpty()) return
    val translation = LocalTranslation.current

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
                            text = instruction.displayTitle(translation),
                            style = MaterialTheme.typography.labelSmall,
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
