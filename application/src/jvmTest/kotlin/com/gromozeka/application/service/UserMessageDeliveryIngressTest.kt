package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class UserMessageDeliveryIngressTest {
    @Test fun `message submission keeps the callers explicit delivery`() = runBlocking {
        val now = Instant.fromEpochSeconds(1)
        val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val agent = AgentDefinition.Id("agent")
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent)),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val message = Conversation.Message(Conversation.Message.Id("message"), conversation.id, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")), createdAt = now)
        val calls = mutableListOf<List<MessageDelivery>>()
        val dispatcher = Mockito.mock(ConversationRuntimeDispatcher::class.java) { invocation ->
            calls += invocation.arguments.filterIsInstance<MessageDelivery>(); true
        }
        val conversations = Mockito.mock(ConversationDomainService::class.java) { conversation }
        val runtime = ConversationRuntimeApplicationService(dispatcher, mock(), mock(), mock(), conversations)
        runtime.submitMessage(user, conversation.id, message, null, MessageDelivery.STEER)
        runtime.submitMessage(user, conversation.id, message, agent, MessageDelivery.QUEUE)
        runtime.submitMessage(conversation.id, message, null, MessageDelivery.QUEUE)
        runtime.submitMessage(conversation.id, message, agent, MessageDelivery.STEER)
        runtime.submitMessage(
            user,
            conversation.id,
            message.copy(instructions = listOf(Conversation.Message.Instruction.Source.Agent("peer"))),
            null,
            MessageDelivery.QUEUE,
        )
        assertEquals(
            listOf(
                listOf(MessageDelivery.STEER),
                listOf(MessageDelivery.QUEUE),
                listOf(MessageDelivery.QUEUE),
                listOf(MessageDelivery.STEER),
                listOf(MessageDelivery.QUEUE),
            ),
            calls,
        )
    }
    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
}
