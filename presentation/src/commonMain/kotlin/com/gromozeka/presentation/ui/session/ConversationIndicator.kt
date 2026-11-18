package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.AiRequestActivitySnapshot
import com.gromozeka.domain.service.AiRequestWaitReason
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.visibleTurnProblems
import com.gromozeka.presentation.ui.GromozekaBulb
import com.gromozeka.presentation.ui.GromozekaRing
import com.gromozeka.presentation.ui.gromozekaRingStrokeWidth
import com.gromozeka.presentation.ui.rememberGromozekaLoadingPhase

internal enum class ConversationActivity {
    UNKNOWN, READY, WORKING, PAUSING, PAUSED, STOPPING;

    val isActive: Boolean get() = this == WORKING || this == PAUSING || this == STOPPING
}
internal enum class ConversationPressure { UNKNOWN, NORMAL, GLOBAL_WAIT, LOCAL_WAIT }
internal enum class ConversationIndicatorDrawing(val size: Dp) { FULL(28.dp), COMPACT(14.dp) }
internal enum class ConversationCenterMark { NONE, READY, UNREAD, PAUSED, STOPPING }

internal enum class CompactIndicatorContent { HIDDEN, UNREAD_ONLY, STATUS }

internal enum class CompactIndicatorMode {
    ALWAYS,
    ACTIVITY_OR_ATTENTION;

    fun content(state: ConversationIndicatorState): CompactIndicatorContent = when {
        this == ALWAYS || state.activity != ConversationActivity.READY ||
            state.pressure != ConversationPressure.NORMAL || state.hasTurnProblem || state.waitReasons.isNotEmpty() ->
            CompactIndicatorContent.STATUS
        state.unread -> CompactIndicatorContent.UNREAD_ONLY
        else -> CompactIndicatorContent.HIDDEN
    }
}

@Composable
internal fun CompactConversationIndicator(
    state: ConversationIndicatorState,
    description: String,
    modifier: Modifier = Modifier,
    mode: CompactIndicatorMode = CompactIndicatorMode.ALWAYS,
) {
    // Keep the slot when hidden so changing activity does not move the tab title.
    Box(modifier.size(ConversationIndicatorDrawing.COMPACT.size)) {
        when (mode.content(state)) {
            CompactIndicatorContent.HIDDEN -> Unit
            CompactIndicatorContent.UNREAD_ONLY -> {
                val color = indicatorAttentionColor(MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }
                    .testTag("conversation-indicator-mark-UNREAD")) {
                    drawCircle(color, unreadDotRadius.toPx(), center)
                }
            }
            CompactIndicatorContent.STATUS ->
                ConversationIndicator(state, ConversationIndicatorDrawing.COMPACT, description)
        }
    }
}

private val unreadDotRadius = 3.dp

private fun indicatorAttentionColor(dark: Boolean): Color =
    if (dark) Color(0xFFFF5A64) else Color(0xFFC02030)

internal data class ConversationIndicatorState(
    val activity: ConversationActivity,
    val pressure: ConversationPressure,
    val unread: Boolean,
    val hasTurnProblem: Boolean,
    val waitReasons: Set<AiRequestWaitReason>,
) {
    val animated: Boolean get() = activity.isActive
    val centerMark: ConversationCenterMark get() = when {
        activity == ConversationActivity.PAUSED || activity == ConversationActivity.PAUSING -> ConversationCenterMark.PAUSED
        activity == ConversationActivity.STOPPING -> ConversationCenterMark.STOPPING
        unread -> ConversationCenterMark.UNREAD
        activity == ConversationActivity.READY -> ConversationCenterMark.READY
        else -> ConversationCenterMark.NONE
    }
    val cornerUnread: Boolean get() = unread && (activity == ConversationActivity.PAUSED || activity == ConversationActivity.PAUSING || activity == ConversationActivity.STOPPING)
}

