package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.presentation.services.VoiceTranscriptionActivity
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator
import com.gromozeka.presentation.ui.GromozekaTabLoadingIndicator
import com.gromozeka.presentation.ui.GromozekaReadyIndicator
import com.gromozeka.presentation.ui.GromozekaBulb
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.RemoteConnectionStatus
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import org.jetbrains.compose.resources.DrawableResource
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

internal data class ExecutionActivity(
    val id: String,
    val label: String,
    val startedAt: Instant? = null,
    val progress: Float? = null,
    val failed: Boolean = false,
)

/** Foreground status only. Long-lived commands and monitors have independent indicators. */
internal fun executionActivities(
    snapshot: ConversationRuntimeSnapshot?,
    generation: ActiveGenerationSnapshot?,
    waiting: Boolean,
    voice: List<VoiceTranscriptionActivity>,
    tabId: String,
    translation: Translation,
    uploadingArtifacts: Boolean = false,
    regeneratingSuggestions: Boolean = false,
): List<ExecutionActivity> = buildList {
    voice.filter { it.target.tabId.value == tabId }.forEach { job ->
        add(ExecutionActivity(
            "voice:${job.id}",
            job.error?.resolve(translation) ?: (translation.runtime.transcribingVoiceStatus + " → " +
                translation.text(if (job.target.autoSend) "chat.voice.mode.auto" else "chat.voice.mode.draft")),
            job.startedAt, failed = job.error != null,
        ))
    }
    if (uploadingArtifacts) add(ExecutionActivity("upload", translation.text("chat.activity.uploading")))
    if (regeneratingSuggestions) add(ExecutionActivity("suggestions", translation.text("chat.activity.suggestions")))
    val tools = snapshot?.runningToolActivities(translation).orEmpty().distinct()
    if (tools.isNotEmpty()) {
        add(ExecutionActivity("tools", tools.joinToString(" · "), snapshot?.state?.activeTaskStartedAt))
    } else if (generation != null || snapshot?.activeTask != null || waiting) {
        add(ExecutionActivity("agent",
            snapshot?.activeTask?.payload?.runtimeStatusLabel(null, translation)
                ?: if (generation != null) translation.runtime.modelRequestStatus else translation.runtime.pendingTaskLabel,
            generation?.startedAt ?: snapshot?.state?.activeTaskStartedAt))
    }
}

internal fun ConversationRuntimeSnapshot?.hasControllableWork(): Boolean = this != null && (
    state != null || activeTask != null || continuationTask != null || pendingTasks.isNotEmpty()
)

