package com.gromozeka.domain.service

import com.gromozeka.domain.model.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class UserMessageDeliverySchedulingTest {
    private val now = Instant.fromEpochSeconds(1)
    private val conversation = Conversation.Id("conversation")
    private val agent = AgentDefinition.Id("agent")
    private val other = AgentDefinition.Id("other")
    private val user = User.Id("user")
    private val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private val capabilities = ConversationRuntimeCapability.entries.toSet()
    private val requirements = ConversationRuntimeTaskRequirements(capabilities, ConversationRuntimeTaskTarget.Server)
    private fun input(id: String, target: AgentDefinition.Id = agent) = ConversationRuntimeTask(
        id = ConversationRuntimeTask.Id(id), conversationId = conversation, actorUserId = user,
        payload = ConversationRuntimeTask.Payload.AgentInvocation(Conversation.Message(
            id = Conversation.Message.Id(id), conversationId = conversation, role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(user, "User"), content = emptyList(), createdAt = now), target),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id, requirements = requirements, createdAt = now,
    )
    private fun llm(parent: ConversationRuntimeTask, id: String) = parent.copy(
        id = ConversationRuntimeTask.Id(id), parentTaskId = parent.id, idempotencyKey = id,
        payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), agent, 1),
    )
    private fun active(task: ConversationRuntimeTask = llm(input("root"), "model")) = ConversationRuntimeSchedulingState(
        conversationId = conversation, activeTask = task,
        executionState = ConversationExecutionState(conversation, ConversationExecutionState.ControlState.RUNNING,
            task.id, activeExecutor = executor, activeTaskStartedAt = now, updatedAt = now),
    )
    private fun ConversationRuntimeSchedulingState.submitHuman(id: String, mode: UserMessageDeliveryMode) =
        submit(input(id), now, userDeliveryMode = mode).also { assertTrue(it.result) }.state
    private fun ConversationRuntimeSchedulingState.finish(outcome: ConversationRuntimeTaskOutcome) =
        completeActiveTask(requireNotNull(activeTask).id, executor, outcome, now).also { assertTrue(it.result) }.state
    private fun ConversationRuntimeSchedulingState.start(task: ConversationRuntimeTask) =
        claim(task.id, executor, capabilities, emptySet(), now).also { assertNotNull(it.result) }.state
            .markActiveTaskStarted(task.id, executor, now).state

    @Test fun `idle including collaboration wait starts either mode as normal input`() {
        for (mode in UserMessageDeliveryMode.entries) {
            val state = ConversationRuntimeSchedulingState(conversation).submitHuman("new", mode)
            assertEquals(QueuedMessagePlacement.END_OF_TURN, state.pendingTasks.single().placement)
            assertEquals("new", state.readyWorkItem()?.taskId?.value)
        }
    }
    @Test fun `steer admission never cancels or replaces current model or tool task`() {
        val root = input("root")
        val tool = Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id("call"),
            Conversation.Message.ContentItem.ToolCall.Data("test", kotlinx.serialization.json.JsonObject(emptyMap())))
        val model = llm(root, "model")
        val executing = model.copy(payload = ConversationRuntimeTask.Payload.ToolExecution(
            Conversation.Message.Id("root"), agent, 1, listOf(tool), false, mapOf("call" to ConversationRuntimeTaskTarget.Server)))
        for (task in listOf(model, executing, model.copy(payload = ConversationRuntimeTask.Payload.ResponseReview("draft", agent)))) {
            val before = active(task)
            val state = before.submitHuman("new", UserMessageDeliveryMode.STEER)
            assertEquals(before.activeTask, state.activeTask)
            assertEquals(before.executionState, state.executionState)
            assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, state.pendingTasks.single().placement)
            assertNull(state.readyWorkItem())
        }
    }
    @Test fun `steer is accepted in the gap between continuation tasks`() {
        val state = active()
        val next = llm(requireNotNull(state.activeTask), "next")
        val gap = state.finish(ConversationRuntimeTaskOutcome.Continue(next)).submitHuman("new", UserMessageDeliveryMode.STEER)
        assertNull(gap.activeTask)
        assertEquals(next.id, gap.readyWorkItem()?.taskId)
        assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, gap.pendingTasks.single().placement)
        val running = gap.start(next)
        val claimed = running.claimActiveInsertions(next.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT)
        assertEquals(listOf("new"), claimed.result.map { it.id.value })
        assertEquals(claimed.result, claimed.state.claimActiveInsertions(next.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT).result)
    }
    @Test fun `after turn waits across every model and tool continuation`() {
        var state = active().submitHuman("later", UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        repeat(3) { n ->
            val next = llm(requireNotNull(state.activeTask), "continuation-$n")
            state = state.finish(ConversationRuntimeTaskOutcome.Continue(next))
            assertEquals(next.id, state.readyWorkItem()?.taskId)
            assertNull(state.claim(input("later").id, executor, capabilities, emptySet(), now).result)
            state = state.start(next)
            assertTrue(state.claimActiveInsertions(next.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT).result.isEmpty())
        }
        state = state.finish(ConversationRuntimeTaskOutcome.CompleteTurn)
        assertEquals("later", state.readyWorkItem()?.taskId?.value)
    }
    @Test fun `new preference affects only new messages and preserves FIFO within placement`() {
        val a = active().submitHuman("a", UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        val b = a.submitHuman("b", UserMessageDeliveryMode.STEER).submitHuman("c", UserMessageDeliveryMode.STEER)
        assertEquals(a.pendingTasks.single(), b.pendingTasks.first())
        assertEquals(listOf("a", "b", "c"), b.pendingTasks.map { it.id.value })
        val claimed = b.claimActiveInsertions(requireNotNull(b.activeTask).id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT)
        assertEquals(listOf("b", "c"), claimed.result.map { it.id.value })
        assertEquals(listOf("a"), claimed.state.pendingTasks.map { it.id.value })
        assertEquals(b, Json.decodeFromString<ConversationRuntimeSchedulingState>(Json.encodeToString(b)))
    }
    @Test fun `runtime owned explicit end placement bypasses human default`() {
        val state = active().submit(input("peer"), now).state
        assertEquals(QueuedMessagePlacement.END_OF_TURN, state.pendingTasks.single().placement)
    }
    @Test fun `different target agent cannot be injected into active agent context`() {
        val task = input("other", other)
        val state = active().submit(task, now, userDeliveryMode = UserMessageDeliveryMode.STEER).state
        assertEquals(task, state.pendingTasks.single())
    }
    @Test fun `broadcast steer preserves other responders without duplicate user insertion`() {
        val source = input("broadcast")
        val post = source.copy(payload = ConversationRuntimeTask.Payload.PostMessage(requireNotNull(source.userMessageOrNull()), setOf(other, agent)))
        val state = active().submit(post, now, userDeliveryMode = UserMessageDeliveryMode.STEER).state
        assertEquals(2, state.pendingTasks.size)
        assertEquals(setOf(agent), assertIs<ConversationRuntimeTask.Payload.PostMessage>(state.pendingTasks[0].payload).autoRespondAgentIds)
        val follower = assertIs<ConversationRuntimeTask.Payload.AgentResponse>(state.pendingTasks[1].payload)
        assertEquals(other, follower.agentDefinitionId)
        assertEquals(source.userMessageIdOrNull(), follower.rootUserMessageId)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, state.pendingTasks[1].placement)
        assertFalse(state.submit(post, now, userDeliveryMode = UserMessageDeliveryMode.STEER).result)
        val withOtherInput = state.submitHuman("unrelated", UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        assertEquals(listOf("unrelated"), withOtherInput.cancelByMessageId(requireNotNull(source.userMessageIdOrNull())).state.pendingTasks.map { it.id.value })
    }
    @Test fun `stop and interrupt preserve queued input but prevent new admission`() {
        for (control in listOf(ConversationExecutionState.ControlState.STOPPING, ConversationExecutionState.ControlState.INTERRUPTING)) {
            val queued = active().submitHuman("new", UserMessageDeliveryMode.STEER)
            val stopping = queued.requestTerminalState(control, now).state
            assertFalse(stopping.submit(input("late"), now, userDeliveryMode = UserMessageDeliveryMode.STEER).result)
            val settled = stopping.finish(ConversationRuntimeTaskOutcome.CompleteTurn)
            assertEquals(ConversationExecutionState.ControlState.PAUSED, settled.executionState?.controlState)
            assertEquals("new", settled.pendingTasks.single().id.value)
            assertEquals(QueuedMessagePlacement.END_OF_TURN, settled.pendingTasks.single().placement)
        }
    }
}
