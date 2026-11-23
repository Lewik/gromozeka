package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gromozeka.domain.model.Artifact
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlin.math.floor

internal val activityIconSize = 28.dp

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
        is ChatActivity.Reasoning -> {
            val summaryTitle = remember(activity.block.thinking) { reasoningSummaryTitle(activity.block.thinking) }
            ActivityHeader(
                kind = ActivityKind.Reasoning,
                title = summaryTitle ?: with(LocalTranslation.current.runtime) {
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
    val translation = LocalTranslation.current
    val statusIcon = Icons.Default.Schedule.takeIf {
        state == ActivityState.RUNNING && kind == ActivityKind.Reasoning
    }
    // Row with main tool button + optional action button
    // Main tool call button with status + name + description
    DisableSelection {
        Surface(
            modifier = modifier.padding(bottom = 4.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.then(if (canExpand) Modifier.clickable(onClick = onToggleExpanded) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActivityIcon(kind, modifier = Modifier.size(26.dp), state = state)
                // Status icon (only for in-progress or error)
                statusIcon?.let {
                    val description = when (state) {
                        ActivityState.RUNNING -> translation.text("runtime.runningTaskLabel")
                        ActivityState.FAILED -> translation.text("runtime.failedTaskLabel")
                        ActivityState.INTERRUPTED -> translation.text("chat.activity.interrupted")
                        ActivityState.COMPLETE -> null
                    }
                    Icon(it, contentDescription = description, modifier = Modifier.size(16.dp))
                }
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

private data class ActiveActivitySummary(val references: List<ActivityReference>, val title: String) {
    val kind: ActivityKind get() = references.first().activity.kind
}

@Composable
internal fun ActivityGroupItem(
    group: MessageSegment.ActivityGroup,
    groupKey: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onModelRequestClick: () -> Unit,
    isCompactLayout: Boolean,
    workspaceRootPath: String?,
) {
    val translation = LocalTranslation.current
    val active = group.activities.filter { it.activity.state == ActivityState.RUNNING }
    fun title(reference: ActivityReference): String = when (val activity = reference.activity) {
        is ChatActivity.Tool -> toolCallDisplayTitle(activity.call.call, activity.state, workspaceRootPath, translation)
        is ChatActivity.Reasoning -> reasoningSummaryTitle(activity.block.thinking) ?: translation.runtime.thinkingLabel
    }
    val live = if (isCompactLayout) active.groupBy { it.activity.kind }.values.map { references ->
        ActiveActivitySummary(references, references.joinToString("\n", transform = ::title))
    } else active.map { ActiveActivitySummary(listOf(it), title(it)) }
    val terminalSummaries = summarizeActivities(group.activities.map(ActivityReference::activity))
        .filter { it.state != ActivityState.RUNNING }
    val completedSummaries = terminalSummaries.filter { it.state.groupSection == ActivityGroupSection.COMPLETED }
    val problemSummaries = terminalSummaries.filter { it.state.groupSection == ActivityGroupSection.PROBLEMS }
    val hasTerminalActivity = terminalSummaries.isNotEmpty()
    val groupDescription = buildList {
        add(translation.text("chat.activity.groupCount", "count" to group.activities.size))
        if (problemSummaries.any { it.state == ActivityState.FAILED }) add(translation.text("runtime.failedTaskLabel"))
        if (problemSummaries.any { it.state == ActivityState.INTERRUPTED }) add(translation.text("chat.activity.interrupted"))
    }.joinToString(" · ")
    val textStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val desiredCaptions = if (isCompactLayout) emptyList() else live.map {
        measurer.measure(AnnotatedString(it.title), style = textStyle, maxLines = 1, softWrap = false).size.width
    }
    DisableSelection {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = if (isExpanded) 0.dp else 4.dp)
                .testTag(UiTestTag.ActivityGroup(groupKey).value),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggleExpanded)
                .semantics { contentDescription = groupDescription }
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                check(maxWidth.value.isFinite()) { "Activity header requires a bounded width" }
                val density = LocalDensity.current
                val icon = with(density) { activityIconSize.roundToPx() }
                val spacing = icon
                val arrow = with(density) { 24.dp.roundToPx() }
                val ellipsis = with(density) { 18.dp.roundToPx() }
                val textGap = with(density) { 4.dp.roundToPx() }
                val overflowGap = with(density) { 2.dp.roundToPx() }
                val usable = (with(density) { maxWidth.roundToPx() } - arrow).coerceAtLeast(0)
                val slotCount = live.size + if (group.modelRequest != null) 1 else 0
                val leadingGap = if (hasTerminalActivity && slotCount > 0) spacing else 0
                val summarySlot = with(density) { (activityIconSize + 2.dp).roundToPx() }
                val completedNatural = completedSummaries.size * summarySlot
                val problemNatural = problemSummaries.size * summarySlot
                val historyNatural = completedNatural + problemNatural +
                    if (completedSummaries.isNotEmpty() && problemSummaries.isNotEmpty()) spacing else 0
                val activeNatural = leadingGap + slotCount * icon + (slotCount - 1).coerceAtLeast(0) * spacing
                val historyWidth = if (slotCount == 0) minOf(historyNatural, usable) else
                    minOf(historyNatural, (usable - activeNatural).coerceAtLeast(minOf(ellipsis, usable)))
                val activeWidth = (usable - historyWidth).coerceAtLeast(0)
                val overflow = activeNatural > activeWidth
                val visibleSlots = if (!overflow) slotCount else
                    ((activeWidth - leadingGap - ellipsis - overflowGap + spacing).coerceAtLeast(0) / (icon + spacing)).coerceAtMost(slotCount)
                val modelVisible = group.modelRequest != null && visibleSlots > 0
                val visibleTools = live.take((visibleSlots - if (modelVisible) 1 else 0).coerceAtLeast(0))
                val footprint = leadingGap + visibleSlots * icon + (visibleSlots - 1).coerceAtLeast(0) * spacing +
                    if (overflow) ellipsis + overflowGap else 0
                val captions = if (isCompactLayout) List(visibleTools.size) { 0 } else allocateActivityCaptionWidths(
                    desiredCaptions.take(visibleTools.size).map { it + textGap }, activeWidth - footprint, ellipsis + textGap,
                )
                val problemWidth = minOf(problemNatural, historyWidth)
                val remainingHistory = (historyWidth - problemWidth).coerceAtLeast(0)
                val showCompleted = completedSummaries.isNotEmpty() &&
                    (problemSummaries.isEmpty() || remainingHistory >= spacing + minOf(ellipsis, summarySlot))
                val sectionGap = if (showCompleted && problemSummaries.isNotEmpty()) spacing else 0
                val completedWidth = if (problemSummaries.isEmpty()) historyWidth else
                    if (showCompleted) (remainingHistory - sectionGap).coerceAtLeast(0) else 0
                fun dp(pixels: Int) = with(density) { pixels.toDp() }

                Row(Modifier.fillMaxWidth().heightIn(min = activityIconSize), verticalAlignment = Alignment.CenterVertically) {
                    if (hasTerminalActivity) {
                        Row(Modifier.width(dp(historyWidth)), verticalAlignment = Alignment.CenterVertically) {
                            if (completedWidth > 0) {
                                ActivitySummaryRow(completedSummaries, Modifier.width(dp(completedWidth)))
                            }
                            if (sectionGap > 0) Spacer(Modifier.width(dp(sectionGap)))
                            if (problemWidth > 0) {
                                ActivitySummaryRow(problemSummaries, Modifier.width(dp(problemWidth)))
                            }
                        }
                    }
                    if (visibleSlots > 0) Spacer(Modifier.width(dp(leadingGap)))
                    var shown = 0
                    if (modelVisible) {
                        ModelRequestIndicator(
                            request = checkNotNull(group.modelRequest),
                            onClick = onModelRequestClick,
                            modifier = Modifier.testTag("activity-model:$groupKey"),
                        )
                        shown++
                    }
                    visibleTools.forEachIndexed { index, summary ->
                        if (shown > 0) Spacer(Modifier.width(activityIconSize))
                        OptionalTooltip(summary.title) {
                            ActivityIcon(summary.kind, Modifier.size(activityIconSize), summary.references.size)
                        }
                        val width = (captions[index] - textGap).coerceAtLeast(0)
                        if (width > 0) {
                            Spacer(Modifier.width(dp(textGap)))
                            OptionalTooltip(summary.title) {
                                Text(summary.title, style = textStyle, maxLines = 1, softWrap = false,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(dp(width)))
                            }
                        }
                        shown++
                    }
                    if (overflow) {
                        if (shown > 0) Spacer(Modifier.width(dp(overflowGap)))
                        Text("…", style = textStyle, maxLines = 1, modifier = Modifier.width(dp(minOf(ellipsis, activeWidth))))
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { ExpansionIcon(isExpanded) }
                }
            }
        }
    }
}

@Composable
internal fun ModelRequestIndicator(
    request: ModelRequestActivity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detailsLabel = LocalTranslation.current.text("chat.activity.details")
    OptionalTooltip(request.description) {
        Box(
            modifier.size(activityIconSize).clickable(
                role = Role.Button,
                onClickLabel = detailsLabel,
                onClick = onClick,
            ),
            contentAlignment = Alignment.Center,
        ) {
            ConversationIndicator(request.indicator, ConversationIndicatorDrawing.COMPACT, request.description)
        }
    }
}

@Composable
private fun ActivitySummaryRow(summaries: List<ActivitySummary>, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val slotWidth = (activityIconSize + 2.dp).value
        val fits = summaries.size * slotWidth <= maxWidth.value
        val visibleCount = if (fits) summaries.size else
            floor((maxWidth.value - 18).coerceAtLeast(0f) / slotWidth).toInt().coerceAtMost(summaries.size)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            summaries.take(visibleCount).forEach { summary ->
                OptionalTooltip(summaryLabel(summary, LocalTranslation.current)) {
                    ActivityIcon(summary.kind, Modifier.size(activityIconSize), summary.count, summary.state)
                }
            }
            if (!fits) Text("…", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private fun summaryLabel(summary: ActivitySummary, translation: Translation): String {
    val label = when (val kind = summary.kind) {
        is ActivityKind.Tool -> toolDisplayName(kind.name, translation.runtime)
        ActivityKind.Reasoning -> translation.runtime.thinkingLabel
    }.let { if (summary.count > 1) "$it ×${summary.count}" else it }
    val status = when (summary.state) {
        ActivityState.FAILED -> translation.text("runtime.failedTaskLabel")
        ActivityState.INTERRUPTED -> translation.text("chat.activity.interrupted")
        ActivityState.COMPLETE, ActivityState.RUNNING -> null
    }
    return listOfNotNull(label, status).joinToString(" · ")
}

@Composable
private fun ActivityIcon(
    kind: ActivityKind,
    modifier: Modifier = Modifier,
    count: Int = 1,
    state: ActivityState = ActivityState.COMPLETE,
) {
    val localization = LocalTranslation.current
    Box(modifier) {
        when (kind) {
            is ActivityKind.Tool -> ToolSemanticIcon(
                kind.name,
                "${toolDisplayName(kind.name, localization.runtime)}: $count",
                Modifier.fillMaxSize(),
                count,
            )
            ActivityKind.Reasoning -> SemanticActivityIcon(
                spec = ToolIconSpec(Icons.Default.Psychology),
                contentDescription = localization.text("chat.activity.thinkingCount", "count" to count),
                modifier = Modifier.fillMaxSize(), invocationCount = count,
            )
        }
        ActivityStatusBadge(state)
    }
}

@Composable
private fun BoxScope.ActivityStatusBadge(state: ActivityState) {
    val translation = LocalTranslation.current
    val failed = state == ActivityState.FAILED
    if (!failed && state != ActivityState.INTERRUPTED) return
    val colors = MaterialTheme.colorScheme
    val description = translation.text(if (failed) "runtime.failedTaskLabel" else "chat.activity.interrupted")
    Surface(
        modifier = Modifier.size(13.dp).align(Alignment.BottomStart)
            .testTag("activity-status:${state.name.lowercase()}")
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = if (failed) colors.error else colors.surfaceContainerHighest,
        contentColor = if (failed) colors.onError else colors.onSurfaceVariant,
        border = if (failed) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (failed) {
                Text("!", fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun ExpansionIcon(isExpanded: Boolean) {
    val strings = LocalTranslation.current.runtime
    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = if (isExpanded) strings.collapseDescription else strings.expandDescription,
        modifier = Modifier.size(16.dp))
}
