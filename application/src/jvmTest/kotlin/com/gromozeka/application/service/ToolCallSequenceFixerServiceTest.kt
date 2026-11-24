package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ToolSequenceFacts
import com.gromozeka.domain.model.ToolSequenceMessageHeader
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ToolCallSequenceFixerServiceTest {
    @Test
    fun `repair preserves a newly appended user message after an orphaned tool call`() = runBlocking {
        val now = Instant.fromEpochSeconds(1)
        val conversationId = Conversation.Id("conversation")
        val sourceThreadId = Conversation.Thread.Id("source-thread")
        val conversation = Conversation(
            id = conversationId,
            projectId = Project.Id("project"),
            participants = setOf(Conversation.Participant.User(User.Id("user"))),
            currentThread = sourceThreadId,
            createdAt = now,
            updatedAt = now,
        )
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("orphan-call")
        val toolCall = Conversation.Message(
            id = Conversation.Message.Id("tool-call-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = toolCallId,
                    call = Conversation.Message.ContentItem.ToolCall.Data("test_tool", JsonObject(emptyMap())),
                ),
            ),
            createdAt = now,
        )
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id("new-user-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(User.Id("user"), "User"),
            content = listOf(Conversation.Message.ContentItem.UserMessage("Keep this exact message")),
            createdAt = now,
        )
        val replayWindow = listOf(toolCall, userMessage).mapIndexed { position, message ->
            ToolSequenceMessageHeader(
                position = position,
                id = message.id,
                role = message.role,
                facts = ToolSequenceFacts.from(message),
            )
        }

        var currentConversation = conversation
        val conversationRepository = responding<ConversationRepository> { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "findById" -> currentConversation
                "updateCurrentThread" -> {
                    currentConversation = currentConversation.copy(
                        currentThread = Conversation.Thread.Id(invocation.arguments[1] as String),
                    )
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val threadRepository = responding<ThreadRepository> { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "save" -> invocation.arguments[0]
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val savedMessages = mutableListOf<Conversation.Message>()
        val messageRepository = responding<MessageRepository> { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "saveBatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    savedMessages += invocation.arguments[0] as List<Conversation.Message>
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        var copiedSuffix = emptyList<Conversation.Message.Id>()
        val threadMessageRepository = responding<ThreadMessageRepository> { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "invalidToolSequenceWindow" -> replayWindow
                "compactionBodies" -> {
                    @Suppress("UNCHECKED_CAST")
                    assertEquals(listOf(toolCall.id), invocation.arguments[1] as List<Conversation.Message.Id>)
                    listOf(toolCall)
                }
                "copyPrefixAndAddSuffix" -> {
                    assertEquals(sourceThreadId.value, invocation.arguments[0])
                    assertEquals(0, invocation.arguments[2])
                    @Suppress("UNCHECKED_CAST")
                    copiedSuffix = invocation.arguments[3] as List<Conversation.Message.Id>
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }

        val result = ToolCallSequenceFixerService(
            conversationRepository,
            threadRepository,
            messageRepository,
            threadMessageRepository,
        ).fixNonSequentialPairs(conversationId)

        assertTrue(result.fixed)
        assertEquals(1, result.addedResults)
        assertEquals(toolCall.id, copiedSuffix.first())
        assertEquals(userMessage.id, copiedSuffix.last())
        assertEquals(3, copiedSuffix.size)
        assertEquals(listOf(copiedSuffix[1]), savedMessages.map { it.id })
        assertTrue(currentConversation.currentThread != sourceThreadId)
    }

    private inline fun <reified T : Any> responding(crossinline answer: (InvocationOnMock) -> Any?): T =
        Mockito.mock(T::class.java) { answer(it) }
}
