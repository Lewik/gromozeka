package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCommand
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryConversationRuntimeStoresTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agent = AgentDefinition(
        id = AgentDefinition.Id("agent-1"),
        name = "Test Agent",
        prompts = listOf(Prompt.Id("prompt-1")),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
        type = AgentDefinition.Type.Inline,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `coordinator claims one end-of-turn command and exposes active insertions separately`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = command("message-1", QueuedMessagePlacement.END_OF_TURN)
        val steering = command("message-2", QueuedMessagePlacement.AFTER_TOOL_RESULT)
        val second = command("message-3", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(first))
        assertTrue(coordinator.submit(second))

        assertEquals(first, coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null))
        assertTrue(coordinator.submit(steering))
        assertEquals(
            listOf(steering.id),
            coordinator.takeActiveInsertions(conversationId, QueuedMessagePlacement.AFTER_TOOL_RESULT).map { it.id },
        )
        assertNull(coordinator.claimNextTurn(conversationId, "worker-2", leaseUntil = null))

        coordinator.completeActiveTurn(conversationId)

        assertEquals(second, coordinator.claimNextTurn(conversationId, "worker-2", leaseUntil = null))
        coordinator.completeActiveTurn(conversationId)
        assertTrue(coordinator.finishIfIdle(conversationId))
        assertEquals(emptyList(), coordinator.listPending(conversationId))
    }

    @Test
    fun `coordinator replaces command with the same user message id`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = command("active-message", QueuedMessagePlacement.END_OF_TURN)
        val original = command("message-1", QueuedMessagePlacement.END_OF_TURN)
        val steering = original.copy(placement = QueuedMessagePlacement.AFTER_TOOL_RESULT)

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null))
        coordinator.submit(original)
        coordinator.submit(steering)

        assertEquals(listOf(steering), coordinator.listPending(conversationId))
    }

    @Test
    fun `coordinator rejects active insertions without active execution`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()

        assertFalse(coordinator.submit(command("message-1", QueuedMessagePlacement.AFTER_TOOL_RESULT)))
        assertEquals(emptyList(), coordinator.listPending(conversationId))
    }

    @Test
    fun `coordinator promotes missed active insertions to the next turn`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = command("message-1", QueuedMessagePlacement.END_OF_TURN)
        val steering = command("message-2", QueuedMessagePlacement.AFTER_TOOL_RESULT)

        coordinator.submit(first)
        assertEquals(first, coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null))
        coordinator.submit(steering)

        coordinator.completeActiveTurn(conversationId)

        val next = coordinator.claimNextTurn(conversationId, "worker-2", leaseUntil = null)
        assertEquals(steering.id, next?.id)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, next?.placement)
    }

    @Test
    fun `coordinator promotes missed active insertions before ordinary queued turns`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = command("active-message", QueuedMessagePlacement.END_OF_TURN)
        val queued = command("message-1", QueuedMessagePlacement.END_OF_TURN)
        val steering = command("message-2", QueuedMessagePlacement.AFTER_TOOL_RESULT)

        coordinator.submit(active)
        assertEquals(active, coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null))
        coordinator.submit(queued)
        coordinator.submit(steering)

        coordinator.completeActiveTurn(conversationId)

        val next = coordinator.claimNextTurn(conversationId, "worker-2", leaseUntil = null)
        assertEquals(steering.id, next?.id)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, next?.placement)
        coordinator.completeActiveTurn(conversationId)

        assertEquals(queued.id, coordinator.claimNextTurn(conversationId, "worker-3", leaseUntil = null)?.id)
    }

    @Test
    fun `coordinator keeps one active command per conversation and supports controls`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = command("message-1", QueuedMessagePlacement.END_OF_TURN)
        val second = command("message-2", QueuedMessagePlacement.END_OF_TURN)

        coordinator.submit(first)
        coordinator.submit(second)

        assertEquals(first, coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null))
        assertNull(coordinator.claimNextTurn(conversationId, "worker-2", leaseUntil = null))

        coordinator.markPhase(conversationId, ConversationExecutionState.Phase.RUNNING_TOOL)
        assertEquals(ConversationExecutionState.Phase.RUNNING_TOOL, coordinator.find(conversationId)?.phase)

        assertTrue(coordinator.requestPause(conversationId))
        assertEquals(ConversationExecutionState.Status.PAUSE_REQUESTED, coordinator.find(conversationId)?.status)
        assertTrue(coordinator.markPaused(conversationId))
        assertEquals(ConversationExecutionState.Status.PAUSED, coordinator.find(conversationId)?.status)
        assertTrue(coordinator.requestResume(conversationId))
        assertEquals(ConversationExecutionState.Status.RUNNING, coordinator.find(conversationId)?.status)

        assertTrue(coordinator.requestStop(conversationId))
        assertEquals(ConversationExecutionState.Status.STOPPING, coordinator.find(conversationId)?.status)
        assertEquals(emptyList(), coordinator.listPending(conversationId))

        coordinator.completeActiveTurn(conversationId)
        assertTrue(coordinator.finishIfIdle(conversationId))
        assertNull(coordinator.find(conversationId))
    }

    @Test
    fun `coordinator finishes pause-requested idle execution when no work remains`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = command("message-1", QueuedMessagePlacement.END_OF_TURN)

        coordinator.submit(first)
        assertEquals(first, coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null))

        assertTrue(coordinator.requestPause(conversationId))
        coordinator.completeActiveTurn(conversationId)

        assertTrue(coordinator.finishIfIdle(conversationId))
        assertNull(coordinator.find(conversationId))
    }

    @Test
    fun `coordinator stop clears pending commands even without active execution`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()

        coordinator.submit(command("message-1", QueuedMessagePlacement.END_OF_TURN))

        assertTrue(coordinator.requestStop(conversationId))
        assertEquals(emptyList(), coordinator.listPending(conversationId))
        assertEquals(ConversationExecutionState.Status.STOPPING, coordinator.find(conversationId)?.status)
        assertTrue(coordinator.finishIfIdle(conversationId))
        assertNull(coordinator.find(conversationId))
    }

    @Test
    fun `coordinator interrupt clears pending commands even without active execution`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()

        coordinator.submit(command("message-1", QueuedMessagePlacement.END_OF_TURN))

        assertTrue(coordinator.requestInterrupt(conversationId))
        assertEquals(emptyList(), coordinator.listPending(conversationId))
        assertEquals(ConversationExecutionState.Status.INTERRUPTING, coordinator.find(conversationId)?.status)
        assertTrue(coordinator.finishIfIdle(conversationId))
        assertNull(coordinator.find(conversationId))
    }

    @Test
    fun `coordinator abort drops active execution and pending commands`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()

        coordinator.submit(command("message-1", QueuedMessagePlacement.END_OF_TURN))
        coordinator.submit(command("message-2", QueuedMessagePlacement.END_OF_TURN))

        assertEquals(
            ConversationRuntimeCommand.Id("message-1"),
            coordinator.claimNextTurn(conversationId, "worker-1", leaseUntil = null)?.id,
        )

        coordinator.abort(conversationId)

        assertNull(coordinator.find(conversationId))
        assertEquals(emptyList(), coordinator.listPending(conversationId))
        assertNull(coordinator.claimNextTurn(conversationId, "worker-2", leaseUntil = null))
    }

    private fun command(
        messageId: String,
        placement: QueuedMessagePlacement,
    ): ConversationRuntimeCommand {
        val message = Conversation.Message(
            id = Conversation.Message.Id(messageId),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Text $messageId")),
            createdAt = Clock.System.now(),
        )

        return ConversationRuntimeCommand(
            id = ConversationRuntimeCommand.Id(messageId),
            conversationId = conversationId,
            userMessage = message,
            agent = agent,
            placement = placement,
            createdAt = Clock.System.now(),
        )
    }
}
