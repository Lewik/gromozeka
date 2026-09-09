package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlin.test.*
import kotlin.time.Instant

class ConversationGuardedInterruptTest {
    private val conversation = Conversation.Id("channel")
    private val now = Instant.fromEpochSeconds(1)
    private val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private fun task(id: String) = ConversationRuntimeTask(ConversationRuntimeTask.Id(id), conversation,
        payload = ConversationRuntimeTask.Payload.AgentResponse(Conversation.Message.Id("message-$id"), AgentDefinition.Id("agent")),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id,
        requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server), createdAt = now)
    private fun active(current: ConversationRuntimeTask, next: ConversationRuntimeTask) = ConversationRuntimeSchedulingState(conversation,
        executionState = ConversationExecutionState(conversation, ConversationExecutionState.ControlState.RUNNING, current.id,
            activeExecutor = executor, activeTaskStartedAt = now, updatedAt = now), activeTask = current, pendingTasks = listOf(next))

    private fun ConversationRuntimeSchedulingState.interrupt(turnId: ConversationRuntimeTurnId) =
        requestTerminalState(ConversationExecutionState.ControlState.INTERRUPTING, now, expectedTurnId = turnId)

    @Test fun `interrupt uses standard cancellation and preserves queued turns paused`() {
        val first = task("first"); val second = task("second")
        val stopped = active(first, second).interrupt(first.turnId)
        assertTrue(stopped.result)
        assertEquals(ConversationExecutionState.ControlState.INTERRUPTING, stopped.state.executionState?.controlState)
        val completed = stopped.state.abort(now)
        assertEquals(listOf(second), completed.state.pendingTasks)
        assertEquals(ConversationExecutionState.ControlState.PAUSED, completed.state.executionState?.controlState)
        assertFalse(completed.state.interrupt(first.turnId).result)
        assertEquals(listOf(first.turnId.value), completed.state.pendingTurnTerminationInstructions.map { it.turnId })
        assertEquals(com.gromozeka.domain.model.Conversation.TurnTerminationReason.INTERRUPTED,
            completed.state.pendingTurnTerminationInstructions.single().reason)
    }

    @Test fun `queued stop is specific and retains idempotency after cancellation`() {
        val first = task("first"); val second = task("second")
        val stopped = active(first, second).interrupt(second.turnId)
        assertEquals(first, stopped.state.activeTask)
        assertEquals(ConversationExecutionState.ControlState.RUNNING, stopped.state.executionState?.controlState)
        assertTrue(stopped.state.pendingTasks.isEmpty())
        assertFalse(stopped.state.submit(second, now).result)
        assertTrue(stopped.state.submit(second, now, acceptPreviouslySubmitted = true).result)
        assertTrue(stopped.state.submit(second, now, acceptPreviouslySubmitted = true).state.pendingTasks.isEmpty())
    }

    @Test fun `interrupt between claims drops continuation and preserves standard queue pause`() {
        val first = task("first")
        val continuation = first.copy(id = ConversationRuntimeTask.Id("first-llm"), parentTaskId = first.id,
            payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), AgentDefinition.Id("agent"), 1), idempotencyKey = "first-llm")
        val state = ConversationRuntimeSchedulingState(conversation,
            executionState = ConversationExecutionState(conversation, ConversationExecutionState.ControlState.RUNNING, null, updatedAt = now),
            continuationTask = continuation, pendingTasks = listOf(task("next")))
        val stopped = state.interrupt(first.turnId)
        assertTrue(stopped.result); assertNull(stopped.state.continuationTask)
        assertEquals(ConversationExecutionState.ControlState.PAUSED, stopped.state.executionState?.controlState)
        assertEquals(1, stopped.state.pendingTasks.size)
    }

    @Test fun `guarded interrupt never downgrades a global interrupt or targets another turn`() {
        val first = task("first")
        val interrupted = active(first, task("second")).requestTerminalState(ConversationExecutionState.ControlState.INTERRUPTING, now).state
        assertTrue(interrupted.interrupt(first.turnId).result)
        assertFalse(interrupted.interrupt(ConversationRuntimeTurnId("stale")).result)
        assertEquals(ConversationExecutionState.ControlState.INTERRUPTING, interrupted.executionState?.controlState)
    }

    @Test fun `duplicate acceptance distinguishes durable work from temporary rejection`() {
        val first = task("first"); val next = task("next")
        val stopping = active(first, next).interrupt(first.turnId).state
        assertTrue(stopping.submit(first, now, acceptPreviouslySubmitted = true).result)
        assertFalse(stopping.submit(task("new"), now, acceptPreviouslySubmitted = true).result)
    }

    @Test fun `failure during guarded interrupt preserves standard queue pause`() {
        val first = task("first"); val next = task("next")
        val stopping = active(first, next).interrupt(first.turnId).state
        val failed = stopping.recordActiveTaskIncident(first.id, executor, ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
            "Interrupted work", "TestFailure", now).state
        assertEquals(ConversationExecutionState.ControlState.PAUSED, failed.executionState?.controlState)
        assertEquals(listOf(next), failed.pendingTasks)
    }

    @Test fun `incident recovery preserves the external origin without creating a new agent turn`() {
        val first = task("first").copy(externalChannel = com.gromozeka.domain.model.ExternalConversationChannel("telegram", "1", "-123:0"))
        val failed = active(first, task("next")).recordActiveTaskIncident(first.id, executor,
            ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, "Unavailable", "TestFailure", now).state
        val recovery = failed.pendingTasks.first()
        assertTrue(recovery.payload is ConversationRuntimeTask.Payload.ExecutionIncident)
        assertEquals(first.externalChannel, recovery.externalChannel)
    }
}