@Composable
internal fun ConversationActivityBar(
    runtime: ConversationRuntimeSnapshot?,
    generation: ActiveGenerationSnapshot?,
    isWaiting: Boolean,
    pauseRequested: Boolean,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    voice: List<VoiceTranscriptionActivity>,
    tabId: String,
    voiceError: String?,
    connection: RemoteConnectionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit,
    runtimePanelVisible: Boolean,
    onToggleRuntimePanel: () -> Unit,
    uploadingArtifacts: Boolean = false,
    regeneratingSuggestions: Boolean = false,
    onInspectRuntime: (RuntimeInspectionSection) -> Unit = { onDetails() },
) {
    val translation = LocalTranslation.current
    val controls = GromozekaTheme.controls
    val spacing = GromozekaTheme.spacing
    val activities = executionActivities(runtime, generation, isWaiting, voice, tabId, translation, uploadingArtifacts, regeneratingSuggestions)
    val commands = runtime?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val monitors = runtime?.commandMonitors.orEmpty().activeForRuntimePanel()
    val problems = runtime?.lastTurn?.problems.orEmpty()
    val timed = activities.any { it.startedAt != null && !it.failed }
    val now by produceState(Clock.System.now(), timed) {
        if (timed) while (true) { delay(1_000); value = Clock.System.now() }
    }
    val state = runtime?.state?.controlState
    val paused = pauseRequested || state == ConversationExecutionState.ControlState.PAUSED ||
        state == ConversationExecutionState.ControlState.PAUSE_REQUESTED
    val stopping = state == ConversationExecutionState.ControlState.STOPPING || state == ConversationExecutionState.ControlState.INTERRUPTING
    val canControl = connection.status == RemoteConnectionState.Status.CONNECTED &&
        (isWaiting || generation != null || runtime.hasControllableWork())
    val status = when {
        state == ConversationExecutionState.ControlState.PAUSE_REQUESTED -> translation.runtime.pauseRequestedStatus
        state == ConversationExecutionState.ControlState.PAUSED -> translation.runtime.pausedStatus
        stopping -> translation.runtime.stoppingStatus
        pauseRequested -> translation.runtime.pauseRequestedStatus
        activities.isEmpty() -> translation.text("chat.activity.idle")
        else -> activities.first().label
    }
    val first = activities.firstOrNull()
    val percent = tokenStats?.takeIf { it.contextStatus != TokenUsageStatistics.ContextStatus.OUT_OF_RANGE }
        ?.let { stats -> stats.currentContextSize?.let { current -> stats.contextWindowTokens?.takeIf { it > 0 }?.let { limit -> (100L * current / limit).toInt().coerceIn(0, 100) } } }
    val commandHint = (listOf(translation.text("session.runtime.commandCount", "count" to commands.size)) +
        commands.map { "${it.workerId.value}: ${it.command.lineSequence().first().take(160)}" }).joinToString("\n")
    val monitorHint = (listOf(translation.text("session.runtime.monitorCount", "count" to monitors.size)) +
        monitors.map { "${it.workerId.value}: ${it.filterCommand.lineSequence().first().take(160)}" }).joinToString("\n")

    BoxWithConstraints(Modifier.fillMaxWidth().testTag("conversation-activity-bar")) {
        val compact = maxWidth < 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = controls.minHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.controlGap),
            ) {
                Box(Modifier.size(controls.minHeight).testTag("composer-state-indicator"), contentAlignment = Alignment.Center) {
                    when {
                        first?.failed == true || (activities.isEmpty() && !voiceError.isNullOrBlank()) ->
                            Icon(Icons.Default.ErrorOutline, null, Modifier.size(controls.iconSize), tint = MaterialTheme.colorScheme.error)
                        paused || stopping || connection.status != RemoteConnectionState.Status.CONNECTED ->
                            GromozekaBulb(Modifier.size(28.dp).testTag("composer-static-symbol"), phase = { null })
                        activities.isNotEmpty() -> GromozekaLoadingIndicator(Modifier.testTag("composer-loading-symbol"))
                        else -> GromozekaReadyIndicator(Modifier.testTag("composer-ready-symbol"))
                    }
                }
                Box(Modifier.weight(1f)) {
                    OptionalTooltip((listOf(status) + activities.drop(1).map { it.label }).joinToString("\n")) {
                        Text(status, Modifier.fillMaxWidth().clickable(onClickLabel = translation.text("chat.activity.details"), onClick = onDetails),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = if (first?.failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (!compact) {
                    first?.startedAt?.takeUnless { first.failed }?.let { Text(activityElapsed(it, now), style = MaterialTheme.typography.labelSmall) }
                    if (activities.size > 1) AdditionalActivities(activities.drop(1), now)
                }
                if (compact && commands.isNotEmpty() && monitors.isNotEmpty()) {
                    RuntimeActivityButton(Icons.Default.Terminal, commands.size + monitors.size, "$commandHint\n$monitorHint",
                        "composer-background-activities", animated = true,
                        onClick = { onInspectRuntime(RuntimeInspectionSection.COMMANDS) })
                } else {
                    if (commands.isNotEmpty()) RuntimeActivityButton(Icons.Default.Terminal, commands.size, commandHint,
                        "composer-background-commands", animated = true,
                        onClick = { onInspectRuntime(RuntimeInspectionSection.COMMANDS) })
                    if (monitors.isNotEmpty()) RuntimeActivityButton(Icons.Default.Visibility, monitors.size, monitorHint,
                        "composer-background-monitors", animated = true,
                        onClick = { onInspectRuntime(RuntimeInspectionSection.MONITORS) })
                }
                if (problems.isNotEmpty()) {
                    val uncertain = problems.all { it.outcomeUnknown }
                    RuntimeActivityButton(
                        icon = if (uncertain) Icons.Default.Warning else Icons.Default.ErrorOutline,
                        count = problems.size,
                        hint = translation.text("chat.activity.turnProblems", "count" to problems.size) + "\n" + problems.last().message.take(200),
                        tag = "composer-turn-problems",
                        tint = if (uncertain) Color(0xFFFE8B17) else MaterialTheme.colorScheme.error,
                        onClick = { onInspectRuntime(RuntimeInspectionSection.PROBLEMS) },
                    )
                }
                if (!compact) percent?.let {
                    TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = spacing.controlGap),
                        modifier = Modifier.size(controls.minHeight).testTag("composer-context-usage")) {
                        Text("$it%", style = MaterialTheme.typography.labelMedium)
                    }
                }
                // Keep both control slots in the layout, including while idle or stopping.
                val pauseLabel = if (paused) translation.runtime.resumeButton else translation.runtime.pauseButton
                OptionalTooltip(pauseLabel) {
                    IconButton(onClick = if (paused) onResume else onPause, enabled = canControl && !stopping,
                        modifier = Modifier.size(controls.minHeight).testTag("composer-pause-execution")) {
                        Icon(if (paused) Icons.Default.Play else Icons.Default.Pause, pauseLabel, Modifier.size(controls.iconSize))
                    }
                }
                OptionalTooltip(translation.runtime.stopButton) {
                    IconButton(onClick = onStop, enabled = canControl && !stopping,
                        modifier = Modifier.size(controls.minHeight).testTag("composer-stop-execution")) {
                        Icon(Icons.Default.Stop, translation.runtime.stopButton, Modifier.size(controls.iconSize))
                    }
                }
                val panelLabel = translation.text(if (runtimePanelVisible) "session.toolbar.hideRuntime" else "session.toolbar.showRuntime")
                CompactButton(
                    onClick = onToggleRuntimePanel,
                    modifier = Modifier.size(controls.minHeight).testTag("composer-runtime-details").semantics { selected = runtimePanelVisible },
                    tooltip = panelLabel,
                    contentPadding = PaddingValues(spacing.controlGap), elevation = null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (runtimePanelVisible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (runtimePanelVisible) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.RuntimePanel, panelLabel, Modifier.size(controls.iconSize))
                        if (compact) percent?.let { Text("$it%", Modifier.testTag("composer-context-usage"), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            first?.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(2.dp)) }
            activities.drop(1).filter { it.failed }.forEach { activity ->
                Text(activity.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!voiceError.isNullOrBlank() && activities.none { it.failed }) {
                Text(voiceError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (connection.status != RemoteConnectionState.Status.CONNECTED) {
                RemoteConnectionStatus(connection, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun RuntimeActivityButton(
    icon: DrawableResource,
    count: Int,
    hint: String,
    tag: String,
    animated: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val controls = GromozekaTheme.controls
    OptionalTooltip(hint) {
        IconButton(onClick, Modifier.size(controls.minHeight).testTag(tag)) {
            BadgedBox(badge = {
                if (count > 1) Badge(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = tint) {
                    Text(count.toString())
                }
            }) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    if (animated) GromozekaTabLoadingIndicator(Modifier.size(30.dp))
                    Icon(icon, hint, Modifier.size(controls.smallIconSize), tint = tint)
                }
            }
        }
    }
}

@Composable
private fun AdditionalActivities(activities: List<ExecutionActivity>, now: Instant) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OptionalTooltip(LocalTranslation.current.text("chat.activity.more", "count" to activities.size)) {
            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = GromozekaTheme.spacing.controlGap),
                modifier = Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-additional-activities")) {
                Text("+${activities.size}", style = MaterialTheme.typography.labelMedium)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f)) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.forEach { activity ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(activity.label, style = MaterialTheme.typography.bodySmall,
                            color = if (activity.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        activity.startedAt?.takeUnless { activity.failed }?.let { Text(activityElapsed(it, now), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

private fun activityElapsed(start: Instant, now: Instant): String {
    val seconds = (now - start).inWholeSeconds.coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
