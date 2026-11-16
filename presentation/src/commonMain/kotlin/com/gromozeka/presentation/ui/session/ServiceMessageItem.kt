package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.PromptSnapshot
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.GromozekaMarkdown
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal enum class ServiceMessageKind(val titleKey: String) {
    PEER_INFORMATION("chat.service.peerInformation"),
    PEER_ACTIVATION("chat.service.peerActivation"),
    PEER_REQUEST("chat.service.peerRequest"),
    PEER_RESULT("chat.service.peerResult"),
    RUNTIME("chat.service.runtime"),
    COMMAND("chat.service.commandFinished"),
    MONITOR("chat.service.monitorUpdate"),
    PROMPT_ATTACHED("chat.service.promptAttached"),
    PROMPT_REPLACED("chat.service.promptReplaced"),
    PROMPT_DETACHED("chat.service.promptDetached"),
    VISUAL("chat.service.visualUpdated"),
}

internal fun ContentItem.serviceKind(): ServiceMessageKind = when (this) {
    is ContentItem.PeerMessage -> when (kind) {
        ContentItem.PeerMessage.Kind.INFORMATION -> ServiceMessageKind.PEER_INFORMATION
        ContentItem.PeerMessage.Kind.ACTIVATION -> ServiceMessageKind.PEER_ACTIVATION
        ContentItem.PeerMessage.Kind.REQUEST -> ServiceMessageKind.PEER_REQUEST
        ContentItem.PeerMessage.Kind.RESULT -> ServiceMessageKind.PEER_RESULT
    }
    is ContentItem.RuntimeNotification -> when (kind) {
        ContentItem.RuntimeNotification.Kind.COMMAND_COMPLETED -> ServiceMessageKind.COMMAND
        ContentItem.RuntimeNotification.Kind.MONITOR_OUTPUT -> ServiceMessageKind.MONITOR
        else -> ServiceMessageKind.RUNTIME
    }
    is ContentItem.PromptMessage -> when (action) {
        ContentItem.PromptMessage.Action.ATTACH -> ServiceMessageKind.PROMPT_ATTACHED
        ContentItem.PromptMessage.Action.REPLACE -> ServiceMessageKind.PROMPT_REPLACED
        ContentItem.PromptMessage.Action.DETACH -> ServiceMessageKind.PROMPT_DETACHED
    }
    is ContentItem.VisualUpdated -> ServiceMessageKind.VISUAL
    else -> error("Not a service message block")
}

internal fun serviceMessageTitle(block: ContentItem, translation: Translation): String {
    val label = translation.text(block.serviceKind().titleKey)
    return when (block) {
        is ContentItem.PeerMessage -> {
            val source = block.sender.conversationTitle.ifBlank { translation.text("common.newConversation") }
            "${block.sender.agentName} · «$source» → ${block.recipient.agentName} · $label"
        }
        is ContentItem.RuntimeNotification -> listOfNotNull(label,
            translation.text("runtime.failedTaskLabel").takeIf { block.level == ContentItem.RuntimeNotification.Level.ERROR },
            block.content.lineSequence().firstOrNull()?.take(180)?.takeIf(String::isNotBlank)).joinToString(" · ")
        is ContentItem.PromptMessage -> "$label · ${block.agentName}"
        is ContentItem.VisualUpdated -> listOf(label, block.visuals.joinToString { it.title }.take(180))
            .filter(String::isNotBlank).joinToString(" · ")
        else -> error("Not a service message block")
    }
}

internal fun serviceIconSpec(kind: ServiceMessageKind): ToolIconSpec = when (kind) {
    ServiceMessageKind.PEER_INFORMATION -> ToolIconSpec(Icons.Default.SmartToy, Icons.Default.ChatBubbleOutline)
    ServiceMessageKind.PEER_ACTIVATION -> ToolIconSpec(Icons.Default.SmartToy, Icons.Default.Send)
    ServiceMessageKind.PEER_REQUEST -> ToolIconSpec(Icons.Default.SmartToy, Icons.Default.ListAlt)
    ServiceMessageKind.PEER_RESULT -> ToolIconSpec(Icons.Default.SmartToy, Icons.Default.ArrowBack)
    ServiceMessageKind.RUNTIME -> ToolIconSpec(Icons.Default.DeveloperBoard)
    ServiceMessageKind.COMMAND -> ToolIconSpec(Icons.Default.Terminal)
    ServiceMessageKind.MONITOR -> ToolIconSpec(Icons.Default.Visibility)
    ServiceMessageKind.PROMPT_ATTACHED -> ToolIconSpec(Icons.Default.Description, Icons.Default.Add)
    ServiceMessageKind.PROMPT_REPLACED -> ToolIconSpec(Icons.Default.Description, Icons.Default.Refresh)
    ServiceMessageKind.PROMPT_DETACHED -> ToolIconSpec(Icons.Default.Description, Icons.Default.Close)
    ServiceMessageKind.VISUAL -> ToolIconSpec(Icons.Default.RuntimePanel, Icons.Default.Refresh)
}

