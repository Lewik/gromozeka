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
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.presentation.services.VoiceTranscriptionActivity
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator
import com.gromozeka.presentation.ui.GromozekaReadyIndicator
import com.gromozeka.presentation.ui.GromozekaBulb
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.RemoteConnectionStatus
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
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

/** A view of current state, never a log entry or a generated assistant message. */
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
            snapshot?.activeTask?.payload?.runtimeStatusLabel(null, translation) ?: translation.runtime.modelRequestStatus,
            generation?.startedAt ?: snapshot?.state?.activeTaskStartedAt))
    }
    snapshot?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }.forEach { command ->
        add(ExecutionActivity("command:${command.id}",
            translation.runtime.runningCommandActivity + ": " + command.command.lineSequence().first(),
            command.processStartedAt ?: command.createdAt))
    }
    snapshot?.memoryOperations.orEmpty().filter { it.status == MemoryRun.Status.RUNNING || it.status == MemoryRun.Status.QUEUED }.forEach { operation ->
        val progress = operation.progress?.takeIf { it.totalUnits > 0 }
            ?.let { (it.completedUnits.toFloat() / it.totalUnits).coerceIn(0f, 1f) }
        add(ExecutionActivity("memory:${operation.runId}", runtimeMemoryOperationLabel(operation.operation, translation), operation.startedAt, progress))
    }
    val queued = snapshot?.pendingTasks.orEmpty().size
    if (queued > 0) add(ExecutionActivity("queue", translation.text("session.runtime.queuedCount", "count" to queued)))
    val monitors = snapshot?.commandMonitors.orEmpty().activeForRuntimePanel().size
    if (monitors > 0) add(ExecutionActivity("monitors", translation.plural("session.runtime.monitorsRunning", monitors.toLong())))
}

internal fun ConversationRuntimeSnapshot?.hasControllableWork(): Boolean = this != null && (
    state != null || activeTask != null || continuationTask != null || pendingTasks.isNotEmpty() ||
        commandTasks.any { it.status == CommandTask.Status.WORKING }
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
) {
    val translation = LocalTranslation.current
    val activities = executionActivities(runtime, generation, isWaiting, voice, tabId, translation, uploadingArtifacts, regeneratingSuggestions)
    val timed = activities.any { it.startedAt != null && !it.failed }
    val now by produceState(Clock.System.now(), timed) {
        if (timed) while (true) { delay(1_000); value = Clock.System.now() }
    }
    val state = runtime?.state?.controlState
    val paused = pauseRequested || state == ConversationExecutionState.ControlState.PAUSED ||
        state == ConversationExecutionState.ControlState.PAUSE_REQUESTED
    val stopping = state == ConversationExecutionState.ControlState.STOPPING || state == ConversationExecutionState.ControlState.INTERRUPTING
    val canControl = isWaiting || generation != null || runtime.hasControllableWork()
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

    Column(
        modifier = Modifier.fillMaxWidth().testTag("conversation-activity-bar"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = GromozekaTheme.controls.minHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.controlGap),
        ) {
            Box(Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-state-indicator"), contentAlignment = Alignment.Center) {
                when {
                    first?.failed == true || (activities.isEmpty() && !voiceError.isNullOrBlank()) ->
                        Icon(Icons.Default.ErrorOutline, contentDescription = null,
                            modifier = Modifier.size(GromozekaTheme.controls.iconSize), tint = MaterialTheme.colorScheme.error)
                    paused || stopping || connection.status != RemoteConnectionState.Status.CONNECTED ->
                        GromozekaBulb(Modifier.size(28.dp).testTag("composer-static-symbol"), phase = { null })
                    activities.isNotEmpty() ->
                        GromozekaLoadingIndicator(Modifier.testTag("composer-loading-symbol"))
                    else -> GromozekaReadyIndicator(Modifier.testTag("composer-ready-symbol"))
                }
            }
            Text(status, Modifier.weight(1f).clickable(onClickLabel = translation.text("chat.activity.details"), onClick = onDetails), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (first?.failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            first?.startedAt?.takeUnless { first.failed }?.let {
                Text(activityElapsed(it, now), style = MaterialTheme.typography.labelSmall)
            }
            if (activities.size > 1) AdditionalActivities(activities.drop(1), now)
            percent?.let {
                TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = GromozekaTheme.spacing.controlGap), modifier = Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-context-usage")) {
                    Text("$it%", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (canControl && !stopping) {
                val pauseLabel = if (paused) translation.runtime.resumeButton else translation.runtime.pauseButton
                OptionalTooltip(pauseLabel) {
                    IconButton(onClick = if (paused) onResume else onPause, modifier = Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-pause-execution")) {
                        Icon(if (paused) Icons.Default.Play else Icons.Default.Pause, pauseLabel, Modifier.size(GromozekaTheme.controls.iconSize))
                    }
                }
                OptionalTooltip(translation.runtime.stopButton) {
                    IconButton(onClick = onStop, modifier = Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-stop-execution")) {
                        Icon(Icons.Default.Stop, contentDescription = translation.runtime.stopButton, modifier = Modifier.size(GromozekaTheme.controls.iconSize))
                    }
                }
            }
            val panelLabel = translation.text(if (runtimePanelVisible) "session.toolbar.hideRuntime" else "session.toolbar.showRuntime")
            CompactButton(
                onClick = onToggleRuntimePanel,
                modifier = Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-runtime-details")
                    .semantics { selected = runtimePanelVisible },
                tooltip = panelLabel,
                contentPadding = PaddingValues(GromozekaTheme.spacing.controlGap),
                elevation = null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (runtimePanelVisible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (runtimePanelVisible) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Default.RuntimePanel, contentDescription = panelLabel, modifier = Modifier.size(GromozekaTheme.controls.iconSize))
            }
        }
        first?.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(2.dp)) }
        // Failures stay visible even when additional background work is collapsed.
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

@Composable
private fun AdditionalActivities(activities: List<ExecutionActivity>, now: Instant) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OptionalTooltip(LocalTranslation.current.text("chat.activity.more", "count" to activities.size)) {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = GromozekaTheme.spacing.controlGap),
                modifier = Modifier.size(GromozekaTheme.controls.minHeight).testTag("composer-additional-activities"),
            ) { Text("+${activities.size}", style = MaterialTheme.typography.labelMedium) }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
        ) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.forEach { activity ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(activity.label, style = MaterialTheme.typography.bodySmall,
                            color = if (activity.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        activity.startedAt?.takeUnless { activity.failed }?.let {
                            Text(activityElapsed(it, now), style = MaterialTheme.typography.labelSmall)
                        }
                        activity.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(2.dp)) }
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