internal fun conversationIndicatorState(
    runtime: ConversationRuntimeSnapshot?,
    generation: ActiveGenerationSnapshot?,
    waiting: Boolean,
    pauseRequested: Boolean,
    locallyBusy: Boolean,
    unread: Boolean,
    connected: Boolean,
    load: AiRequestActivitySnapshot?,
): ConversationIndicatorState {
    val control = runtime?.state?.controlState
    val activity = when {
        !connected -> ConversationActivity.UNKNOWN
        control == ConversationExecutionState.ControlState.STOPPING || control == ConversationExecutionState.ControlState.INTERRUPTING -> ConversationActivity.STOPPING
        control == ConversationExecutionState.ControlState.PAUSED -> ConversationActivity.PAUSED
        pauseRequested || control == ConversationExecutionState.ControlState.PAUSE_REQUESTED -> ConversationActivity.PAUSING
        locallyBusy || waiting || generation != null || runtime?.activeTask != null || runtime?.continuationTask != null ||
            runtime?.activeInsertions.orEmpty().isNotEmpty() || runtime?.pendingTasks.orEmpty().isNotEmpty() ||
            runtime?.proactiveCompaction?.status == com.gromozeka.domain.service.ProactiveCompaction.Status.RUNNING -> ConversationActivity.WORKING
        runtime == null -> ConversationActivity.UNKNOWN
        else -> ConversationActivity.READY
    }
    val activeTaskIds = (listOfNotNull(runtime?.activeTask?.id,
        runtime?.proactiveCompaction?.takeIf { it.pending }?.sourceTaskId) + runtime?.activeInsertions.orEmpty().map { it.id }).toSet()
    val waits = if (connected) load?.taskWaits.orEmpty().filter { it.taskId in activeTaskIds }.mapTo(mutableSetOf()) { it.reason } else emptySet()
    val pressure = when {
        !connected || load == null -> ConversationPressure.UNKNOWN
        waits.isNotEmpty() -> ConversationPressure.LOCAL_WAIT
        activity.isActive && (load.waitingForTpm > 0 || load.waitingForConcurrency > 0) -> ConversationPressure.GLOBAL_WAIT
        else -> ConversationPressure.NORMAL
    }
    return ConversationIndicatorState(activity, pressure, unread, runtime?.visibleTurnProblems().orEmpty().isNotEmpty() ||
        runtime?.proactiveCompaction?.status == com.gromozeka.domain.service.ProactiveCompaction.Status.FAILED, waits)
}

@Composable
internal fun ConversationIndicator(
    state: ConversationIndicatorState,
    drawing: ConversationIndicatorDrawing,
    description: String,
    modifier: Modifier = Modifier,
) {
    val phase = if (state.animated) rememberGromozekaLoadingPhase() else remember { mutableFloatStateOf(0f) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val outline = when (state.pressure) {
        ConversationPressure.UNKNOWN -> MaterialTheme.colorScheme.outline
        ConversationPressure.NORMAL -> if (dark) Color(0xFFFE8B17) else Color(0xFFC86400)
        ConversationPressure.GLOBAL_WAIT -> if (dark) Color(0xFFFFE14A) else Color(0xFF967000)
        ConversationPressure.LOCAL_WAIT -> if (dark) Color(0xFFACB2BC) else Color(0xFF686E78)
    }
    val red = indicatorAttentionColor(dark)
    Box(modifier.size(drawing.size).then(if (state.animated) Modifier.progressSemantics() else Modifier)
        .semantics { contentDescription = description }) {
        val activityPhase: () -> Float? = { if (state.animated) phase.value else null }
        when (drawing) {
            ConversationIndicatorDrawing.FULL -> GromozekaBulb(Modifier.fillMaxSize(), phase = activityPhase,
                ready = state.centerMark == ConversationCenterMark.READY, outlineColor = outline,
                baseColor = Color(0xFF1266D0), errorRayColor = red.takeIf { state.hasTurnProblem })
            ConversationIndicatorDrawing.COMPACT -> GromozekaRing(Modifier.fillMaxSize(), phase = activityPhase,
                ready = state.centerMark == ConversationCenterMark.READY, outlineColor = outline,
                errorRayColor = red.takeIf { state.hasTurnProblem })
        }
        Canvas(Modifier.fillMaxSize().testTag("conversation-indicator-mark-${state.centerMark.name}")) {
            val center = Offset(size.width / 2f, size.height * if (drawing == ConversationIndicatorDrawing.FULL) 0.35f else 0.5f)
            val stroke = if (drawing == ConversationIndicatorDrawing.FULL) size.minDimension * 48f / 800f else gromozekaRingStrokeWidth.toPx()
            when (state.centerMark) {
                ConversationCenterMark.UNREAD -> drawCircle(red, unreadDotRadius.toPx(), center)
                ConversationCenterMark.PAUSED -> {
                    val dx = stroke * 1.2f
                    val half = stroke * 1.4f
                    drawLine(outline, center + Offset(-dx, -half), center + Offset(-dx, half), stroke, StrokeCap.Round)
                    drawLine(outline, center + Offset(dx, -half), center + Offset(dx, half), stroke, StrokeCap.Round)
                }
                ConversationCenterMark.STOPPING -> {
                    val side = stroke * 2.5f
                    drawRect(outline, center - Offset(side / 2f, side / 2f), Size(side, side))
                }
                else -> Unit
            }
            if (state.cornerUnread) {
                drawCircle(red, 1.75.dp.toPx(), Offset(size.width, size.height))
            }
        }
    }
}
