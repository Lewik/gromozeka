package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConversationRuntimeTimingTest {
    private val conversation = Conversation.Id("timing")
    private val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private val capabilities = ConversationRuntimeCapability.entries.toSet()
    private val root = ConversationRuntimeTask(ConversationRuntimeTask.Id("root"), conversation,
        payload = ConversationRuntimeTask.Payload.AgentInvocation(Conversation.Message(Conversation.Message.Id("input"), conversation,
            role = Conversation.Message.Role.USER, content = emptyList(), createdAt = at(1)), AgentDefinition.Id("agent")),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "root",
        requirements = ConversationRuntimeTaskRequirements(capabilities, ConversationRuntimeTaskTarget.Server), createdAt = at(1))
    private val model = root.copy(id = ConversationRuntimeTask.Id("model"), parentTaskId = root.id, idempotencyKey = "model",
        payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("input"), AgentDefinition.Id("agent"), 1))
    private val next = model.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = model.id, idempotencyKey = "next",
        payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("input"), AgentDefinition.Id("agent"), 2))

    @Test
    fun completedRequestStopsBetweenStepsAndCompletedTurnKeepsBothBoundaries() {
        var state = modelStarted()
        assertEquals(at(20), state.lastTurn!!.modelRequest!!.startedAt)
        state = state.completeActiveTask(model.id, executor, ConversationRuntimeTaskOutcome.Continue(next), at(35)).state
        assertEquals(at(35), state.lastTurn!!.modelRequest!!.completedAt)
        assertNull(state.lastTurn!!.completedAt)
        state = state.claim(next.id, executor, capabilities, emptySet(), at(40)).state
            .markActiveTaskStarted(next.id, executor, at(50)).state
        assertEquals(next.id, state.lastTurn!!.modelRequest!!.taskId)
        assertNull(state.lastTurn!!.modelRequest!!.completedAt)
        state = state.completeActiveTask(next.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn, at(75)).state
        val finished = assertNotNull(state.lastTurn)
        assertEquals(at(10), finished.startedAt)
        assertEquals(at(75), finished.completedAt)
        assertEquals(at(50), finished.modelRequest!!.startedAt)
        assertEquals(at(75), finished.modelRequest!!.completedAt)
        state = state.finishIfIdle().state
        assertEquals(finished, state.lastTurn)
        assertEquals(finished, Json.decodeFromString<ConversationRuntimeSchedulingState>(Json.encodeToString(state)).lastTurn)
        val later = root.copy(id = ConversationRuntimeTask.Id("later"), turnId = ConversationRuntimeTurnId("later-turn"), idempotencyKey = "later")
        state = state.submit(later, at(90)).state.claim(later.id, executor, capabilities, emptySet(), at(91)).state
        assertNull(state.lastTurn!!.completedAt)
        assertNull(state.lastTurn!!.modelRequest)
    }

    @Test
    fun stopAbortAndUnknownOutcomeEndClocksAtTheirRecordedBoundary() {
        val active = modelStarted()
        val stopping = active.requestTerminalState(ConversationExecutionState.ControlState.STOPPING, at(30)).state
        assertNull(stopping.lastTurn!!.completedAt, "A cancellation request is not a completion acknowledgement")
        val stopped = stopping.completeActiveTask(model.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn, at(35)).state
        assertEquals(at(35), stopped.lastTurn!!.completedAt)
        assertEquals(at(35), stopped.lastTurn!!.modelRequest!!.completedAt)
        val aborted = active.abort(at(31)).state
        assertEquals(at(31), aborted.lastTurn!!.completedAt)
        assertEquals(at(31), aborted.lastTurn!!.modelRequest!!.completedAt)
        val failed = active.recordActiveTaskIncident(model.id, executor, ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
            "Lost task", null, at(32)).state
        assertEquals(at(32), failed.lastTurn!!.completedAt)
        assertEquals(at(32), failed.lastTurn!!.modelRequest!!.completedAt)
        val between = active.completeActiveTask(model.id, executor, ConversationRuntimeTaskOutcome.Continue(next), at(33)).state
        val cancelled = between.requestTerminalState(ConversationExecutionState.ControlState.INTERRUPTING, at(40)).state
        assertEquals(at(40), cancelled.lastTurn!!.completedAt)
        assertEquals(at(33), cancelled.lastTurn!!.modelRequest!!.completedAt)
    }

    @Test
    fun staleOwnerCannotFinishTheCurrentRequest() {
        val active = modelStarted()
        val changed = active.completeActiveTask(model.id,
            ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("other")),
            ConversationRuntimeTaskOutcome.CompleteTurn, at(35))
        assertTrue(!changed.result)
        assertEquals(active, changed.state)
    }

    private fun modelStarted(): ConversationRuntimeSchedulingState = ConversationRuntimeSchedulingState(conversation)
        .submit(root, at(2)).state.claim(root.id, executor, capabilities, emptySet(), at(10)).state
        .markActiveTaskStarted(root.id, executor, at(11)).state
        .completeActiveTask(root.id, executor, ConversationRuntimeTaskOutcome.Continue(model), at(12)).state
        .claim(model.id, executor, capabilities, emptySet(), at(19)).state.markActiveTaskStarted(model.id, executor, at(20)).state

    private fun at(seconds: Long) = Instant.fromEpochSeconds(seconds)
}
