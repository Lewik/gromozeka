package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ConversationTabLayoutRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationTabLayoutApplicationServiceTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val projectId = Project.Id("project-1")

    @Test
    fun `open and close preserve order and only advance revision on changes`() = runBlocking {
        val first = conversation("conversation-1")
        val second = conversation("conversation-2")
        val repository = TestConversationTabLayoutRepository()
        val service = ConversationTabLayoutApplicationService(
            repository = repository,
            conversationRepository = TestConversationRepository(first, second),
        )

        val opened = service.open(first.id)
        assertEquals(listOf(first.id), opened.conversationIds)
        assertEquals(1, opened.revision)
        assertEquals(opened, service.snapshot())
        assertEquals(1, service.open(first.id).revision)
        assertEquals(listOf(first.id, second.id), service.open(second.id).conversationIds)

        val closed = service.close(first.id)
        assertEquals(listOf(second.id), closed.conversationIds)
        assertEquals(3, closed.revision)
        assertEquals(3, service.close(first.id).revision)
    }

    @Test
    fun `cannot open a missing conversation`() = runBlocking {
        val service = ConversationTabLayoutApplicationService(
            repository = TestConversationTabLayoutRepository(),
            conversationRepository = TestConversationRepository(),
        )

        assertFailsWith<IllegalArgumentException> {
            service.open(Conversation.Id("missing"))
        }
        Unit
    }

    private fun conversation(id: String): Conversation = Conversation(
        id = Conversation.Id(id),
        projectId = projectId,
        agentDefinitionId = AgentDefinition.Id("agent-1"),
        currentThread = Conversation.Thread.Id("thread-$id"),
        createdAt = now,
        updatedAt = now,
    )
}

private class TestConversationTabLayoutRepository : ConversationTabLayoutRepository {
    private var layout = ConversationTabLayout()

    override suspend fun load(): ConversationTabLayout = layout

    override suspend fun save(layout: ConversationTabLayout): ConversationTabLayout =
        layout.also { this.layout = it }
}

private class TestConversationRepository(
    vararg conversations: Conversation,
) : ConversationRepository {
    private val conversations = conversations.associateBy(Conversation::id).toMutableMap()

    override suspend fun create(conversation: Conversation): Conversation =
        conversation.also { conversations[it.id] = it }

    override suspend fun findById(id: Conversation.Id): Conversation? = conversations[id]

    override suspend fun findByProject(projectId: Project.Id): List<Conversation> =
        conversations.values.filter { it.projectId == projectId }

    override suspend fun delete(id: Conversation.Id) {
        conversations.remove(id)
    }

    override suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id) {
        conversations.computeIfPresent(id) { _, conversation -> conversation.copy(currentThread = threadId) }
    }

    override suspend fun updateDisplayName(id: Conversation.Id, displayName: String) {
        conversations.computeIfPresent(id) { _, conversation -> conversation.copy(displayName = displayName) }
    }

    override suspend fun updateAgentDefinition(id: Conversation.Id, agentDefinitionId: AgentDefinition.Id) {
        conversations.computeIfPresent(id) { _, conversation -> conversation.copy(agentDefinitionId = agentDefinitionId) }
    }

    override suspend fun touch(id: Conversation.Id) = Unit
}
