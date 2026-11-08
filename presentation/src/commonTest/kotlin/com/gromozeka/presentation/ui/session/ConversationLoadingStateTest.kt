package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.BackgroundActivityLaunchReference
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.presentation.ui.LoadingIndicatorTone.BACKGROUND
import com.gromozeka.presentation.ui.LoadingIndicatorTone.FOREGROUND
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ConversationLoadingStateTest {
    private val conversationId = Conversation.Id("conversation")
    private val now = Instant.fromEpochSeconds(100)
    private val command = CommandTask(
        id = CommandTask.Id("command"), conversationId = conversationId,
        workerId = ConversationRuntimeWorkerId("worker"), workspaceMountId = WorkspaceMount.Id("mount"),
        command = "sleep 60", workingDirectory = "/tmp", status = CommandTask.Status.WORKING,
        processId = 42, processStartedAt = now, outputFile = "/tmp/output", outputBytes = 0,
        createdAt = now, updatedAt = now,
        launchReference = BackgroundActivityLaunchReference("request", "launch"),
    )
    private val monitor = CommandMonitor(
        id = CommandMonitor.Id("monitor"), conversationId = conversationId, commandTaskId = command.id,
        workerId = command.workerId, workspaceMountId = command.workspaceMountId,
        filterCommand = "grep READY", mode = CommandMonitor.Mode.CONTINUOUS, startFrom = CommandMonitor.StartFrom.NOW,
        status = CommandMonitor.Status.WORKING, sourceOutputCursor = 0, processId = 43, processStartedAt = now,
        outputFile = "/tmp/monitor-output", errorFile = "/tmp/monitor-error", outputBytes = 0, eventOutputCursor = 0,
        createdAt = now, updatedAt = now,
        launchReference = BackgroundActivityLaunchReference("monitor-request", "monitor-launch"),
    )

    @Test
    fun idleAndTerminalActivitiesDoNotAnimate() {
        assertNull(conversationLoadingTone(null, false))
        assertNull(conversationLoadingTone(snapshot(), false))
        assertNull(conversationLoadingTone(snapshot().copy(
            commandTasks = listOf(command.copy(status = CommandTask.Status.COMPLETED)),
            commandMonitors = listOf(monitor.copy(status = CommandMonitor.Status.CANCELLED)),
        ), false))
        assertEquals(FOREGROUND, conversationLoadingTone(null, true))
    }

    @Test
    fun foregroundWinsOverBackgroundButPausedTurnDoesNotAnimate() {
        val background = snapshot().copy(commandTasks = listOf(command), commandMonitors = listOf(monitor))
        assertEquals(BACKGROUND, conversationLoadingTone(background, false))
        assertEquals(FOREGROUND, conversationLoadingTone(background, true))
        val paused = background.copy(state = ConversationExecutionState(
            conversationId, ConversationExecutionState.ControlState.PAUSED, null, updatedAt = now,
        ))
        assertEquals(BACKGROUND, conversationLoadingTone(paused, true))
        assertNull(conversationLoadingTone(paused.copy(commandTasks = emptyList(), commandMonitors = emptyList()), true))
    }

    @Test
    fun queuedAndContinuingWorkRemainForeground() {
        val task = waitRuntime().activeTask!!
        for (runtime in listOf(
            snapshot().copy(activeTask = task), snapshot().copy(activeInsertions = listOf(task)),
            snapshot().copy(continuationTask = task), snapshot().copy(pendingTasks = listOf(task)),
        )) assertEquals(FOREGROUND, conversationLoadingTone(runtime, false))
    }

    @Test
    fun onlyTheCommandWhoseLaunchIsStillRunningIsForeground() {
        val runtime = waitRuntime(callId = "launch", toolName = "grz_execute_command__v4")
        assertEquals(FOREGROUND, commandLoadingTone(runtime, command))
        assertEquals(BACKGROUND, commandLoadingTone(runtime, command.copy(
            id = CommandTask.Id("other"), launchReference = BackgroundActivityLaunchReference("other-request", "other-launch"),
        )))
        val returned = runtime.copy(toolExecutions = runtime.toolExecutions.map {
            it.copy(status = ConversationRuntimeToolExecution.Status.COMPLETED)
        })
        assertEquals(BACKGROUND, commandLoadingTone(returned, command))
        assertEquals(BACKGROUND, commandLoadingTone(null, command))
    }

    @Test
    fun explicitPositiveOrDefaultWaitReactivatesOnlyItsTarget() {
        assertEquals(FOREGROUND, commandLoadingTone(waitRuntime(), command))
        assertEquals(FOREGROUND, commandLoadingTone(waitRuntime(waitMs = 300_000), command))
        assertEquals(BACKGROUND, commandLoadingTone(waitRuntime(waitMs = 0), command))
        assertEquals(BACKGROUND, commandLoadingTone(waitRuntime(activityId = "other"), command))
        assertEquals(BACKGROUND, commandLoadingTone(waitRuntime(toolName = "external_get_command_task"), command))
    }

    @Test
    fun explicitWaitUsesRuntimeTaskAndResolvedToolName() {
        val runtime = waitRuntime(toolName = "public_alias", resolvedToolName = "grz_get_command_task__v3")
        assertEquals(FOREGROUND, commandLoadingTone(runtime, command))
        assertEquals(FOREGROUND, commandLoadingTone(runtime.copy(activeTask = null, activeInsertions = listOf(runtime.activeTask!!)), command))
        assertEquals(BACKGROUND, commandLoadingTone(runtime.copy(activeTask = null), command))
        assertEquals(BACKGROUND, commandLoadingTone(runtime.copy(toolExecutions = runtime.toolExecutions.map {
            it.copy(status = ConversationRuntimeToolExecution.Status.COMPLETED)
        }), command))
    }

    @Test
    fun monitorWaitDoesNotChangeItsSourceCommandAndViceVersa() {
        val waitingForMonitor = waitRuntime(toolName = "grz_get_command_monitor__v3", argumentName = "monitor_id", activityId = "monitor")
        assertEquals(FOREGROUND, monitorLoadingTone(waitingForMonitor, monitor))
        assertEquals(BACKGROUND, commandLoadingTone(waitingForMonitor, command))
        assertEquals(BACKGROUND, monitorLoadingTone(waitRuntime(), monitor))
        assertEquals(BACKGROUND, monitorLoadingTone(waitRuntime(
            toolName = "grz_get_command_monitor", argumentName = "monitor_id", activityId = "monitor", waitMs = 0,
        ), monitor))
    }

    @Test
    fun monitorLaunchHasItsOwnCorrelationAndRemainsBackgroundAfterReturn() {
        val runtime = waitRuntime(callId = "monitor-launch", toolName = "grz_monitor_command__v3")
        assertEquals(FOREGROUND, monitorLoadingTone(runtime, monitor))
        assertEquals(BACKGROUND, commandLoadingTone(runtime, command))
        assertEquals(BACKGROUND, monitorLoadingTone(snapshot(), monitor))
    }

    private fun snapshot() = ConversationRuntimeSnapshot(
        revision = 1, conversationId = conversationId, state = null, pendingTasks = emptyList(),
    )

    private fun waitRuntime(
        callId: String = "wait", toolName: String = "grz_get_command_task__v3",
        resolvedToolName: String? = null, argumentName: String = "task_id", activityId: String = "command",
        waitMs: Long? = null,
    ): ConversationRuntimeSnapshot {
        val call = ToolCall(ToolCall.Id(callId), ToolCall.Data(toolName, buildJsonObject {
            put(argumentName, activityId)
            waitMs?.let { put("wait_ms", it) }
        }))
        val task = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("tools"), conversationId = conversationId,
            parentTaskId = ConversationRuntimeTask.Id("parent"),
            payload = ConversationRuntimeTask.Payload.ToolExecution(
                rootUserMessageId = Conversation.Message.Id("root"), agentDefinitionId = AgentDefinition.Id("agent"),
                iteration = 1, toolCalls = listOf(call), returnDirect = false,
                executionTargetsByCallId = mapOf(callId to ConversationRuntimeTaskTarget.Server),
                executionToolNamesByCallId = resolvedToolName?.let { mapOf(callId to it) }.orEmpty(),
            ),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "tools",
            requirements = ConversationRuntimeTaskRequirements(
                ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server,
            ), createdAt = now,
        )
        return snapshot().copy(activeTask = task, toolExecutions = listOf(ConversationRuntimeToolExecution(
            toolCallId = call.id, toolName = toolName, status = ConversationRuntimeToolExecution.Status.RUNNING,
            runtimeTaskId = task.id, executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server")),
            startedAt = now,
        )))
    }
}
