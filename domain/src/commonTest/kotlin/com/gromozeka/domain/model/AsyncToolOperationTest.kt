package com.gromozeka.domain.model

import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.SlotCommandOrigin
import com.gromozeka.domain.tool.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.time.Instant

class AsyncToolOperationTest {
    private val at = Instant.fromEpochSeconds(10)
    private val owner = ConversationRuntimeServerSessionId("server-session")
    private val origin = AsyncToolOrigin(User.Id("actor"), Project.Id("project"), Conversation.Id("conversation"),
        Conversation.Thread.Id("source-thread"), AgentDefinition.Id("agent"), ConversationRuntimeTask.Id("source-task"),
        ContentItem.ToolCall.Id("source-call"), Conversation.Message.Id("source-task:result"), readOnly = true, slotOrigin = null)
    private val context = mapOf(
        TOOL_CONTEXT_USER_ID to origin.actorUserId.value,
        TOOL_CONTEXT_PROJECT_ID to origin.projectId.value,
        TOOL_CONTEXT_CONVERSATION_ID to origin.conversationId.value,
        TOOL_CONTEXT_THREAD_ID to origin.threadId.value,
        TOOL_CONTEXT_AGENT_DEFINITION_ID to origin.agentDefinitionId.value,
        "toolCallId" to origin.sourceToolCallId.value,
    )
    private val invocation = AsyncToolInvocation(origin, "tool_v1", "a".repeat(64),
        ContentItem.ToolCall(origin.sourceToolCallId, ContentItem.ToolCall.Data("tool", JsonObject(emptyMap()))),
        ConversationRuntimeTaskTarget.Server, context)
    private val accepted = AsyncToolOperation(AsyncToolOperation.Id("operation"), invocation, at)

    @Test fun acknowledgementFencePrecedesClaimAndExecution() {
        assertEquals(Phase.ACCEPTED, accepted.phase)
        assertFailsWith<IllegalArgumentException> { accepted.copy(phase = Phase.ACKNOWLEDGED) }
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(phase = Phase.CLAIMED, claimedAt = at, executorSessionId = owner, claimGeneration = 1, leaseDeadline = at)
        }
        val ack = accepted.copy(phase = Phase.ACKNOWLEDGED, acknowledgedAt = at)
        val claimed = ack.copy(phase = Phase.CLAIMED, claimedAt = at, executorSessionId = owner, claimGeneration = 1, leaseDeadline = at)
        assertNull(claimed.startedAt)
        assertNull(accepted.claimReference)
        assertEquals(AsyncToolDispatchReference(accepted.id, owner, 1), claimed.claimReference)
        assertFailsWith<IllegalArgumentException> { claimed.copy(claimGeneration = 0) }
        assertFailsWith<IllegalArgumentException> { accepted.copy(claimGeneration = 1) }
        assertFailsWith<IllegalArgumentException> { accepted.copy(claimGeneration = -1) }
        assertNotEquals(claimed.claimReference, claimed.copy(claimGeneration = 2).claimReference)
        val running = claimed.copy(phase = Phase.RUNNING, startedAt = at)
        assertEquals(owner, running.executorSessionId)
    }

    @Test fun instantOutcomeCannotBeProjectedBeforeTheSourceAcknowledgement() {
        val finished = accepted.copy(phase = Phase.FAILED, completedAt = at,
            outcome = AsyncToolOutcome(isError = true, failureCode = "REJECTED"))
        assertFailsWith<IllegalArgumentException> { finished.copy(projectedAt = at) }
        assertEquals(at, finished.copy(acknowledgedAt = at, projectedAt = at).projectedAt)
    }

    @Test fun cancellationBeforeStartIsDifferentFromUnknownAfterEffects() {
        val cancelled = accepted.copy(phase = Phase.CANCELLED, completedAt = at,
            outcome = AsyncToolOutcome(isError = true, failureCode = "CANCELLED_BEFORE_START"))
        assertNull(cancelled.startedAt)
        assertFailsWith<IllegalArgumentException> {
            cancelled.copy(acknowledgedAt = at, claimedAt = at, executorSessionId = owner, claimGeneration = 1, startedAt = at)
        }
        val unknown = cancelled.copy(phase = Phase.OUTCOME_UNKNOWN, acknowledgedAt = at,
            claimedAt = at, executorSessionId = owner, claimGeneration = 1, startedAt = at,
            outcome = AsyncToolOutcome(isError = true, failureCode = "OUTCOME_UNKNOWN"))
        assertTrue(unknown.phase.isTerminal)
        assertNull(unknown.leaseDeadline)
    }

    @Test fun errorsAndIncompleteStateCannotPretendToBeSuccess() {
        assertFailsWith<IllegalArgumentException> { accepted.copy(phase = Phase.SUCCEEDED) }
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(phase = Phase.SUCCEEDED, completedAt = at, outcome = AsyncToolOutcome(isError = false))
        }
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(phase = Phase.SUCCEEDED, completedAt = at, outcome = AsyncToolOutcome(isError = true))
        }
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(phase = Phase.OUTCOME_UNKNOWN, completedAt = at, outcome = AsyncToolOutcome(isError = false))
        }
    }

    @Test fun capturedActorBranchAndAgentCannotBeRebound() {
        for (key in listOf(TOOL_CONTEXT_USER_ID, TOOL_CONTEXT_PROJECT_ID, TOOL_CONTEXT_CONVERSATION_ID,
            TOOL_CONTEXT_THREAD_ID, TOOL_CONTEXT_AGENT_DEFINITION_ID, "toolCallId")) {
            assertFailsWith<IllegalArgumentException> { invocation.copy(context = context + (key to "another-binding")) }
        }
        assertFailsWith<IllegalArgumentException> { origin.copy(acknowledgementMessageId = Conversation.Message.Id("another-task:result")) }
        assertFailsWith<IllegalArgumentException> { invocation.copy(origin = origin.copy(slotOrigin = SlotCommandOrigin("slot", 2, "lease"))) }
    }

    @Test fun workspaceAndLeaseSnapshotRequireTheExactWorkerMountAndRoot() {
        val target = ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"))
        val workerContext = context + mapOf(TOOL_CONTEXT_WORKER_ID to "worker", TOOL_CONTEXT_WORKSPACE_MOUNT_ID to "mount",
            TOOL_CONTEXT_WORKSPACE_ID to "workspace", TOOL_CONTEXT_WORKSPACE_ROOT_PATH to "/captured-checkout")
        val captured = invocation.copy(origin = origin.copy(slotOrigin = SlotCommandOrigin("slot", 2, "original-lease")),
            target = target, context = workerContext)
        assertEquals("original-lease", captured.origin.slotOrigin?.leaseId)
        assertFailsWith<IllegalArgumentException> { captured.copy(context = workerContext + (TOOL_CONTEXT_WORKER_ID to "new-worker")) }
        assertFailsWith<IllegalArgumentException> { captured.copy(context = workerContext + (TOOL_CONTEXT_WORKSPACE_MOUNT_ID to "new-mount")) }
        assertFailsWith<IllegalArgumentException> { captured.copy(context = workerContext - TOOL_CONTEXT_WORKSPACE_ROOT_PATH) }
        assertEquals(captured, Json.decodeFromString<AsyncToolInvocation>(Json.encodeToString(AsyncToolInvocation.serializer(), captured)))
    }
}
