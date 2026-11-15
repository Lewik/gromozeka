package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.BackgroundActivityLaunchReference
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.AiRequestActivitySnapshot
import com.gromozeka.domain.service.AiRequestTaskWait
import com.gromozeka.domain.service.AiRequestWaitReason
import com.gromozeka.domain.service.ConversationRuntimeTurnSummary
import com.gromozeka.domain.service.ConversationRuntimeTurnId
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
import kotlin.test.assertTrue
import kotlin.test.assertFalse
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

    private fun indicator(runtime: ConversationRuntimeSnapshot?, waiting: Boolean = false,
        unread: Boolean = false, connected: Boolean = true,
        load: AiRequestActivitySnapshot? = AiRequestActivitySnapshot(0, 0, 0, emptyList())) =
        conversationIndicatorState(runtime, null, waiting, false, false, unread, connected, load)

    @Test fun proactiveCompactionRemainsVisibleAndCancellableAfterTheAnswer() {
        val preparation = com.gromozeka.domain.service.ProactiveCompactionActivity("compaction", ConversationRuntimeTask.Id("source"),
            com.gromozeka.domain.service.ProactiveCompaction.Status.RUNNING, 100_000, 10, 2, now, now)
        val running = snapshot().copy(proactiveCompaction = preparation)
        assertTrue(running.hasControllableWork())
        assertEquals(ConversationActivity.WORKING, indicator(running).activity)
        val load = AiRequestActivitySnapshot(1, 0, 1, listOf(AiRequestTaskWait(preparation.sourceTaskId, AiRequestWaitReason.CONCURRENCY)))
        assertEquals(setOf(AiRequestWaitReason.CONCURRENCY), indicator(running, load = load).waitReasons)
        val ready = running.copy(proactiveCompaction = preparation.copy(status = com.gromozeka.domain.service.ProactiveCompaction.Status.READY))
        assertTrue(ready.hasControllableWork())
        assertEquals(ConversationActivity.READY, indicator(ready).activity)
        val cancelled = running.copy(proactiveCompaction = preparation.copy(status = com.gromozeka.domain.service.ProactiveCompaction.Status.CANCELLED))
        assertFalse(cancelled.hasControllableWork())
        assertEquals(ConversationActivity.READY, indicator(cancelled).activity)
        val unknown = running.copy(proactiveCompaction = preparation.copy(status = com.gromozeka.domain.service.ProactiveCompaction.Status.FAILED, outcomeUnknown = true))
        assertTrue(unknown.hasControllableWork())
    }

    @Test
    fun unknownStateDoesNotManufactureReadinessAndBackgroundCommandsStaySeparate() {
        assertEquals(ConversationActivity.UNKNOWN, indicator(null).activity)
        assertEquals(ConversationActivity.READY, indicator(snapshot()).activity)
        val background = snapshot().copy(commandTasks = listOf(command), commandMonitors = listOf(monitor))
        assertEquals(ConversationActivity.READY, indicator(background).activity)
        assertFalse(indicator(background).animated)
        assertEquals(ConversationActivity.WORKING, indicator(background, waiting = true).activity)
        assertTrue(indicator(null, waiting = true).animated)
    }

    @Test
    fun pausedAndPausingStatesKeepTheirActualMotionSemantics() {
        val paused = snapshot().copy(state = ConversationExecutionState(conversationId,
            ConversationExecutionState.ControlState.PAUSED, null, updatedAt = now))
        assertEquals(ConversationActivity.PAUSED, indicator(paused, waiting = true).activity)
        assertFalse(indicator(paused, waiting = true).animated)
        val requested = paused.copy(state = paused.state!!.copy(controlState = ConversationExecutionState.ControlState.PAUSE_REQUESTED))
        assertEquals(ConversationActivity.PAUSING, indicator(requested).activity)
        assertTrue(indicator(requested).animated)
        val stopping = paused.copy(state = paused.state!!.copy(controlState = ConversationExecutionState.ControlState.STOPPING))
        assertTrue(indicator(stopping).animated)
        assertEquals(ConversationCenterMark.STOPPING, indicator(stopping, unread = true).centerMark)
        assertTrue(indicator(stopping, unread = true).cornerUnread)
    }

    @Test
    fun queuedAndContinuingWorkRemainActive() {
        val task = waitRuntime().activeTask!!
        for (runtime in listOf(snapshot().copy(activeTask = task), snapshot().copy(activeInsertions = listOf(task)),
            snapshot().copy(continuationTask = task), snapshot().copy(pendingTasks = listOf(task)))) {
            assertEquals(ConversationActivity.WORKING, indicator(runtime).activity)
            assertTrue(indicator(runtime).animated)
        }
    }

    @Test
    fun localWaitIsMatchedByTaskNotInferredFromOtherConversationsOrOldRequests() {
        val runtime = waitRuntime()
        val task = runtime.activeTask!!.id
        val load = AiRequestActivitySnapshot(3, 2, 1, listOf(AiRequestTaskWait(task, AiRequestWaitReason.TPM)))
        assertEquals(ConversationPressure.LOCAL_WAIT, indicator(runtime, load = load).pressure)
        assertTrue(indicator(runtime, load = load).animated)
        val stale = load.copy(taskWaits = listOf(AiRequestTaskWait(ConversationRuntimeTask.Id("old"), AiRequestWaitReason.TPM)))
        assertEquals(ConversationPressure.GLOBAL_WAIT, indicator(runtime, load = stale).pressure)
        assertEquals(ConversationPressure.NORMAL, indicator(runtime).pressure)
        assertEquals(ConversationPressure.UNKNOWN, indicator(runtime, load = null).pressure)
        assertEquals(ConversationActivity.UNKNOWN, indicator(runtime, connected = false, load = load).activity)
    }

    @Test
    fun unreadReplacesOnlyReadinessAndDoesNotPretendThatWorkFinished() {
        assertEquals(ConversationCenterMark.UNREAD, indicator(snapshot(), unread = true).centerMark)
        assertEquals(ConversationCenterMark.READY, indicator(snapshot()).centerMark)
        assertEquals(ConversationCenterMark.UNREAD, indicator(waitRuntime(), unread = true).centerMark)
        assertEquals(ConversationCenterMark.NONE, indicator(waitRuntime()).centerMark)
        assertTrue(indicator(waitRuntime(), unread = true).animated)
    }

    @Test
    fun retainedProblemsStayIndependentOfCurrentActivityAndPressure() {
        val problem = ConversationRuntimeTurnSummary(ConversationRuntimeTurnId("previous"), now,
            listOf(ConversationRuntimeTurnSummary.Problem("problem", "Failed", now)))
        val runtime = waitRuntime().copy(lastTurn = ConversationRuntimeTurnSummary(ConversationRuntimeTurnId("current"), now),
            retainedTurnProblems = problem)
        val state = indicator(runtime, load = AiRequestActivitySnapshot(1, 1, 0, emptyList()))
        assertTrue(state.hasTurnProblem)
        assertEquals(ConversationActivity.WORKING, state.activity)
        assertEquals(ConversationPressure.GLOBAL_WAIT, state.pressure)
        assertFalse(indicator(runtime.copy(retainedTurnProblems = null)).hasTurnProblem)
    }

    @Test
    fun globalPressureDoesNotTurnAnIdleConversationIntoActiveWork() {
        val state = indicator(snapshot(), load = AiRequestActivitySnapshot(4, 1, 0, emptyList()))
        assertEquals(ConversationPressure.GLOBAL_WAIT, state.pressure)
        assertEquals(ConversationCenterMark.READY, state.centerMark)
        assertFalse(state.animated)
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
