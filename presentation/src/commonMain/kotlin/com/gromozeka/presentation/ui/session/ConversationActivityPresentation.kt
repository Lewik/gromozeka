package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.gromozeka.domain.service.AiRequestActivityService
import com.gromozeka.domain.service.AiRequestActivitySnapshot
import com.gromozeka.domain.service.AiRequestWaitReason
import com.gromozeka.domain.service.visibleTurnProblems
import com.gromozeka.presentation.services.VoiceTranscriptionActivity
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private sealed interface RequestActivityLoad {
    data object Loading : RequestActivityLoad
    data class Available(val value: AiRequestActivitySnapshot) : RequestActivityLoad
    data object Failed : RequestActivityLoad
}

internal data class ConversationActivityPresentation(
    val indicator: ConversationIndicatorState,
    val tooltip: String,
    val canControl: Boolean,
    val modelRequest: ModelRequestActivity?,
)

@Composable
internal fun rememberConversationActivityPresentation(
    viewModel: TabViewModel,
    unread: Boolean,
    connected: Boolean,
    activityService: AiRequestActivityService,
    voice: List<VoiceTranscriptionActivity>,
): ConversationActivityPresentation {
    val translation = LocalTranslation.current
    val runtime by viewModel.runtimeSnapshot.collectAsState()
    val generation by viewModel.activeGeneration.collectAsState()
    val waiting by viewModel.isWaitingForResponse.collectAsState()
    val pauseRequested by viewModel.executionPauseRequested.collectAsState()
    val uploading by remember(viewModel) {
        viewModel.uiState.map { it.composerArtifactUploadInProgress }.distinctUntilChanged()
    }.collectAsState(initial = viewModel.uiState.value.composerArtifactUploadInProgress)
    val regenerating by viewModel.suggestedRepliesRegeneratingFor.collectAsState()
    val tabId = viewModel.uiState.value.tabId
    val load = key(viewModel.conversationId, connected) {
        val flow = remember(activityService, viewModel.conversationId, connected) {
            if (!connected) flowOf<RequestActivityLoad>(RequestActivityLoad.Loading) else
                activityService.observe(viewModel.conversationId)
                    .map<AiRequestActivitySnapshot, RequestActivityLoad> { RequestActivityLoad.Available(it) }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        KLoggers.logger("ConversationActivity").error(error) { "AI request activity observation failed" }
                        emit(RequestActivityLoad.Failed)
                    }
        }
        val value by flow.collectAsState(initial = RequestActivityLoad.Loading)
        value
    }
    val snapshot = (load as? RequestActivityLoad.Available)?.value
    val state = conversationIndicatorState(runtime, generation, waiting, pauseRequested,
        locallyBusy = uploading || regenerating != null || voice.any { it.target.tabId.value == tabId && it.error == null },
        unread = unread, connected = connected, load = snapshot)
    val modelWait = state.waitReasons.takeIf { it.isNotEmpty() }?.let { reasons ->
        val labels = reasons.sortedBy { it.ordinal }.map { reason ->
            translation.text(if (reason == AiRequestWaitReason.TPM) "chat.activity.waitingTpm" else "chat.activity.waitingConcurrency")
        }
        ExecutionActivity("agent", labels.joinToString(" · "), generation?.startedAt ?: runtime?.state?.activeTaskStartedAt)
    }
    val activities = executionActivities(runtime, generation, waiting, voice, tabId, translation,
        uploadingArtifacts = uploading, regeneratingSuggestions = regenerating != null, modelRequestActivityOverride = modelWait)
    val first = activities.firstOrNull()
    val status = when (state.activity) {
        ConversationActivity.UNKNOWN -> if (connected) translation.text("chat.activity.stateLoading") else translation.runtime.offlineStatus
        ConversationActivity.PAUSING -> translation.runtime.pauseRequestedStatus
        ConversationActivity.PAUSED -> translation.runtime.pausedStatus
        ConversationActivity.STOPPING -> translation.runtime.stoppingStatus
        else -> first?.label ?: translation.text("chat.activity.idle")
    }
    val tooltip = buildList {
        add(status)
        activities.mapNotNull { it.detail ?: it.label }.filter { it != status }.distinct().forEach(::add)
        if (unread) add(translation.text("chat.activity.unread"))
        val problems = runtime?.visibleTurnProblems().orEmpty()
        if (problems.isNotEmpty()) {
            add(translation.text("chat.activity.turnProblems", "count" to problems.size))
            problems.takeLast(3).forEach { add(it.message.take(300)) }
        }
        add("")
        if (!connected || load == RequestActivityLoad.Failed) add(translation.text("chat.activity.loadUnavailable"))
        else if (snapshot == null) add(translation.text("chat.activity.loadLoading"))
        else {
            add(translation.text("chat.activity.loadRunning", "count" to snapshot.runningRequests))
            add(translation.text("chat.activity.loadTpm", "count" to snapshot.waitingForTpm))
            add(translation.text("chat.activity.loadConcurrency", "count" to snapshot.waitingForConcurrency))
        }
    }.joinToString("\n")
    return ConversationActivityPresentation(
        indicator = state,
        tooltip = tooltip,
        canControl = connected && (waiting || generation != null || runtime.hasControllableWork()),
        modelRequest = modelRequestActivity(generation, runtime, state, tooltip),
    )
}
