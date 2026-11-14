package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.presentation.services.VoiceTranscriptionActivity
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.ControlMetrics
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlin.time.Instant

internal data class ExecutionActivity(
    val id: String,
    val label: String,
    val startedAt: Instant? = null,
    val progress: Float? = null,
    val failed: Boolean = false,
    val detail: String? = null,
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
    modelRequestActivityOverride: ExecutionActivity? = null,
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
        add(modelRequestActivityOverride ?: ExecutionActivity("agent",
            snapshot?.activeTask?.payload?.runtimeStatusLabel(null, translation)
                ?: if (generation != null) translation.runtime.modelRequestStatus else translation.runtime.pendingTaskLabel,
            generation?.startedAt ?: snapshot?.state?.activeTaskStartedAt))
    }
}

internal fun ConversationRuntimeSnapshot?.hasControllableWork(): Boolean = this != null && (
    state != null || activeTask != null || continuationTask != null || pendingTasks.isNotEmpty()
)

@Composable
internal fun ConversationExecutionControls(
    paused: Boolean, stopping: Boolean, canControl: Boolean,
    onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
) {
    val translation = LocalTranslation.current
    val controls = GromozekaTheme.controls
    Row(horizontalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.controlGap), verticalAlignment = Alignment.CenterVertically) {
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
    }
}

internal fun contextUsagePercent(tokenStats: TokenUsageStatistics.ThreadTotals?): Int? =
    tokenStats?.takeIf { it.contextStatus != TokenUsageStatistics.ContextStatus.OUT_OF_RANGE }
            ?.let { stats -> stats.currentContextSize?.let { current -> stats.contextWindowTokens?.takeIf { it > 0 }?.let { limit -> (100L * current / limit).toInt().coerceIn(0, 100) } } }

@Composable
internal fun ConversationContextUsageIndicator(tokenStats: TokenUsageStatistics.ThreadTotals?, onDetails: () -> Unit) {
    val translation = LocalTranslation.current
    val controls = GromozekaTheme.controls
    contextUsagePercent(tokenStats)?.let { percent ->
        val hint = translation.text("session.runtime.context.usageTooltip", "percent" to percent,
            "currentCount" to checkNotNull(tokenStats?.currentContextSize).formatWithCommas(),
            "limitCount" to checkNotNull(tokenStats?.contextWindowTokens).formatWithCommas())
        OptionalTooltip(hint) {
            val target = Modifier.testTag("composer-context-usage").semantics { contentDescription = hint }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClick = onDetails)
            Box(target.then(if (controls == ControlMetrics.Touch)
                Modifier.heightIn(min = controls.minHeight).widthIn(min = controls.minHeight) else Modifier),
                contentAlignment = Alignment.Center) {
                Text(contextUsageText(percent), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1, softWrap = false)
            }
        }
    }
}

internal fun contextUsageText(percent: Int): String {
    require(percent in 0..100)
    return if (percent == 100) "100" else "${percent.toString().padStart(2)}%"
}

internal fun activityElapsed(start: Instant, now: Instant): String {
    val seconds = (now - start).inWholeSeconds.coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
