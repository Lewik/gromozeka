package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun `runtime submission keeps caller controlled target and delivery`() = runBlocking {
        val now = Instant.fromEpochSeconds(1)
        val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val agent = AgentDefinition.Id("agent")
        val conversation = Conversation(
            Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent)),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now,
            autoRespondAgentIds = setOf(agent),
        )
        val message = Conversation.Message(
            Conversation.Message.Id("runtime-message"), conversation.id,
            role = Conversation.Message.Role.RUNTIME,
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(
                JsonObject(mapOf("kind" to JsonPrimitive("panel_state"))),
            )),
            createdAt = now,
        )
        val calls = mutableListOf<List<Any?>>()
        val dispatcher = Mockito.mock(ConversationRuntimeDispatcher::class.java) { invocation ->
            if (invocation.method.name.startsWith("submitMessage")) {
                calls += invocation.arguments.toList()
                true
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val conversations = Mockito.mock(ConversationDomainService::class.java) { conversation }
        val runtime = ConversationRuntimeApplicationService(dispatcher, mock(), mock(), mock(), conversations)

        runtime.submitMessage(user, conversation.id, message, null, MessageDelivery.QUEUE)
        runtime.submitMessage(user, conversation.id, message.copy(id = Conversation.Message.Id("runtime-steer")), agent, MessageDelivery.STEER)

        assertEquals(listOf(null, agent.value), calls.map { it[2] })
        assertEquals(listOf(MessageDelivery.QUEUE, MessageDelivery.STEER), calls.map { it[3] })
        assertEquals(listOf(user.id.value, user.id.value), calls.map { it[4] })
        assertTrue(calls.all { (it[1] as Conversation.Message).author == Conversation.Message.Author.Runtime() })
        assertTrue(calls.all { it[5] == emptySet<AgentDefinition.Id>() })
    }

    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
}
