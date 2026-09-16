package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryCursor
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationHistoryRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.PositionedConversationMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConversationHistoryReadServiceTest {
    private val conversationId = Conversation.Id("conversation")
    private val threadId = Conversation.Thread.Id("current-thread")
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val json = Json { encodeDefaults = true }

    @Test
    fun byteBoundedPagesCoverEveryMessageWithoutGapsInEitherDirection() = runBlocking {
        val service = service(140)
        val visited = mutableListOf<Int>()
        var page = service.page(conversationId, ConversationHistoryPageRequest())
        while (true) {
            assertTrue(json.encodeToString(page).encodeToByteArray().size <= ConversationHistoryReadService.MAX_PAGE_BYTES)
            visited += page.messages.map { it.position }
            val older = page.older ?: break
            page = service.page(conversationId, ConversationHistoryPageRequest(before = older))
        }
        assertEquals((0 until 140).toList(), visited.sorted())
        val forward = mutableListOf<Int>()
        page = service.page(conversationId, ConversationHistoryPageRequest(around = Conversation.Message.Id("message-0")))
        while (true) {
            forward += page.messages.map { it.position }
            val newer = page.newer ?: break
            page = service.page(conversationId, ConversationHistoryPageRequest(after = newer))
        }
        assertEquals((0 until 140).toList(), forward)
    }

    @Test
    fun boundedSearchWindowIncludesTheRequestedMessage() = runBlocking {
        val page = service(140).page(conversationId, ConversationHistoryPageRequest(around = Conversation.Message.Id("message-60")))
        assertTrue(page.messages.any { it.position == 60 })
        assertNotNull(page.older)
        assertNotNull(page.newer)
        assertTrue(page.messages.size < 50)
    }

    @Test
    fun cursorFromReplacedThreadResetsToLatestPage() = runBlocking {
        val page = service(140).page(conversationId, ConversationHistoryPageRequest(before = ConversationHistoryCursor(Conversation.Thread.Id("old-thread"), 10)))
        assertTrue(page.reset)
        assertEquals(threadId, page.threadId)
        assertEquals(139, page.messages.last().position)
    }

    @Test
    fun removedScrollAnchorUsesItsPositionInTheNewThread() = runBlocking {
        val page = service(140).page(conversationId, ConversationHistoryPageRequest(around = Conversation.Message.Id("removed-message"), positionHint = 60))
        assertFalse(page.reset)
        assertTrue(page.messages.any { it.position == 60 })
    }

    private fun service(size: Int): ConversationHistoryReadService {
        val conversation = Conversation(
            id = conversationId, projectId = Project.Id("project"),
            participants = setOf(Conversation.Participant.User(com.gromozeka.domain.model.User.Id("user"))),
            currentThread = threadId, createdAt = now, updatedAt = now,
        )
        val entries = (0 until size).map { position -> PositionedConversationMessage(position, Conversation.Message(
            id = Conversation.Message.Id("message-$position"), conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("x".repeat(20_000))), createdAt = now,
        )) }
        val repository = stub<ConversationHistoryRepository> { method, args -> when {
            method.startsWith("position") -> entries.firstOrNull { it.message.id.value == args[1] }?.position
            method.startsWith("page") -> {
                val before = args[1] as Int?
                val after = args[2] as Int?
                val limit = args[3] as Int
                val matching = entries.filter { (before == null || it.position < before) && (after == null || it.position > after) }
                if (after == null) matching.takeLast(limit) else matching.take(limit)
            }
            method.startsWith("toolResults") -> emptyList<PositionedConversationMessage>()
            else -> error("Unexpected history call $method")
        } }
        return ConversationHistoryReadService(
            stub<ConversationRepository> { method, _ ->
                check(method.startsWith("findById"))
                conversation
            },
            repository,
            InMemoryConversationRuntimeCoordinator(),
        )
    }

    private inline fun <reified T> stub(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T
}
