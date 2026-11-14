package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.presentation.ui.ControlMetrics
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.presentation.ui.viewmodel.PendingUserMessage
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun ConversationFooterSummary(
    runtime: ConversationRuntimeSnapshot?,
    pending: List<PendingUserMessage>,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    lastUserMessageAt: Instant?,
    messageCount: Int,
    hasMoreMessages: Boolean,
    conversationId: Conversation.Id,
    slotLoad: SlotInventoryLoad,
    onOpenSlots: () -> Unit,
    onInspect: (RuntimeInspectionSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val translation = LocalTranslation.current
    val commands = runtime?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val monitors = runtime?.commandMonitors.orEmpty().activeForRuntimePanel()
    val clocks = conversationFooterClocks(runtime, lastUserMessageAt)
    val timed = clocks.running || commands.isNotEmpty()
    val now by produceState(Clock.System.now(), timed) {
        value = Clock.System.now()
        if (timed) while (true) { delay(1000); value = Clock.System.now() }
    }
    val unknown = translation.text("chat.activity.stateLoading")
    val occupancy = (slotLoad as? SlotInventoryLoad.Ready)?.catalog?.let {
        conversationSlotOccupancy(it.slots, conversationId, it.automaticRequests)
    }
    val slotsHint = when (slotLoad) {
        SlotInventoryLoad.Loading -> translation.text("slots.loading")
        is SlotInventoryLoad.Failed -> slotLoad.message ?: translation.text("slots.loadFailed")
        is SlotInventoryLoad.Ready -> buildString {
            val value = checkNotNull(occupancy)
            append(translation.text("slots.title"))
            append(": " + value.held.joinToString(", ").ifEmpty { "0" })
            if (value.pending.isNotEmpty()) append("\n" + translation.text("slots.pending") + ": " + value.pending.joinToString(", "))
            if (value.automaticPending > 0) append("\n" + translation.text("slots.pending") + ": " + translation.text("slots.automatic") + " (${value.automaticPending})")
        }
    }
    val pendingHint = if (runtime == null) unknown else buildString {
        append(translation.text("chat.footer.queued", "count" to pending.size))
        pending.orderedForDisplay().take(3).forEachIndexed { index, message ->
            val text = message.text.ifBlank { message.artifacts.joinToString { it.fileName } }
            append("\n${index + 1}. ${queuePlacementDescription(message.placement, translation.runtime)}: ${footerPreview(text)}")
        }
        if (pending.size > 3) append("\n" + translation.text("chat.activity.more", "count" to pending.size - 3))
    }
    val commandsHint = if (runtime == null) unknown else buildString {
        append(translation.text("chat.footer.commands", "count" to commands.size))
        commands.take(3).forEach { append("\n${footerPreview(it.displayLabel)} · ${activityElapsed(it.createdAt, now)}") }
        if (commands.size > 3) append("\n" + translation.text("chat.activity.more", "count" to commands.size - 3))
    }
    val monitorsHint = if (runtime == null) unknown else buildString {
        append(translation.text("chat.footer.monitors", "count" to monitors.size))
        monitors.take(3).forEach { append("\n${footerPreview(it.filterCommand)}") }
        if (monitors.size > 3) append("\n" + translation.text("chat.activity.more", "count" to monitors.size - 3))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = GromozekaTheme.spacing.contentPadding,
        vertical = GromozekaTheme.spacing.panelPadding).testTag("conversation-footer-summary"), verticalArrangement = Arrangement.Center) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.controlGap),
                verticalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.controlGap), itemVerticalAlignment = Alignment.CenterVertically) {
                ConversationFooterBadge(Icons.Default.ChatBubbleOutline, messageCount, translation.format("messageCountTooltip", messageCount),
                    Modifier.testTag("conversation-message-count"), hasMore = hasMoreMessages)
                ConversationFooterBadge(Icons.Default.FormatListBulleted, runtime?.let { pending.size }, pendingHint,
                    Modifier.testTag("conversation-queued-messages")) { onInspect(RuntimeInspectionSection.QUEUED_MESSAGES) }
                ConversationFooterBadge(Icons.Default.Terminal, runtime?.let { commands.size }, commandsHint,
                    Modifier.testTag("conversation-running-commands")) { onInspect(RuntimeInspectionSection.COMMANDS) }
                ConversationFooterBadge(Icons.Default.Visibility, runtime?.let { monitors.size }, monitorsHint,
                    Modifier.testTag("conversation-command-monitors")) { onInspect(RuntimeInspectionSection.MONITORS) }
                ConversationFooterBadge(Icons.Default.DeveloperBoard, occupancy?.held?.size, slotsHint,
                    Modifier.testTag("conversation-slots"), onClick = onOpenSlots)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.panelPadding), verticalAlignment = Alignment.CenterVertically) {
                val total = clocks.total?.let { conversationElapsedSince(it.startedAt, it.completedAt ?: now) } ?: "—"
                val request = clocks.request?.let { activityElapsed(it.startedAt, it.completedAt ?: now) } ?: "—"
                val hint = translation.text("chat.footer.elapsedTotal", "elapsed" to total) + "\n" +
                    translation.text("chat.footer.elapsedRequest", "elapsed" to request)
                OptionalTooltip(hint) {
                    Text("$total / $request", Modifier.testTag("conversation-elapsed-time").semantics { contentDescription = hint },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, softWrap = false)
                }
                ConversationContextUsageIndicator(tokenStats, onDetails = { onInspect(RuntimeInspectionSection.OVERVIEW) })
            }
        }
    }
}

