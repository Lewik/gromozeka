package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationRuntimeAppendTest {
    private val now = Instant.fromEpochSeconds(1)
    private val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
        setOf(Conversation.Participant.User(User.Id("user"))), currentThread = Conversation.Thread.Id("current"),
        createdAt = now, updatedAt = now)
    private val message = Conversation.Message(Conversation.Message.Id("message"), conversation.id,
        role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage("body")), createdAt = now)

    @Test fun `single append and replay use the atomic writer without enumerating history`() = runBlocking {
        val f = Fixture()
        f.service.appendRuntimeMessage(conversation.id, message)
        f.service.appendRuntimeMessage(conversation.id, message.copy(createdAt = Instant.fromEpochSeconds(2)))
        assertEquals(listOf(message.id), f.linked)
        assertEquals(1, f.bodyWrites)
        assertEquals(1, f.commits)
        Mockito.verifyNoInteractions(f.threads, f.messages, f.links)
    }

    @Test fun `stored body without a current-thread link is linked without another insert`() = runBlocking {
        val f = Fixture(); f.stored[message.id] = message
        f.service.appendRuntimeMessage(conversation.id, message)
        assertEquals(0, f.bodyWrites)
        assertEquals(listOf(message.id), f.linked)
    }

    @Test fun `batch preserves order and rejects a body collision before writing any sibling`() = runBlocking {
        val f = Fixture(); f.stored[message.id] = message
        val sibling = message.copy(id = Conversation.Message.Id("sibling"))
        assertFailsWith<IllegalArgumentException> {
            f.service.appendRuntimeMessages(conversation.id, listOf(sibling, message.copy(content = emptyList())))
        }
        assertNull(f.stored[sibling.id]); assertTrue(f.linked.isEmpty()); assertEquals(0, f.commits)
        assertEquals(listOf(sibling, message), f.service.appendRuntimeMessages(conversation.id, listOf(sibling, message)))
        assertEquals(listOf(sibling.id, message.id), f.linked)
        assertEquals(1, f.commits)
    }

    @Test fun `atomic writer failure is propagated rather than turned into successful delivery`() = runBlocking {
        val f = Fixture(); val error = IllegalStateException("Synthetic write failure"); f.failure = error
        assertSame(error, assertFailsWith<IllegalStateException> { f.service.appendRuntimeMessage(conversation.id, message) })
        assertTrue(f.stored.isEmpty()); assertTrue(f.linked.isEmpty()); assertEquals(0, f.commits)
        Mockito.verifyNoInteractions(f.unread)
    }

    private inner class Fixture {
        val stored = mutableMapOf<Conversation.Message.Id, Conversation.Message>()
        val linked = mutableListOf<Conversation.Message.Id>()
        var bodyWrites = 0
        var commits = 0
        var failure: Exception? = null
        val conversations = mock<ConversationRepository>().also {
            Mockito.`when`(runBlocking { it.findById(conversation.id) }).thenReturn(conversation)
        }
        val threads = mock<ThreadRepository>()
        val messages = mock<MessageRepository>()
        val links = mock<ThreadMessageRepository>()
        val unread = mock<ConversationUnreadStateApplicationService>()
        val writer = object : ConversationMessageBatchRepository {
            override suspend fun append(conversationId: Conversation.Id, expectedThreadId: Conversation.Thread.Id,
                messages: List<Conversation.Message>, at: Instant): List<Conversation.Message> {
                assertEquals(conversation.id, conversationId); assertEquals(conversation.currentThread, expectedThreadId)
                messages.forEach { require(stored[it.id]?.samePersistentBodyAs(it) != false) }
                failure?.let { throw it }
                val appended = messages.filterNot { it.id in linked }
                if (appended.isEmpty()) return emptyList()
                appended.forEach { if (stored.putIfAbsent(it.id, it) == null) bodyWrites++ }
                linked += appended.map { it.id }
                commits++
                return appended.map { stored.getValue(it.id) }
            }
        }
        val service = ConversationApplicationService(conversations, threads, messages, links,
            mock(), mock(), mock(), ToolCallPairingService(), mock(), unread, mock(), mock(), mock(), writer)
    }
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
