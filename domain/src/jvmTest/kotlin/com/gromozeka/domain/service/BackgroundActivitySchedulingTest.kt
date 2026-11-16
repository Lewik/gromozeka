package com.gromozeka.domain.service

import com.gromozeka.domain.model.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class BackgroundActivitySchedulingTest {
    private val now = Instant.fromEpochSeconds(1)
    private val conversation = Conversation.Id("conversation")
    private val agent = AgentDefinition.Id("agent")
    private val user = User.Id("user")
    private val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private val capabilities = ConversationRuntimeCapability.entries.toSet()
    private val requirements = ConversationRuntimeTaskRequirements(capabilities, ConversationRuntimeTaskTarget.Server)
    private fun completion(id: String = "completion", actor: User.Id? = user) = ConversationRuntimeTask(
        ConversationRuntimeTask.Id(id), conversation, actorUserId = actor,
        payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion("command-task:$id"),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id, requirements = requirements, createdAt = now,
    )
    private val running = completion("model").copy(parentTaskId = ConversationRuntimeTask.Id("root"),
        payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), agent, 1))
    private fun active() = ConversationRuntimeSchedulingState(conversation, activeTask = running,
        executionState = ConversationExecutionState(conversation, ConversationExecutionState.ControlState.RUNNING,
            running.id, activeExecutor = executor, activeTaskStartedAt = now, updatedAt = now))

    @Test fun `completion is reserved before the next model request and survives restart`() {
        val submitted = active().submit(completion(), now, backgroundActivityAgentId = agent)
        assertTrue(submitted.result)
        assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, submitted.state.pendingTasks.single().placement)
        assertEquals(running, submitted.state.activeTask)
        val restored = Json.decodeFromString<ConversationRuntimeSchedulingState>(Json.encodeToString(submitted.state))
        val reserved = restored.claimActiveInsertions(running.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT)
        assertEquals(listOf("completion"), reserved.result.map { it.id.value })
        assertTrue(reserved.state.pendingTasks.isEmpty())
        assertEquals(reserved.result, reserved.state.claimActiveInsertions(running.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT).result)
        assertFalse(reserved.state.submit(completion(), now, backgroundActivityAgentId = agent).result)
        val next = running.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = running.id, idempotencyKey = "next")
        val finished = reserved.state.completeActiveTask(running.id, executor, ConversationRuntimeTaskOutcome.Continue(next), now)
        assertTrue(finished.result)
        assertTrue("completion" in finished.state.completedIdempotencyKeys)
        assertEquals(next.id, finished.state.readyWorkItem()?.taskId)
    }

    @Test fun `completion admitted between continuation tasks still uses the next safe point`() {
        val next = running.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = running.id, idempotencyKey = "next")
        val gap = active().completeActiveTask(running.id, executor, ConversationRuntimeTaskOutcome.Continue(next), now).state
        val submitted = gap.submit(completion(), now, backgroundActivityAgentId = agent)
        assertTrue(submitted.result)
        assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, submitted.state.pendingTasks.single().placement)
        assertEquals(next.id, submitted.state.readyWorkItem()?.taskId)
    }

    @Test fun `a bound recipient cannot be replaced by admission`() {
        val input = completion().copy(payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion("command-task:completion", AgentDefinition.Id("other")))
        assertFailsWith<IllegalArgumentException> { active().submit(input, now, backgroundActivityAgentId = agent) }
    }

    @Test fun `idle completion is immediately runnable and late completion is not stranded`() {
        val idle = ConversationRuntimeSchedulingState(conversation).submit(completion(), now, backgroundActivityAgentId = agent)
        assertTrue(idle.result)
        assertEquals(completion().id, idle.state.readyWorkItem()?.taskId)
        val queued = active().submit(completion(), now, backgroundActivityAgentId = agent).state
        val finished = queued.completeActiveTask(running.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn, now).state
        assertEquals(completion().id, finished.readyWorkItem()?.taskId)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, finished.pendingTasks.single().placement)
    }

    @Test fun `another agent actor or unproven creator never changes the active turn authority`() {
        for ((target, actor) in listOf(agent to User.Id("other"), AgentDefinition.Id("other") to user, agent to null)) {
            val submitted = active().submit(completion(actor = actor), now, backgroundActivityAgentId = target)
            assertTrue(submitted.result)
            assertEquals(QueuedMessagePlacement.END_OF_TURN, submitted.state.pendingTasks.single().placement)
            assertTrue(submitted.state.claimActiveInsertions(running.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT).result.isEmpty())
            assertEquals(running, submitted.state.activeTask)
        }
    }

    @Test fun `parent failure preserves unattempted background reservations`() {
        val queued = active().submit(completion(), now, backgroundActivityAgentId = agent).state
        val claimed = queued.claimActiveInsertions(running.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT).state
        val failed = claimed.recordActiveTaskIncident(running.id, executor, ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
            "Parent failed before delivery", "SyntheticFailure", now)
        assertNotNull(failed.result)
        val pending = failed.state.pendingTasks.single { it.payload is ConversationRuntimeTask.Payload.BackgroundActivityCompletion }
        assertEquals(completion().id, pending.id)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, pending.placement)
        assertFalse(completion().idempotencyKey in failed.state.completedIdempotencyKeys)
        assertEquals(failed.state, Json.decodeFromString<ConversationRuntimeSchedulingState>(Json.encodeToString(failed.state)))
    }

    @Test fun `only the insertion whose delivery failed is closed`() {
        var state = active().submit(completion(), now, backgroundActivityAgentId = agent).state
        state = state.submit(completion("untouched"), now, backgroundActivityAgentId = agent).state
        state = state.claimActiveInsertions(running.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT).state
        val failure = state.recordBackgroundInsertionFailure(running.id, completion().id, executor, now, "Failed delivery", "SyntheticFailure", now)
        assertEquals(completion().id, assertNotNull(failure.result).task.id)
        assertEquals(listOf("untouched"), failure.state.activeInsertions.map { it.id.value })
        val parentFailure = failure.state.recordActiveTaskIncident(running.id, executor, ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
            "Parent stopped", "SyntheticFailure", now).state
        assertEquals(listOf("untouched"), parentFailure.pendingTasks.filter { it.payload is ConversationRuntimeTask.Payload.BackgroundActivityCompletion }.map { it.id.value })
    }

    @Test fun `notification arrival during another delivery is not delayed until its model finishes`() {
        var state = ConversationRuntimeSchedulingState(conversation).submit(completion("first"), now, backgroundActivityAgentId = agent).state
        val first = state.pendingTasks.single()
        state = state.claim(first.id, executor, capabilities, emptySet(), now).state
        state = state.submit(completion("next"), now, backgroundActivityAgentId = agent).state
        assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, state.pendingTasks.single().placement)
    }

    @Test fun `stop refuses new completion work and preserves already queued delivery while paused`() {
        val queued = active().submit(completion(), now, backgroundActivityAgentId = agent).state
        val stopping = queued.requestTerminalState(ConversationExecutionState.ControlState.STOPPING, now).state
        assertFalse(stopping.submit(completion("late"), now, backgroundActivityAgentId = agent).result)
        val finished = stopping.completeActiveTask(running.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn, now).state
        assertNull(finished.readyWorkItem())
        assertEquals(ConversationExecutionState.ControlState.PAUSED, finished.executionState?.controlState)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, finished.pendingTasks.single().placement)
    }
}
