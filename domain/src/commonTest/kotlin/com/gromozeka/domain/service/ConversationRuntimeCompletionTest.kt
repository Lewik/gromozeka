package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import kotlin.time.Instant

class ConversationRuntimeCompletionTest {
    private val conversation = Conversation.Id("conversation")
    private val now = Instant.fromEpochSeconds(100)
    private val done = Instant.fromEpochSeconds(110)
    private val owner = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private val input = Conversation.Message(Conversation.Message.Id("input"), conversation,
        role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Input")), createdAt = now)
    private val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("root"), conversation,
        payload = ConversationRuntimeTask.Payload.AgentInvocation(input, AgentDefinition.Id("agent")),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "root",
        requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server),
        createdAt = now)

    private fun active(
        source: ConversationRuntimeTask = task,
        control: ConversationExecutionState.ControlState = ConversationExecutionState.ControlState.RUNNING,
        started: Boolean = true,
    ) = ConversationRuntimeSchedulingState(conversation,
        executionState = ConversationExecutionState(conversation, control, source.id, owner, now.takeIf { started }, now),
        activeTask = source, lastTurn = ConversationRuntimeTurnSummary(source.turnId, now))

    @Test fun successfulCompletionSurvivesIdleAndSerialization() {
        val state = active().completeActiveTask(task.id, owner, ConversationRuntimeTaskOutcome.CompleteTurn, done).state
        val completion = assertNotNull(state.finishIfIdle().state.lastCompletion)
        assertEquals(ConversationRuntimeCompletion.Status.COMPLETED, completion.status)
        assertEquals(task.turnId, completion.turnId)
        assertEquals(task.id, completion.taskId)
        assertEquals(done, completion.completedAt)
        assertNull(completion.reason)
        assertFalse(completion.outcomeUnknown)
        assertEquals(completion, Json.decodeFromString<ConversationRuntimeSchedulingState>(Json.encodeToString(state)).lastCompletion)
    }

    @Test fun handledModelFailureDoesNotBecomeSuccessfulCompletion() {
        val outcome = ConversationRuntimeTaskOutcome.Failed(ConversationRuntimeCompletion.Reason.MODEL_ERROR, "ProviderUnavailable")
        val state = active().completeActiveTask(task.id, owner, outcome, done).state.finishIfIdle().state
        val completion = assertNotNull(state.lastCompletion)
        assertEquals(ConversationRuntimeCompletion.Status.FAILED, completion.status)
        assertEquals(ConversationRuntimeCompletion.Reason.MODEL_ERROR, completion.reason)
        assertEquals("ProviderUnavailable", completion.errorType)
        assertFalse(completion.outcomeUnknown)
    }

    @Test fun toolProblemsDoNotReclassifyAnOtherwiseCompletedTurn() {
        val problem = ConversationRuntimeTurnSummary.Problem("tool", "Tool result was unavailable", now, outcomeUnknown = true)
        val state = active().copy(lastTurn = ConversationRuntimeTurnSummary(task.turnId, now, listOf(problem)))
            .completeActiveTask(task.id, owner, ConversationRuntimeTaskOutcome.CompleteTurn, done).state
        val completion = assertNotNull(state.lastCompletion)
        assertEquals(ConversationRuntimeCompletion.Status.COMPLETED, completion.status)
        assertEquals(1, completion.problemCount)
        assertTrue(completion.outcomeUnknown)
    }

    @Test fun continuationIsNotACompletionAndPausingDoesNotEndTheTurn() {
        val next = task.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = task.id, idempotencyKey = "next",
            payload = ConversationRuntimeTask.Payload.LlmCall(input.id, AgentDefinition.Id("agent"), 1))
        val state = active().completeActiveTask(task.id, owner, ConversationRuntimeTaskOutcome.Continue(next), done).state
        assertNull(state.lastCompletion)
        assertNull(state.requestPause(done).state.lastCompletion)
        assertNotNull(state.continuationTask)
    }

    @Test fun userStopAndInterruptRemainExplicitEvenWhenTheCurrentTaskFinishesNormally() {
        for ((control, status) in listOf(
            ConversationExecutionState.ControlState.STOPPING to ConversationRuntimeCompletion.Status.STOPPED,
            ConversationExecutionState.ControlState.INTERRUPTING to ConversationRuntimeCompletion.Status.INTERRUPTED,
        )) {
            val state = active(control = control).completeActiveTask(task.id, owner, ConversationRuntimeTaskOutcome.CompleteTurn, done).state
            assertEquals(status, state.finishIfIdle().state.lastCompletion?.status)
            val interrupted = active(control = control).abort(done).state.lastCompletion
            assertEquals(status, interrupted?.status)
            assertEquals(true, interrupted?.outcomeUnknown)
        }
    }

    @Test fun stoppingBetweenTasksRecordsTheStoppedTurn() {
        val next = task.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = task.id, idempotencyKey = "next",
            payload = ConversationRuntimeTask.Payload.LlmCall(input.id, AgentDefinition.Id("agent"), 1))
        val state = active().completeActiveTask(task.id, owner, ConversationRuntimeTaskOutcome.Continue(next), now).state
            .requestTerminalState(ConversationExecutionState.ControlState.STOPPING, done).state
        assertEquals(ConversationRuntimeCompletion.Status.STOPPED, state.lastCompletion?.status)
        assertEquals(task.turnId, state.lastCompletion?.turnId)
        assertNull(state.continuationTask)
    }

    @Test fun unconfirmedExecutionAndFailedDeliveryKeepDistinctOutcomeCertainty() {
        for (started in listOf(false, true)) {
            val kind = if (started) ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN else ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED
            val state = active(started = started).recordActiveTaskIncident(task.id, owner, kind,
                "Private error body", "ExecutorUnavailable", done).state
            val completion = assertNotNull(state.lastCompletion)
            assertEquals(ConversationRuntimeCompletion.Status.FAILED, completion.status)
            assertEquals(started, completion.outcomeUnknown)
            assertEquals(if (started) ConversationRuntimeCompletion.Reason.EXECUTION_OUTCOME_UNKNOWN
                else ConversationRuntimeCompletion.Reason.DELIVERY_FAILED, completion.reason)
            assertFalse(Json.encodeToString(completion).contains("Private error body"))
        }
        val pending = ConversationRuntimeSchedulingState(conversation, pendingTasks = listOf(task))
            .recordPendingTaskDeliveryFailure(task.id, owner, "Private delivery failure", "MissingExecutor", done).state
        assertEquals(ConversationRuntimeCompletion.Reason.DELIVERY_FAILED, pending.lastCompletion?.reason)
        assertEquals(false, pending.lastCompletion?.outcomeUnknown)
    }

    @Test fun unrequestedAbortIsNotMisrepresentedAsAUserStop() {
        val completion = active().abort(done).state.lastCompletion
        assertEquals(ConversationRuntimeCompletion.Status.UNKNOWN, completion?.status)
        assertEquals(ConversationRuntimeCompletion.Reason.ABORTED, completion?.reason)
    }

    @Test fun contextOnlyDeliveryCannotHideThePreviousAgentFailure() {
        val previous = active().completeActiveTask(task.id, owner,
            ConversationRuntimeTaskOutcome.Failed(ConversationRuntimeCompletion.Reason.MODEL_ERROR), now).state.lastCompletion
        val passive = task.copy(id = ConversationRuntimeTask.Id("context"), turnId = ConversationRuntimeTurnId("context"),
            idempotencyKey = "context", payload = ConversationRuntimeTask.Payload.PostMessage(input))
        val completed = active(passive).copy(lastCompletion = previous)
            .completeActiveTask(passive.id, owner, ConversationRuntimeTaskOutcome.CompleteWithoutNotification, done).state
        assertEquals(previous, completed.lastCompletion)
    }

    @Test fun cancelledInvocationIsNotReportedAsSuccessfulWork() {
        val state = active().completeActiveTask(task.id, owner, ConversationRuntimeTaskOutcome.Cancelled, done).state
        assertEquals(ConversationRuntimeCompletion.Status.CANCELLED, state.lastCompletion?.status)
    }

    @Test fun missingRecordedCompletionDoesNotManufactureSuccessFromHistoricalTiming() {
        val history = ConversationRuntimeSchedulingState(conversation,
            lastTurn = ConversationRuntimeTurnSummary(task.turnId, now, completedAt = done))
        val serialized = JsonObject(Json.parseToJsonElement(Json.encodeToString(history)).jsonObject - "lastCompletion")
        val restored = Json.decodeFromString<ConversationRuntimeSchedulingState>(serialized.toString())
        assertEquals(done, restored.lastTurn?.completedAt)
        assertNull(restored.lastCompletion)
    }
}
