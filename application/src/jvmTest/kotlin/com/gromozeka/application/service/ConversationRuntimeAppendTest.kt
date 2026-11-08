package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import kotlin.test.*
import kotlin.time.Instant

class ConversationRuntimeAppendTest {
    private val now = Instant.fromEpochSeconds(1)
    private val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
        setOf(Conversation.Participant.User(User.Id("user"))), currentThread = Conversation.Thread.Id("current"),
        createdAt = now, updatedAt = now)
    private val thread = Conversation.Thread(conversation.currentThread, conversation.id, createdAt = now, updatedAt = now)
    private val message = Conversation.Message(Conversation.Message.Id("message"), conversation.id,
        role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage("body")), createdAt = now)

    @Test fun `append and replay use point membership and never enumerate links`() = runBlocking {
        val f = Fixture()
        f.service.appendRuntimeMessage(conversation.id, message)
        f.service.appendRuntimeMessage(conversation.id, message)
        assertEquals(listOf(0), f.positions)
        assertEquals(1, f.saves)
        assertEquals(1, f.adds)
        assertEquals(1, f.touches)
    }

    @Test fun `stored message from another thread is linked to the current thread`() = runBlocking {
        val f = Fixture(); f.saved = message
        f.service.appendRuntimeMessage(conversation.id, message)
        assertEquals(0, f.saves)
        assertEquals(1, f.adds)
        assertEquals(listOf(0), f.positions)
    }

    @Test fun `same-id different-body collision is not accepted as replay`() = runBlocking {
        val f = Fixture(); f.saved = message; f.linked = true
        assertFailsWith<IllegalArgumentException> {
            f.service.appendRuntimeMessage(conversation.id, message.copy(content = emptyList()))
        }
        assertEquals(0, f.adds); assertEquals(0, f.touches)
    }

    @Test fun `add failure is accepted only when the exact link exists afterward`() = runBlocking {
        for (linkedAfterFailure in listOf(false, true)) {
            val f = Fixture(); f.addFailure = IllegalStateException("synthetic add failure")
            f.linkAfterFailure = linkedAfterFailure
            if (linkedAfterFailure) f.service.appendRuntimeMessage(conversation.id, message)
            else assertSame(f.addFailure, assertFailsWith<IllegalStateException> {
                f.service.appendRuntimeMessage(conversation.id, message)
            })
            assertEquals(if (linkedAfterFailure) 1 else 0, f.touches)
        }
    }

    private inner class Fixture {
        var saved: Conversation.Message? = null
        var linked = false
        var saves = 0; var adds = 0; var touches = 0
        var addFailure: Exception? = null
        var linkAfterFailure = false
        val positions = mutableListOf<Int>()
        val conversations = responding<ConversationRepository> {
            when (it.method.name.substringBefore('-')) {
                "findById" -> conversation
                "touch" -> { touches++; Unit }
                else -> error("Unexpected conversation operation")
            }
        }
        val threads = responding<ThreadRepository> {
            when (it.method.name.substringBefore('-')) {
                "findById" -> thread
                "updateTimestamp" -> Unit
                else -> error("Unexpected thread operation")
            }
        }
        val messages = responding<MessageRepository> {
            when (it.method.name.substringBefore('-')) {
                "findById" -> saved
                "save" -> (it.arguments[0] as Conversation.Message).also { value -> saved = value; saves++ }
                else -> error("Unexpected message operation")
            }
        }
        val links = responding<ThreadMessageRepository> {
            assertEquals(thread.id.value, it.arguments[0])
            when (it.method.name.substringBefore('-')) {
                "contains" -> { assertEquals(message.id.value, it.arguments[1]); linked }
                "getMaxPosition" -> positions.maxOrNull()
                "add" -> {
                    assertEquals(message.id.value, it.arguments[1]); adds++
                    addFailure?.let { failure -> linked = linkAfterFailure; throw failure }
                    positions += it.arguments[2] as Int; linked = true; Unit
                }
                else -> error("Append must not enumerate messages or links: ${it.method.name}")
            }
        }
        val service = ConversationApplicationService(conversations, threads, messages, links,
            mock(), mock(), mock(), ToolCallPairingService(), mock(), mock(), mock(), mock(), mock())
    }
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    private inline fun <reified T> responding(noinline answer: (InvocationOnMock) -> Any?): T =
        Mockito.mock(T::class.java) { answer(it) }
}
