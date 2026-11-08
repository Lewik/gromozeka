package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.presentation.ui.LoadingIndicatorTone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal fun conversationLoadingTone(runtime: ConversationRuntimeSnapshot?, waiting: Boolean): LoadingIndicatorTone? = when {
    runtime?.state?.controlState != ConversationExecutionState.ControlState.PAUSED &&
        (waiting || runtime?.activeTask != null || runtime?.activeInsertions.orEmpty().isNotEmpty() ||
            runtime?.continuationTask != null || runtime?.pendingTasks.orEmpty().isNotEmpty()) -> LoadingIndicatorTone.FOREGROUND
    runtime?.commandTasks.orEmpty().any { !it.isTerminal } ||
        runtime?.commandMonitors.orEmpty().any { !it.isTerminal } -> LoadingIndicatorTone.BACKGROUND
    else -> null
}

internal fun commandLoadingTone(runtime: ConversationRuntimeSnapshot?, command: CommandTask): LoadingIndicatorTone =
    if (runtime.waitsForActivity(command.launchReference?.toolCallId, "grz_get_command_task", "task_id", command.id.value))
        LoadingIndicatorTone.FOREGROUND else LoadingIndicatorTone.BACKGROUND

internal fun monitorLoadingTone(runtime: ConversationRuntimeSnapshot?, monitor: CommandMonitor): LoadingIndicatorTone =
    if (runtime.waitsForActivity(monitor.launchReference?.toolCallId, "grz_get_command_monitor", "monitor_id", monitor.id.value))
        LoadingIndicatorTone.FOREGROUND else LoadingIndicatorTone.BACKGROUND

private fun ConversationRuntimeSnapshot?.waitsForActivity(
    launchCallId: String?, waitTool: String, argumentName: String, activityId: String,
): Boolean {
    if (this == null) return false
    return toolExecutions.any { execution ->
        if (execution.status != ConversationRuntimeToolExecution.Status.RUNNING) return@any false
        // Launch correlation includes the original tool's bounded initial wait, not all running commands.
        if (launchCallId != null && execution.toolCallId.value == launchCallId) return@any true
        val payload = (sequenceOf(activeTask).plus(activeInsertions.asSequence()).filterNotNull()
            .firstOrNull { it.id == execution.runtimeTaskId }?.payload as? ConversationRuntimeTask.Payload.ToolExecution)
            ?: return@any false
        val call = payload.toolCalls.firstOrNull { it.id == execution.toolCallId } ?: return@any false
        val toolName = payload.executionToolNamesByCallId[call.id.value] ?: call.call.name
        if (toolName.replace(toolVersionSuffix, "") != waitTool) return@any false
        val input = call.call.input as? JsonObject ?: return@any false
        if ((input[argumentName] as? JsonPrimitive)?.contentOrNull != activityId) return@any false
        // Both native get-command tools default to a positive wait when wait_ms is omitted.
        val wait = input["wait_ms"]
        wait == null || (wait as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true
    }
}

private val toolVersionSuffix = Regex("__v[0-9]+$")