internal data class FooterClock(val startedAt: Instant, val completedAt: Instant? = null)
internal data class ConversationFooterClocks(val total: FooterClock?, val request: FooterClock?) {
    val running: Boolean get() = total?.let { it.completedAt == null } == true || request?.let { it.completedAt == null } == true
}

internal fun conversationFooterClocks(runtime: ConversationRuntimeSnapshot?, lastUserMessageAt: Instant?): ConversationFooterClocks {
    fun ConversationRuntimeTask.expectsResponse(): Boolean = isAgentWork() ||
        (payload as? ConversationRuntimeTask.Payload.PostMessage)?.autoRespondAgentIds?.isNotEmpty() == true
    val responsePending = runtime != null && (listOfNotNull(runtime.activeTask, runtime.continuationTask) +
        runtime.activeInsertions + runtime.pendingTasks).any { it.expectsResponse() }
    val completedAt = runtime?.lastTurn?.completedAt
    val total = lastUserMessageAt?.let { sentAt ->
        when {
            responsePending -> FooterClock(sentAt)
            completedAt != null && completedAt >= sentAt -> FooterClock(sentAt, completedAt)
            else -> null
        }
    }
    val request = runtime?.lastTurn?.modelRequest?.let { timing ->
        when {
            timing.completedAt != null -> FooterClock(timing.startedAt, timing.completedAt)
            runtime.activeTask?.id == timing.taskId -> FooterClock(timing.startedAt)
            else -> null
        }
    }
    return ConversationFooterClocks(total, request)
}

internal fun conversationElapsedSince(since: Instant, now: Instant): String {
    val seconds = (now - since).inWholeSeconds.coerceAtLeast(0)
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    else "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun footerPreview(value: String): String = value.lineSequence().joinToString(" ").take(160)

@Composable
private fun ConversationFooterBadge(
    icon: DrawableResource, count: Int?, hint: String, modifier: Modifier,
    hasMore: Boolean = false, onClick: (() -> Unit)? = null,
) {
    val controls = GromozekaTheme.controls
    val spacing = GromozekaTheme.spacing
    val style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val label = count?.let { it.toString() + if (hasMore) "+" else "" } ?: "—"
    val showIcon = count == null || count < 10
    val width = with(LocalDensity.current) {
        maxOf(measurer.measure("0000", style).size.width.toDp(),
            measurer.measure(label, style).size.width.toDp() + if (showIcon) controls.smallIconSize + spacing.controlGap else 0.dp)
    }
    val green = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF82D9A3) else Color(0xFF216C3A)
    val target = if (onClick == null) modifier else modifier.clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
    Box(target.then(if (controls == ControlMetrics.Touch) Modifier.heightIn(min = controls.minHeight) else Modifier), contentAlignment = Alignment.Center) {
        OptionalTooltip(hint) {
            Surface(color = green.copy(alpha = 0.12f), contentColor = green, shape = MaterialTheme.shapes.extraLarge) {
                Row(Modifier.padding(horizontal = spacing.panelPadding, vertical = spacing.controlGap).width(width)
                    .semantics { contentDescription = hint }, horizontalArrangement = Arrangement.spacedBy(spacing.controlGap, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (showIcon) Icon(icon, null, Modifier.size(controls.smallIconSize))
                    Text(label, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
