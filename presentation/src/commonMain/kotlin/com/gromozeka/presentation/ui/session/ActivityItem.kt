package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Artifact
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlin.math.floor

internal enum class ActivitySummaryStyle {
    ICONS,
    LABELS,
    TEXT,
}

@Composable
internal fun ChatActivityItem(
    activity: ChatActivity,
    activityKey: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    workspaceRootPath: String?,
    loadArtifactContent: suspend (Artifact.Id) -> ByteArray,
) {
    val modifier = Modifier.testTag(UiTestTag.ActivityItem(activityKey).value)
    when (activity) {
        is ChatActivity.Tool -> ToolCallItem(
            toolCall = activity.call.call,
            toolResult = activity.result,
            workspaceRootPath = workspaceRootPath,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            activityState = activity.state,
            modifier = modifier,
            loadArtifactContent = loadArtifactContent,
        )
        is ChatActivity.Reasoning -> ActivityHeader(
            kind = ActivityKind.Reasoning,
            title = with(LocalTranslation.current.runtime) {
                if (activity.canExpand || activity.state == ActivityState.RUNNING) thinkingLabel else hiddenThinkingLabel
            },
            state = activity.state,
            canExpand = activity.canExpand,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            modifier = modifier,
        )
    }
}

@Composable
internal fun ActivityHeader(
    kind: ActivityKind,
    title: String,
    state: ActivityState,
    canExpand: Boolean,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Determine status icon based on toolResult (no icon on success)
    val statusIcon = when (state) {
        ActivityState.RUNNING -> Icons.Default.Schedule
        ActivityState.FAILED -> Icons.Default.Error
        ActivityState.INTERRUPTED -> Icons.Default.Stop
        ActivityState.COMPLETE -> null
    }
    // Row with main tool button + optional action button
    // Main tool call button with status + name + description
    DisableSelection {
        Surface(
            modifier = modifier.padding(bottom = 4.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            Row(
                modifier = Modifier.then(if (canExpand) Modifier.clickable(onClick = onToggleExpanded) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActivityIcon(kind, modifier = Modifier.size(26.dp))
                // Status icon (only for in-progress or error)
                statusIcon?.let { Icon(it, contentDescription = state.name, modifier = Modifier.size(16.dp)) }
                // Tool description with parameters
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (canExpand) ExpansionIcon(isExpanded)
            }
        }
    }
}

@Composable
internal fun ActivityGroupItem(
    group: MessageSegment.ActivityGroup,
    groupKey: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    summaryStyle: ActivitySummaryStyle = ActivitySummaryStyle.ICONS,
) {
    val strings = LocalTranslation.current.runtime
    val summaries = summarizeActivities(group.activities.map(ActivityReference::activity))
    DisableSelection {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                .testTag(UiTestTag.ActivityGroup(groupKey).value),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onToggleExpanded).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${strings.activityGroupLabel} · ${group.activities.size}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                ActivitySummaryRow(summaries, summaryStyle, Modifier.weight(1f))
                ExpansionIcon(isExpanded)
            }
        }
    }
}

@Composable
private fun ActivitySummaryRow(
    summaries: List<ActivitySummary>,
    style: ActivitySummaryStyle,
    modifier: Modifier,
) {
    val strings = LocalTranslation.current.runtime
    when (style) {
        ActivitySummaryStyle.TEXT -> Text(
            text = summaries.joinToString(" · ") { summaryLabel(it, strings) },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        ActivitySummaryStyle.LABELS -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            summaries.take(2).forEach { summary ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ActivityIcon(summary.kind, Modifier.size(24.dp))
                    Text(summaryLabel(summary, strings), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (summaries.size > 2) Text("…")
        }
        ActivitySummaryStyle.ICONS -> BoxWithConstraints(modifier) {
            val slotWidth = 30f
            val fits = summaries.size * slotWidth <= maxWidth.value
            val visibleCount = if (fits) summaries.size else
                floor((maxWidth.value - 18).coerceAtLeast(0f) / slotWidth).toInt().coerceAtMost(summaries.size)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                summaries.take(visibleCount).forEach { summary ->
                    ActivityIcon(summary.kind, Modifier.size(28.dp), summary.count)
                }
                if (!fits) Text("…", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun summaryLabel(summary: ActivitySummary, strings: Translation.RuntimeTranslation): String {
    val label = when (val kind = summary.kind) {
        is ActivityKind.Tool -> toolDisplayName(kind.name, strings)
        ActivityKind.Reasoning -> strings.thinkingLabel
    }
    return if (summary.count > 1) "$label ×${summary.count}" else label
}

@Composable
private fun ActivityIcon(kind: ActivityKind, modifier: Modifier = Modifier, count: Int = 1) {
    when (kind) {
        is ActivityKind.Tool -> ToolSemanticIcon(kind.name, "${kind.name}: $count", modifier, count)
        ActivityKind.Reasoning -> SemanticActivityIcon(
            spec = ToolIconSpec(Icons.Default.Psychology),
            contentDescription = "${LocalTranslation.current.runtime.thinkingLabel}: $count",
            modifier = modifier,
            invocationCount = count,
        )
    }
}

@Composable
private fun ExpansionIcon(isExpanded: Boolean) {
    val strings = LocalTranslation.current.runtime
    Icon(
        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = if (isExpanded) strings.collapseDescription else strings.expandDescription,
        modifier = Modifier.size(16.dp),
    )
}
