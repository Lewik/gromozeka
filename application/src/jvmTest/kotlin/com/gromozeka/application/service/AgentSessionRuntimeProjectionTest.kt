package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class AgentSessionRuntimeProjectionTest {
    private val old = Instant.fromEpochMilliseconds(1)
    private val readAt = Instant.parse("2026-10-11T20:00:00Z")
    private val actor = User.Id("user")
    private val peer = AgentEndpoint(Conversation.Id("peer"), Conversation.Thread.Id("peer-thread"), AgentDefinition.Id("peer-agent"))
    private val endpoint = AgentEndpoint(Conversation.Id("target"), Conversation.Thread.Id("target-thread"), AgentDefinition.Id("target-agent"))
    private val empty = ConversationRuntimeSchedulingSnapshot(endpoint.conversationId)
    private val input = Conversation.Message(Conversation.Message.Id("input"), endpoint.conversationId,
        role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage("PRIVATE_INPUT_SENTINEL")), createdAt = old)
    private val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("task"), endpoint.conversationId,
        actorUserId = actor, payload = ConversationRuntimeTask.Payload.AgentInvocation(input, endpoint.agentId),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "task",
        requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.CONVERSATION_TURN, ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server),
        createdAt = old)
    private val incoming = AgentRequest("incoming", peer, endpoint, actor, "PRIVATE_REQUEST_SENTINEL", old)
    private fun view(s: ConversationRuntimeSchedulingSnapshot = empty, requests: List<AgentRequest> = emptyList(), details: Boolean = false) =
        AgentSessionRuntimeProjection.project(endpoint, actor, s, requests, readAt, details)

    @Test fun `empty scheduling has explicit missing control timestamps without inventing last activity`() {
        val v = view()
        assertEquals("IDLE", v.getValue("foreground_state").jsonPrimitive.content)
        assertEquals(JsonNull, v["control_state"])
        assertEquals(JsonNull, v["state_updated_at"])
        assertEquals(JsonNull, v["active_task_kind"])
        assertEquals(readAt.toString(), v.getValue("read_at").jsonPrimitive.content)
        assertEquals("stored_runtime", v.getValue("source").jsonPrimitive.content)
        assertEquals("best_effort", v.getValue("consistency").jsonPrimitive.content)
        assertEquals(0, v.getValue("queued_task_count").jsonPrimitive.int)
    }

    @Test fun `old active records stay recorded active rather than being diagnosed as stuck or healthy`() {
        val s = empty.copy(state = ConversationExecutionState(endpoint.conversationId,
            ConversationExecutionState.ControlState.RUNNING, task.id, updatedAt = old), activeTask = task, lastEventSequence = 7)
        val v = view(s)
        assertEquals("ACTIVE", v.getValue("foreground_state").jsonPrimitive.content)
        assertEquals("AgentInvocation", v.getValue("active_task_kind").jsonPrimitive.content)
        assertEquals(old.toString(), v.getValue("state_updated_at").jsonPrimitive.content)
        assertEquals(7, v.getValue("last_event_sequence").jsonPrimitive.int)
        assertFalse(v.containsKey("stuck"))
        assertFalse(v.containsKey("healthy"))
        assertFalse(v.toString().contains("PRIVATE_INPUT_SENTINEL"))
    }

    @Test fun `pause and stopping controls remain distinct from foreground activity and explicit waits`() {
        for (control in ConversationExecutionState.ControlState.entries.filter { it != ConversationExecutionState.ControlState.RUNNING }) {
            val s = empty.copy(state = ConversationExecutionState(endpoint.conversationId, control, null, updatedAt = old))
            val v = view(s, listOf(incoming.copy(state = AgentRequest.State.WAITING_USER)))
            assertEquals(control.name, v.getValue("foreground_state").jsonPrimitive.content)
            assertEquals(control.name, v.getValue("control_state").jsonPrimitive.content)
            assertEquals(1, v.getValue("agent_requests").jsonObject.getValue("incoming_waiting_user").jsonPrimitive.int)
        }
    }

    @Test fun `pending roots and continuation gaps are queued not active or idle`() {
        val queued = view(empty.copy(pendingTasks = listOf(task)))
        assertEquals("QUEUED", queued.getValue("foreground_state").jsonPrimitive.content)
        assertEquals(1, queued.getValue("queued_task_count").jsonPrimitive.int)
        val continuation = task.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = task.id, idempotencyKey = "next",
            payload = ConversationRuntimeTask.Payload.LlmCall(input.id, endpoint.agentId, 1),
            requirements = task.requirements.copy(capabilities = setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE, ConversationRuntimeCapability.MEMORY_PIPELINE)))
        val gap = view(empty.copy(continuationTask = continuation))
        assertEquals("QUEUED", gap.getValue("foreground_state").jsonPrimitive.content)
        assertTrue(gap.getValue("continuation_pending").jsonPrimitive.boolean)
        assertEquals(0, gap.getValue("queued_task_count").jsonPrimitive.int)
    }

    @Test fun `obligations are restricted to the authenticated actor selected agent and current thread`() {
        val own = incoming.copy(state = AgentRequest.State.WAITING_RESULT, waitFor = listOf("known-command"))
        val rows = listOf(own, incoming.copy(id = "user-wait", state = AgentRequest.State.WAITING_USER),
            incoming.copy(id = "outgoing", source = endpoint, target = peer),
            incoming.copy(id = "other-user", actorUserId = User.Id("other")),
            incoming.copy(id = "old-thread", target = endpoint.copy(threadId = Conversation.Thread.Id("old"))),
            incoming.copy(id = "other-agent", target = endpoint.copy(agentId = AgentDefinition.Id("other"))),
            incoming.copy(id = "completed", state = AgentRequest.State.COMPLETED, result = "PRIVATE_RESULT_SENTINEL"))
        val v = view(empty.copy(activeTask = task), rows, details = true)
        assertEquals("ACTIVE", v.getValue("foreground_state").jsonPrimitive.content)
        val requests = v.getValue("agent_requests").jsonObject
        assertEquals(2, requests.getValue("open_incoming").jsonPrimitive.int)
        assertEquals(1, requests.getValue("open_outgoing").jsonPrimitive.int)
        assertEquals(1, requests.getValue("incoming_waiting_user").jsonPrimitive.int)
        assertEquals(1, requests.getValue("incoming_waiting_result").jsonPrimitive.int)
        assertEquals(setOf("incoming", "user-wait", "outgoing"), requests.getValue("requests").jsonArray.map { it.jsonObject.getValue("request_id").jsonPrimitive.content }.toSet())
        assertFalse(v.toString().contains("PRIVATE_"))
        assertFalse(v.toString().contains(peer.conversationId.value + "-thread"))
    }

    @Test fun `details and wait references are bounded while summary omits request bodies and IDs`() {
        val rows = (0 until 20).map { incoming.copy(id = "request-$it", state = AgentRequest.State.WAITING_RESULT,
            waitFor = (0 until 20).map { i -> "wait-$i" }) }
        val summary = view(requests = rows).getValue("agent_requests").jsonObject
        assertEquals(20, summary.getValue("open_incoming").jsonPrimitive.int)
        assertFalse(summary.containsKey("requests"))
        val details = view(requests = rows, details = true).getValue("agent_requests").jsonObject
        assertTrue(details.getValue("details_truncated").jsonPrimitive.boolean)
        assertEquals(16, details.getValue("requests").jsonArray.size)
        val item = details.getValue("requests").jsonArray.first().jsonObject
        assertEquals(16, item.getValue("wait_for").jsonArray.size)
        assertTrue(item.getValue("wait_for_truncated").jsonPrimitive.boolean)
        assertFalse(details.toString().contains("PRIVATE_"))
        val capped = view(requests = (0 until 129).map { incoming.copy(id = "r-$it") }).getValue("agent_requests").jsonObject
        assertEquals(128, capped.getValue("open_incoming").jsonPrimitive.int)
        assertTrue(capped.getValue("possibly_truncated").jsonPrimitive.boolean)
    }

    @Test fun `a snapshot of another conversation must not be exposed`() {
        assertFailsWith<IllegalArgumentException> { view(empty.copy(conversationId = peer.conversationId)) }
    }
}
