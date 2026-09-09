package com.gromozeka.server.telegram

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.server.GromozekaRemoteAuthorization
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class TelegramRuntimeGatewayTest {
    private val instant = Instant.fromEpochSeconds(1)
    private val user = User(User.Id("owner"), listOf(UserIdentity.LocalLogin("owner")), "Owner", User.Status.ACTIVE, createdAt = instant, updatedAt = instant)
    private val route = TelegramAgentRoute(AgentDefinition.Id("agent"))
    private val binding = TelegramConversationBinding(-123, conversationId = Conversation.Id("conversation"), initiatorTelegramUserId = 42, routes = listOf(route))
    private val connection = TelegramConnection(12345, "test_bot", user.id, "token", enabled = true, bindings = listOf(binding))
    private val conversation = Conversation(binding.conversationId, Project.Id("project"),
        setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(route.agentId)),
        currentThread = Conversation.Thread.Id("thread"), createdAt = instant, updatedAt = instant, externalChannel = connection.channel(binding))
    private val identities = Mockito.mock(IdentityRepository::class.java)
    private val connections = Mockito.mock(TelegramConnectionRepository::class.java)
    private val authorization = Mockito.mock(GromozekaRemoteAuthorization::class.java)
    private val apiFactory = Mockito.mock(TelegramConnectionApiFactory::class.java)
    private val ingress = Mockito.mock(ExternalConversationIngressService::class.java)
    private val coordinator = Mockito.mock(ConversationRuntimeCoordinator::class.java)
    private val access = TelegramBindingAuthorization(identities, connections, authorization, apiFactory)
    private val gateway = TelegramRuntimeGateway(access, ingress, coordinator,
        Mockito.mock(ConversationArtifactApplicationService::class.java), Mockito.mock(AgentDomainService::class.java))
    private val invocation = TelegramInvocation("invocation", connection.id, user.id, binding, route, Conversation.Message.Id("source"), 7)

    private suspend fun allow() {
        Mockito.`when`(apiFactory.serverEnabled).thenReturn(true)
        Mockito.`when`(connections.find(connection.id)).thenReturn(connection)
        Mockito.`when`(identities.findUserById(user.id)).thenReturn(user)
        Mockito.`when`(authorization.requireConversation(user, binding.conversationId, ProjectPermission.WRITE)).thenReturn(conversation)
    }

    @Test fun `submission uses owner authority but references the independently authored source message`() = runBlocking {
        allow(); gateway.submit(invocation)
        Mockito.verify(ingress).invokeAgent(connection.channel(binding), binding.conversationId, invocation.rootMessageId, route.agentId, user, invocation.id)
        Mockito.verifyNoMoreInteractions(ingress)
    }

    @Test fun `login AI and account state are independently enforced for each model step`() = runBlocking {
        allow()
        for (revoked in listOf(user.copy(loginAllowed = false), user.copy(aiAllowed = false), user.copy(status = User.Status.DISABLED))) {
            Mockito.`when`(identities.findUserById(user.id)).thenReturn(revoked)
            assertFailsWith<TelegramBindingRejected> { gateway.submit(invocation) }
        }
        Mockito.verifyNoInteractions(ingress)
    }

    @Test fun `deployment disabled means no connection access and no ingress`() = runBlocking {
        assertFailsWith<TelegramBindingRejected> { gateway.submit(invocation) }
        Mockito.verifyNoInteractions(connections, identities, authorization, ingress)
    }

    @Test fun `changed route or disconnected agent cannot run an old queued invocation`() = runBlocking {
        allow()
        Mockito.`when`(connections.find(connection.id)).thenReturn(connection.copy(bindings = listOf(binding.copy(routes = listOf(route.copy(writeAllowed = true))))))
        assertFailsWith<TelegramBindingRejected> { gateway.submit(invocation) }
        Mockito.`when`(connections.find(connection.id)).thenReturn(connection)
        Mockito.`when`(authorization.requireConversation(user, binding.conversationId, ProjectPermission.WRITE))
            .thenReturn(conversation.copy(participants = setOf(Conversation.Participant.User(user.id))))
        assertFailsWith<TelegramBindingRejected> { gateway.submit(invocation) }
        Mockito.verifyNoInteractions(ingress)
    }

    @Test fun `stop addresses exact turn even after AI access was revoked`() = runBlocking {
        Mockito.`when`(ingress.stop(binding.conversationId, ConversationRuntimeTurnId(invocation.id))).thenReturn(true)
        assertTrue(gateway.stop(invocation))
        Mockito.verify(ingress).stop(binding.conversationId, ConversationRuntimeTurnId(invocation.id))
        Mockito.verifyNoInteractions(identities, authorization, coordinator)
    }

    @Test fun `every runtime continuation rejects a detached binding or revoked authority`(): Unit = runBlocking {
        allow()
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val channels = Mockito.mock(TelegramChannelRepository::class.java)
        val guard = TelegramRuntimeTaskGuard(conversations, channels, access)
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("continuation"), conversation.id,
            parentTaskId = ConversationRuntimeTask.Id(invocation.id),
            turnId = ConversationRuntimeTurnId(invocation.id), actorUserId = user.id, externalChannel = connection.channel(binding),
            payload = ConversationRuntimeTask.Payload.LlmCall(invocation.rootMessageId, route.agentId, 2),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "continuation",
            requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server), createdAt = instant)
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(channels.find(connection.id)).thenReturn(TelegramBotState(invocations = listOf(invocation)))
        guard.validate(task)
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation.copy(externalChannel = null))
        assertFailsWith<TelegramBindingRejected> { guard.validate(task) }
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(connections.find(connection.id)).thenReturn(connection.copy(enabled = false))
        assertFailsWith<TelegramBindingRejected> { guard.validate(task) }
        Mockito.`when`(connections.find(connection.id)).thenReturn(connection)
        Mockito.`when`(identities.findUserById(user.id)).thenReturn(user.copy(aiAllowed = false))
        assertFailsWith<TelegramBindingRejected> { guard.validate(task) }
        guard.validate(task.copy(id = ConversationRuntimeTask.Id("incident"), parentTaskId = null,
            payload = ConversationRuntimeTask.Payload.ExecutionIncident(task.id)))
    }
}