@Composable
internal fun ServiceMessageItem(
    block: ContentItem,
    activityKey: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    loadArtifactContent: suspend (Artifact.Id) -> ByteArray,
    onOpenConversation: ((Conversation.Id) -> Unit)?,
    onOpenMessage: ((Conversation.Message.Id) -> Unit)?,
    replacementPromptId: Conversation.Message.Id? = null,
) {
    val translation = LocalTranslation.current
    val title = serviceMessageTitle(block, translation).let {
        if (block is ContentItem.PromptMessage && replacementPromptId != null) "$it · ${translation.text("chat.service.promptSuperseded")}" else it
    }
    Column(Modifier.fillMaxWidth().testTag("service-message:$activityKey")) {
        DisableSelection {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SemanticActivityIcon(serviceIconSpec(block.serviceKind()), translation.text(block.serviceKind().titleKey), Modifier.size(28.dp))
                    if (block is ContentItem.RuntimeNotification && block.level != ContentItem.RuntimeNotification.Level.INFO) {
                        Icon(if (block.level == ContentItem.RuntimeNotification.Level.ERROR) Icons.Default.Error else Icons.Default.Warning,
                            block.level.name, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (block is ContentItem.PeerMessage && onOpenConversation != null) {
                        IconButton(onClick = { onOpenConversation(block.sender.conversationId) },
                            modifier = Modifier.size(32.dp).testTag("service-source:$activityKey")) {
                            Icon(Icons.Default.OpenInNew, translation.text("chat.service.openSource"), Modifier.size(16.dp))
                        }
                    }
                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (isExpanded) translation.runtime.collapseDescription else translation.runtime.expandDescription,
                        Modifier.size(16.dp))
                }
            }
        }
        if (isExpanded) Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (block) {
                is ContentItem.PeerMessage -> {
                    GromozekaMarkdown(block.text)
                    Text("source_thread: ${block.sender.threadId.value}", style = MaterialTheme.typography.labelSmall)
                    block.requestId?.let { Text("request_id: $it", style = MaterialTheme.typography.labelSmall) }
                }
                is ContentItem.RuntimeNotification -> {
                    if (block.content.isNotBlank()) Text(block.content)
                    if (block.details.isNotEmpty()) Text(serviceJson.encodeToString(JsonObject.serializer(), block.details), fontFamily = FontFamily.Monospace)
                    block.attachments.forEach { data -> when (data) {
                        is ContentItem.ToolResult.Data.Text -> Text(data.content, fontFamily = FontFamily.Monospace)
                        is ContentItem.ToolResult.Data.ArtifactData -> GenericContentItem(content = ContentItem.ArtifactItem(data.artifact),
                            providerMetadata = JsonObject(emptyMap()), loadArtifactContent = loadArtifactContent, onManualContentResize = {})
                        is ContentItem.ToolResult.Data.UrlData -> Text(data.url)
                        is ContentItem.ToolResult.Data.Base64Data -> Text(data.fileName ?: data.mediaType.value)
                    } }
                }
                is ContentItem.PromptMessage -> {
                    Text("SHA-256: ${block.snapshot.fingerprint}", style = MaterialTheme.typography.labelSmall)
                    block.previousMessageId?.let { previous ->
                        if (onOpenMessage != null) TextButton(onClick = { onOpenMessage(previous) }) {
                            Text(translation.text("chat.service.previousPrompt"))
                        }
                    }
                    replacementPromptId?.let { replacement ->
                        if (onOpenMessage != null) TextButton(onClick = { onOpenMessage(replacement) }) {
                            Text(translation.text("chat.service.newerPrompt"))
                        }
                    }
                    block.snapshot.entries.forEach { entry -> when (entry) {
                        is PromptSnapshot.Entry.Catalog -> {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            Text(entry.id.value, style = MaterialTheme.typography.labelSmall)
                            GromozekaMarkdown(entry.text)
                        }
                        PromptSnapshot.Entry.Environment -> Text("env", fontFamily = FontFamily.Monospace)
                    } }
                }
                is ContentItem.VisualUpdated -> Text(serviceJson.encodeToString(block), fontFamily = FontFamily.Monospace)
                else -> error("Not a service message block")
            }
        }
    }
}

private val serviceJson = Json { prettyPrint = true }
