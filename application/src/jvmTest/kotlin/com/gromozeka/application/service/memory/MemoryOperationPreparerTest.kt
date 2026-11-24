package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.ConversationDomainService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.time.Instant

class MemoryOperationPreparerTest {
    @Test
    fun runtimeMessageIsNotAMemoryOperationTarget() = runBlocking<Unit> {
        val conversationId = Conversation.Id("conversation")
        val threadId = Conversation.Thread.Id("thread")
        val now = Instant.fromEpochMilliseconds(0)
        val conversation = Conversation(
            id = conversationId,
            projectId = Project.Id("project"),
            participants = setOf(Conversation.Participant.User(User.Id("user"))),
            currentThread = threadId,
            createdAt = now,
            updatedAt = now,
        )
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id("user-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Remember this.")),
            createdAt = now,
        )
        val runtimeMessage = Conversation.Message(
            id = Conversation.Message.Id("runtime-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.UserMessage("{\"kind\":\"visual.update\"}")),
            createdAt = now,
        )
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val messages = Mockito.mock(ThreadMessageRepository::class.java)
        Mockito.`when`(conversations.findById(conversationId)).thenReturn(conversation)
        Mockito.`when`(messages.getMessagesByThread(threadId)).thenReturn(listOf(userMessage, runtimeMessage))
        val preparer = MemoryOperationPreparer(conversations, messages)

        val prepared = preparer.prepareRememberMessage(
            conversationIdValue = conversationId.value,
            namespace = MemoryNamespace("project:test"),
        )

        assertEquals(
            userMessage.id,
            (prepared.request as MemoryOperationRequest.RememberMessage).targetMessageId,
        )
        assertFailsWith<IllegalArgumentException> {
            preparer.prepareRememberMessage(
                conversationIdValue = conversationId.value,
                namespace = MemoryNamespace("project:test"),
                targetMessageId = runtimeMessage.id.value,
            )
        }
    }
}
