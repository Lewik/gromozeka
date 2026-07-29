package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
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
    private val userId = User.Id("user-1")

    @Test
    fun `open and close preserve order and only advance revision on changes`() = runBlocking {
        val first = conversation("conversation-1")
        val second = conversation("conversation-2")
        val repository = TestConversationTabLayoutRepository()
        val service = ConversationTabLayoutApplicationService(
            repository = repository,
            conversationRepository = TestConversationRepository(first, second),
        )

        val opened = service.open(userId, first.id)
        assertEquals(listOf(first.id), opened.conversationIds)
        assertEquals(1, opened.revision)
        assertEquals(opened, service.snapshot(userId))
        assertEquals(1, service.open(userId, first.id).revision)
        assertEquals(listOf(first.id, second.id), service.open(userId, second.id).conversationIds)

        val closed = service.close(userId, first.id)
        assertEquals(listOf(second.id), closed.conversationIds)
        assertEquals(3, closed.revision)
        assertEquals(3, service.close(userId, first.id).revision)
    }

    @Test
    fun `cannot open a missing conversation`() = runBlocking {
        val service = ConversationTabLayoutApplicationService(
            repository = TestConversationTabLayoutRepository(),
            conversationRepository = TestConversationRepository(),
        )

        assertFailsWith<IllegalArgumentException> {
            service.open(userId, Conversation.Id("missing"))
        }
        Unit
    }

    @Test
    fun `layouts are isolated by user and conversation removal updates all owners`() = runBlocking {
        val conversation = conversation("conversation-1")
        val service = ConversationTabLayoutApplicationService(
            repository = TestConversationTabLayoutRepository(),
            conversationRepository = TestConversationRepository(conversation),
        )
        val secondUserId = User.Id("user-2")

        service.open(userId, conversation.id)
        service.open(secondUserId, conversation.id)
        service.close(userId, conversation.id)

        assertEquals(emptyList(), service.snapshot(userId).conversationIds)
        assertEquals(listOf(conversation.id), service.snapshot(secondUserId).conversationIds)

        service.removeConversation(conversation.id)

        assertEquals(emptyList(), service.snapshot(secondUserId).conversationIds)
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
    private val layouts = mutableMapOf<User.Id, ConversationTabLayout>()

    override suspend fun load(userId: User.Id): ConversationTabLayout =
        layouts[userId] ?: ConversationTabLayout()

    override suspend fun loadAll(): Map<User.Id, ConversationTabLayout> = layouts.toMap()

    override suspend fun save(userId: User.Id, layout: ConversationTabLayout): ConversationTabLayout =
        layout.also { layouts[userId] = it }
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
