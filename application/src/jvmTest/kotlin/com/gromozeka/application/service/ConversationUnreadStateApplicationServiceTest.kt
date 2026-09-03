package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationUnreadState
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationUnreadStateRepository
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ConversationUnreadStateApplicationServiceTest {
    @Test
    fun `messages mark recipients unread and read state remains isolated by user`() = runBlocking {
        val conversationRepository = FakeConversationRepository().apply { create(conversation) }
        val unreadRepository = TestConversationUnreadStateRepository()
        val changes = RecordingStateChanges()
        val service = ConversationUnreadStateApplicationService(unreadRepository, conversationRepository, changes)

        service.recordMessage(conversation, userMessage)

        assertEquals(emptySet(), service.snapshot(firstUserId).conversationIds)
        assertEquals(setOf(conversation.id), service.snapshot(secondUserId).conversationIds)
        assertEquals(
            listOf(DeclarativeStateKey.conversationUnreadState(secondUserId)),
            changes.keys,
        )

        service.recordMessage(conversation, agentMessage)

        assertEquals(setOf(conversation.id), service.snapshot(firstUserId).conversationIds)
        assertEquals(setOf(conversation.id), service.snapshot(secondUserId).conversationIds)
        assertEquals(
            listOf(
                DeclarativeStateKey.conversationUnreadState(secondUserId),
                DeclarativeStateKey.conversationUnreadState(firstUserId),
            ),
            changes.keys,
        )

        val updated = service.markRead(secondUserId, conversation.id)

        assertEquals(emptySet(), updated.conversationIds)
        assertEquals(setOf(conversation.id), service.snapshot(firstUserId).conversationIds)
        assertEquals(
            DeclarativeStateKey.conversationUnreadState(secondUserId),
            changes.keys.last(),
        )
    }

    @Test
    fun `only connected users can mark a conversation read`() = runBlocking {
        val conversationRepository = FakeConversationRepository().apply { create(conversation) }
        val service = ConversationUnreadStateApplicationService(
            TestConversationUnreadStateRepository(),
            conversationRepository,
            RecordingStateChanges(),
        )

        assertFailsWith<IllegalArgumentException> {
            service.markRead(User.Id("other-user"), conversation.id)
        }
        Unit
    }

    @Test
    fun `tool activity is ignored while user-visible attachments mark recipients unread`() = runBlocking {
        val conversationRepository = FakeConversationRepository().apply { create(conversation) }
        val changes = RecordingStateChanges()
        val service = ConversationUnreadStateApplicationService(
            TestConversationUnreadStateRepository(),
            conversationRepository,
            changes,
        )

        service.recordMessage(
            conversation,
            message(
                role = Conversation.Message.Role.ASSISTANT,
                author = Conversation.Message.Author.Agent(AgentDefinition.Id("agent-1"), "Agent"),
                content = Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id("tool-1"),
                    call = Conversation.Message.ContentItem.ToolCall.Data("example", JsonObject(emptyMap())),
                ),
            ),
        )

        assertEquals(emptySet(), service.snapshot(firstUserId).conversationIds)
        assertEquals(emptySet(), service.snapshot(secondUserId).conversationIds)
        assertEquals(emptyList(), changes.keys)

        service.recordMessage(
            conversation,
            message(
                role = Conversation.Message.Role.ASSISTANT,
                author = Conversation.Message.Author.Agent(AgentDefinition.Id("agent-1"), "Agent"),
                content = Conversation.Message.ContentItem.ImageItem(
                    Conversation.Message.ImageSource.UrlImageSource("https://example.com/image.png"),
                ),
            ),
        )

        assertEquals(setOf(conversation.id), service.snapshot(firstUserId).conversationIds)
        assertEquals(setOf(conversation.id), service.snapshot(secondUserId).conversationIds)
    }

    private val firstUserId = User.Id("user-1")
    private val secondUserId = User.Id("user-2")
    private val conversation = Conversation(
        id = Conversation.Id("conversation-1"),
        projectId = Project.Id("project-1"),
        participants = setOf(
            Conversation.Participant.User(firstUserId),
            Conversation.Participant.User(secondUserId),
            Conversation.Participant.Agent(AgentDefinition.Id("agent-1")),
        ),
        currentThread = Conversation.Thread.Id("thread-1"),
        createdAt = Instant.parse("2026-09-03T00:00:00Z"),
        updatedAt = Instant.parse("2026-09-03T00:00:00Z"),
    )
    private val userMessage = message(
        role = Conversation.Message.Role.USER,
        author = Conversation.Message.Author.User(firstUserId, "First"),
        content = Conversation.Message.ContentItem.UserMessage("Hello"),
    )
    private val agentMessage = message(
        role = Conversation.Message.Role.ASSISTANT,
        author = Conversation.Message.Author.Agent(AgentDefinition.Id("agent-1"), "Agent"),
        content = Conversation.Message.ContentItem.AssistantMessage(
            Conversation.Message.StructuredText(fullText = "Hi"),
        ),
    )

    private fun message(
        role: Conversation.Message.Role,
        author: Conversation.Message.Author,
        content: Conversation.Message.ContentItem,
    ) = Conversation.Message(
        id = Conversation.Message.Id("message-${role.name.lowercase()}"),
        conversationId = conversation.id,
        role = role,
        author = author,
        content = listOf(content),
        createdAt = Instant.parse("2026-09-03T00:00:01Z"),
    )
}

private class TestConversationUnreadStateRepository : ConversationUnreadStateRepository {
    private val unreadByUser = mutableMapOf<User.Id, MutableSet<Conversation.Id>>()

    override suspend fun load(userId: User.Id): ConversationUnreadState =
        ConversationUnreadState(unreadByUser[userId].orEmpty())

    override suspend fun markUnread(
        conversationId: Conversation.Id,
        userIds: Set<User.Id>,
    ): Set<User.Id> = userIds.filterTo(mutableSetOf()) { userId ->
        unreadByUser.getOrPut(userId, ::mutableSetOf).add(conversationId)
    }

    override suspend fun markRead(
        conversationId: Conversation.Id,
        userId: User.Id,
    ): Boolean = unreadByUser[userId]?.remove(conversationId) == true
}

private class RecordingStateChanges : DeclarativeStateChangePublisher {
    val keys = mutableListOf<DeclarativeStateKey>()

    override fun publish(vararg keys: DeclarativeStateKey) {
        this.keys += keys
    }
}
