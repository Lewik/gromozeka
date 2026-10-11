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
    private fun input(id: String, target: AgentDefinition.Id = agent, actor: User.Id = user) = ConversationRuntimeTask(
        id = ConversationRuntimeTask.Id(id), conversationId = conversation, actorUserId = actor,
        payload = ConversationRuntimeTask.Payload.AgentInvocation(Conversation.Message(
            id = Conversation.Message.Id(id), conversationId = conversation, role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(actor, "User"), content = emptyList(), createdAt = now), target),
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

    @Test fun `idle bursts in either mode reserve the entire batch with the root claim`() {
        for (mode in UserMessageDeliveryMode.entries) {
            var state = ConversationRuntimeSchedulingState(conversation)
            for (id in listOf("a", "b", "c")) state = state.submitHuman(id, mode)
            val claimed = state.start(input("a"))
            assertEquals(listOf("b", "c"), claimed.activeInsertions.map { it.id.value })
            assertTrue(claimed.pendingTasks.isEmpty())
            assertFalse(claimed.cancelByMessageId(Conversation.Message.Id("b")).result)
            assertFalse(claimed.updatePendingMessageSubmission(input("b")).result)
            val next = llm(requireNotNull(claimed.activeTask), "next")
            val done = claimed.finish(ConversationRuntimeTaskOutcome.Continue(next))
            assertEquals(setOf("a", "b", "c"), done.completedIdempotencyKeys)
            assertEquals(next.id, done.readyWorkItem()?.taskId)
        }
    }

    @Test fun `turn start reservation survives serialization and never absorbs late arrivals even when empty`() {
        for (hasFollower in listOf(false, true)) {
            var state = ConversationRuntimeSchedulingState(conversation).submitHuman("a", UserMessageDeliveryMode.STEER)
            if (hasFollower) state = state.submitHuman("b", UserMessageDeliveryMode.STEER)
            state = state.start(input("a"))
            state = Json.decodeFromString(Json.encodeToString(state))
            state = state.submitHuman("late", UserMessageDeliveryMode.AFTER_CURRENT_TURN)
            repeat(2) {
                val batch = state.claimActiveInsertions(input("a").id, executor, QueuedMessagePlacement.END_OF_TURN)
                assertFalse(batch.changed)
                assertEquals(if (hasFollower) listOf("b") else emptyList(), batch.result.map { it.id.value })
                assertEquals(listOf("late"), batch.state.pendingTasks.map { it.id.value })
            }
            val otherExecutor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("stale"))
            assertTrue(state.claimActiveInsertions(input("a").id, otherExecutor, QueuedMessagePlacement.END_OF_TURN).result.isEmpty())
            val next = llm(requireNotNull(state.activeTask), "model")
            val running = state.finish(ConversationRuntimeTaskOutcome.Continue(next)).start(next)
            assertTrue(running.claimActiveInsertions(next.id, executor, QueuedMessagePlacement.END_OF_TURN).result.isEmpty())
            assertEquals(listOf("late"), running.pendingTasks.map { it.id.value })
        }
    }

    @Test fun `batching stops at actor recipient runtime and execution boundaries without jumping FIFO`() {
        val source = input("barrier")
        val message = requireNotNull(source.userMessageOrNull())
        val barriers = listOf(
            input("barrier", actor = User.Id("bob")),
            input("barrier", target = other),
            source.copy(actorUserId = null),
            source.copy(payload = ConversationRuntimeTask.Payload.PostMessage(message)),
            source.copy(payload = ConversationRuntimeTask.Payload.AgentInvocation(
                message.copy(instructions = listOf(Conversation.Message.Instruction.Source.Agent("peer"))), agent)),
            source.copy(payload = ConversationRuntimeTask.Payload.AgentInvocation(message.copy(
                providerMetadata = kotlinx.serialization.json.buildJsonObject { put("syntheticKind", kotlinx.serialization.json.JsonPrimitive("command_task_completion")) }), agent)),
            source.copy(payload = ConversationRuntimeTask.Payload.HistoryMutation(ConversationHistoryMutation.Delete(emptyList()))),
            source.copy(requirements = requirements.copy(target = ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("worker")))),
        )
        for (barrier in barriers) {
            var state = ConversationRuntimeSchedulingState(conversation)
                .submitHuman("a", UserMessageDeliveryMode.STEER).submitHuman("b", UserMessageDeliveryMode.STEER)
            state = state.submit(barrier, now).state.submitHuman("after-barrier", UserMessageDeliveryMode.STEER)
            val claimed = state.start(input("a"))
            assertEquals(listOf("b"), claimed.activeInsertions.map { it.id.value }, barrier.toString())
            assertEquals(listOf("barrier", "after-barrier"), claimed.pendingTasks.map { it.id.value })
        }
    }

    @Test fun `broadcast batches require the same full recipient set`() {
        fun post(id: String, recipients: Set<AgentDefinition.Id>): ConversationRuntimeTask {
            val source = input(id)
            return source.copy(payload = ConversationRuntimeTask.Payload.PostMessage(requireNotNull(source.userMessageOrNull()), recipients))
        }
        val root = post("a", setOf(agent, other))
        var state = ConversationRuntimeSchedulingState(conversation)
        for (task in listOf(root, post("b", setOf(other, agent)), post("different", setOf(agent)), post("c", setOf(agent, other)))) {
            state = state.submit(task, now, userDeliveryMode = UserMessageDeliveryMode.STEER).state
        }
        state = state.start(root)
        assertEquals(listOf("b"), state.activeInsertions.map { it.id.value })
        assertEquals(listOf("different", "c"), state.pendingTasks.map { it.id.value })
    }

    @Test fun `pause edits and cancellations settle before the snapshot is reserved`() {
        var state = ConversationRuntimeSchedulingState(conversation)
        for (id in listOf("a", "b", "c")) state = state.submitHuman(id, UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        state = state.requestPause(now).state
        assertNull(state.readyWorkItem())
        val source = input("b")
        val edited = source.copy(payload = source.requireAgentInvocation().copy(userMessage = requireNotNull(source.userMessageOrNull()).copy(
            content = listOf(Conversation.Message.ContentItem.UserMessage("edited")))))
        val updated = state.updatePendingMessageSubmission(edited)
        assertTrue(updated.result)
        state = updated.state.cancelByMessageId(Conversation.Message.Id("c")).state
        state = state.requestResume(now).state.start(input("a"))
        assertEquals(edited, state.activeInsertions.single())
        assertTrue(state.pendingTasks.isEmpty())
    }

    @Test fun `abort and execution incidents preserve the reserved batch in order`() {
        for (started in listOf(false, true)) {
            var state = ConversationRuntimeSchedulingState(conversation)
            for (id in listOf("a", "b", "c")) state = state.submitHuman(id, UserMessageDeliveryMode.STEER)
            state = state.claim(input("a").id, executor, capabilities, emptySet(), now).state
            if (started) state = state.markActiveTaskStarted(input("a").id, executor, now).state
            val recovered = state.recordActiveTaskIncident(input("a").id, executor,
                if (started) ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN else ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                "synthetic failure", null, now).state
            assertEquals(listOf("b", "c"), recovered.pendingTasks.takeLast(2).map { it.id.value })
            assertFalse("b" in recovered.completedIdempotencyKeys)
            assertFalse("c" in recovered.completedIdempotencyKeys)
            val aborted = state.abort(now).state
            assertEquals(listOf("b", "c"), aborted.pendingTasks.map { it.id.value })
            assertTrue(aborted.activeInsertions.isEmpty())
            assertEquals(ConversationExecutionState.ControlState.PAUSED, aborted.executionState?.controlState)
        }
    }

    @Test fun `steer batch reservation is stable while more messages arrive`() {
        val before = active().submitHuman("a", UserMessageDeliveryMode.STEER).submitHuman("b", UserMessageDeliveryMode.STEER)
        val task = requireNotNull(before.activeTask)
        val first = before.claimActiveInsertions(task.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT)
        val later = first.state.submitHuman("c", UserMessageDeliveryMode.STEER)
        val replay = later.claimActiveInsertions(task.id, executor, QueuedMessagePlacement.AFTER_TOOL_RESULT)
        assertFalse(replay.changed)
        assertEquals(listOf("a", "b"), replay.result.map { it.id.value })
        assertEquals(listOf("c"), replay.state.pendingTasks.map { it.id.value })
    }

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
