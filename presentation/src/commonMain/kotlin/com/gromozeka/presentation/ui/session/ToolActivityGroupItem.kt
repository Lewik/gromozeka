package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import kotlin.math.floor

@Composable
internal fun ToolActivityGroupItem(
    group: MessageSegment.ToolActivityGroup,
    toolResultsMap: Map<String, Conversation.Message.ContentItem.ToolResult>,
    workspaceRootPath: String?,
    loadArtifactContent: suspend (Artifact.Id) -> ByteArray,
) {
    val translation = LocalTranslation.current.runtime
    val firstToolCallId = group.calls.first().content.id.value
    var isExpanded by remember(firstToolCallId) { mutableStateOf(false) }
    val activities = group.calls
        .map { reference ->
            toolActivityCaption(
                toolName = reference.content.call.name,
                input = reference.content.call.input,
                translation = translation,
            )
        }
        .distinct()
    val statusText = activities.take(2).joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTestTag.ToolActivityGroup(firstToolCallId).value)
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            if (isExpanded) {
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
            } else {
                Text(
                    text = "${translation.toolActivityGroupLabel} · ${group.calls.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(8.dp))
                ToolInvocationSummaryRow(
                    calls = group.calls,
                    modifier = Modifier.weight(1f),
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
                    .padding(10.dp),
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

internal data class ToolInvocationSummary(
    val toolName: String,
    val count: Int,
)

internal fun summarizeToolInvocations(calls: List<ToolCallReference>): List<ToolInvocationSummary> =
    calls
        .groupingBy { it.content.call.name }
        .eachCount()
        .map { (toolName, count) -> ToolInvocationSummary(toolName, count) }

@Composable
private fun ToolInvocationSummaryRow(
    calls: List<ToolCallReference>,
    modifier: Modifier = Modifier,
) {
    val summaries = remember(calls) { summarizeToolInvocations(calls) }
    BoxWithConstraints(modifier = modifier) {
        val iconWidth = 32f
        val spacing = 2f
        val ellipsisWidth = 18f
        val fullWidth = summaries.size * iconWidth + (summaries.size - 1).coerceAtLeast(0) * spacing
        val overflows = fullWidth > maxWidth.value
        val visibleCount = if (overflows) {
            floor((maxWidth.value - ellipsisWidth).coerceAtLeast(0f) / (iconWidth + spacing))
                .toInt()
                .coerceAtMost(summaries.size)
        } else {
            summaries.size
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            summaries.take(visibleCount).forEach { summary ->
                ToolSemanticIcon(
                    toolName = summary.toolName,
                    contentDescription = "${summary.toolName}: ${summary.count}",
                    modifier = Modifier.size(32.dp),
                    invocationCount = summary.count,
                )
            }
            if (overflows) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
