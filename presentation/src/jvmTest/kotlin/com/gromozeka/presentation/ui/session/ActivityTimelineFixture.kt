package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Instant

internal fun activityTestMessage(id: String, vararg content: Conversation.Message.ContentItem) = Conversation.Message(
    id = Conversation.Message.Id(id),
    conversationId = Conversation.Id("activity-test"),
    role = Conversation.Message.Role.ASSISTANT,
    content = content.toList(),
    createdAt = Instant.fromEpochMilliseconds(0),
)

internal fun activityTestCall(id: String) = Conversation.Message.ContentItem.ToolCall(
    id = Conversation.Message.ContentItem.ToolCall.Id(id),
    call = Conversation.Message.ContentItem.ToolCall.Data("grz_read_file", buildJsonObject {}),
)

internal fun activityTestResults(messages: List<Conversation.Message>) = messages.flatMap { it.content }
    .filterIsInstance<Conversation.Message.ContentItem.ToolCall>().associate { call ->
        call.id.value to Conversation.Message.ContentItem.ToolResult(
            toolUseId = call.id, toolName = call.call.name,
            result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("Tool output ${call.id.value}")),
        )
    }

@Composable
internal fun ActivityTimelineFixture(
    messages: List<Conversation.Message>,
    results: Map<String, Conversation.Message.ContentItem.ToolResult> = activityTestResults(messages),
    summaryStyle: ActivitySummaryStyle = ActivitySummaryStyle.ICONS,
    onEntries: (List<MessageListEntry>) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val entries = rememberMessageListEntries(messages, emptyMap(), results, expanded)
    onEntries(entries)
    MaterialTheme {
        FollowLatestLazyColumn(
            items = entries,
            itemKey = MessageListEntry::key,
            contentRevision = entries,
            unreadLabel = { "new activity" },
            modifier = Modifier.fillMaxSize(),
        ) { entry, pauseFollowingLatest ->
            MessageItem(
                entry = entry,
                expandedActivityKeys = expanded,
                onToggleActivityExpansion = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
                onManualContentResize = pauseFollowingLatest,
                activitySummaryStyle = summaryStyle,
                loadArtifactContent = { byteArrayOf() },
            )
        }
    }
}
