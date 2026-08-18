package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag

@Composable
internal fun ToolActivityGroupItem(
    group: MessageSegment.ToolActivityGroup,
    toolResultsMap: Map<String, Conversation.Message.ContentItem.ToolResult>,
    workspaceRootPath: String?,
    loadArtifactContent: suspend (Artifact.Id) -> ByteArray,
) {
    val translation = LocalTranslation.current.runtime
    val firstToolCallId = group.calls.first().content.id.value
    val results = group.calls.map { toolResultsMap[it.content.id.value] }
    val unresolvedCount = results.count { it == null }
    val failedCount = results.count { it?.isError == true }
    val needsAttention = unresolvedCount > 0 || failedCount > 0
    var manualExpanded by remember(firstToolCallId) { mutableStateOf<Boolean?>(null) }
    val isExpanded = manualExpanded ?: needsAttention
    val activities = group.calls
        .map { reference ->
            toolActivityCaption(
                toolName = reference.content.call.name,
                input = reference.content.call.input,
                translation = translation,
            )
        }
        .distinct()
    val statusText = when {
        failedCount > 0 -> "${translation.failedTaskLabel}: $failedCount"
        unresolvedCount > 0 -> "${translation.runningTaskLabel}: $unresolvedCount"
        else -> activities.take(2).joinToString(" · ")
    }
    val statusIcon = when {
        failedCount > 0 -> Icons.Default.Error
        unresolvedCount > 0 -> Icons.Default.Schedule
        else -> Icons.Default.CheckCircle
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTestTag.ToolActivityGroup(firstToolCallId).value)
                .clickable { manualExpanded = !isExpanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(statusIcon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${translation.toolActivityGroupLabel} · ${group.calls.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) {
                    translation.collapseDescription
                } else {
                    translation.expandDescription
                },
            )
        }

        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTag.ToolActivityGroupContent(firstToolCallId).value)
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.calls.forEach { reference ->
                    androidx.compose.runtime.key(reference.content.id.value) {
                        ToolCallItem(
                            toolCall = reference.content.call,
                            toolResult = toolResultsMap[reference.content.id.value],
                            workspaceRootPath = workspaceRootPath,
                            loadArtifactContent = loadArtifactContent,
                        )
                    }
                }
            }
        }
    }
}
