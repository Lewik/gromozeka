package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class UserMessageDeliveryIngressTest {
    @Test fun `only authenticated human submissions resolve the callers preference`() = runBlocking {
        val now = Instant.fromEpochSeconds(1)
        val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val agent = AgentDefinition.Id("agent")
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent)),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val message = Conversation.Message(Conversation.Message.Id("message"), conversation.id, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")), createdAt = now)
        val calls = mutableListOf<List<UserMessageDeliveryMode>>()
        val dispatcher = Mockito.mock(ConversationRuntimeDispatcher::class.java) { invocation ->
            calls += invocation.arguments.filterIsInstance<UserMessageDeliveryMode>(); true
        }
        val lookups = mutableListOf<User.Id>()
        var mode = UserMessageDeliveryMode.STEER
        val preference = object : UserMessageDeliveryPreferenceService {
            override suspend fun get(userId: User.Id): UserMessageDeliveryMode { lookups += userId; return mode }
            override suspend fun set(userId: User.Id, mode: UserMessageDeliveryMode): UserMessageDeliveryMode = error("No save during admission")
        }
        val conversations = Mockito.mock(ConversationDomainService::class.java) { conversation }
        val runtime = ConversationRuntimeApplicationService(dispatcher, mock(), mock(), mock(), conversations, preference)
        runtime.postMessage(user, conversation.id, message)
        mode = UserMessageDeliveryMode.AFTER_CURRENT_TURN
        runtime.invokeAgent(user, conversation.id, message, agent)
        runtime.postMessage(conversation.id, message)
        runtime.invokeAgent(conversation.id, message, agent)
        runtime.postMessage(user, conversation.id, message.copy(instructions = listOf(Conversation.Message.Instruction.Source.Agent("peer"))))
        assertEquals(listOf(user.id, user.id), lookups)
        assertEquals(listOf(listOf(UserMessageDeliveryMode.STEER), listOf(UserMessageDeliveryMode.AFTER_CURRENT_TURN), emptyList(), emptyList(), emptyList()), calls)
    }
    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
}
