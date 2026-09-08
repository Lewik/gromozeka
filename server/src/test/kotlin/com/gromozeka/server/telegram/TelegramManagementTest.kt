package com.gromozeka.server.telegram

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.server.GromozekaRemoteAuthorization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock

class TelegramManagementTest {
    @Test fun `enabled configuration saves replace activation even when only a group is reenabled`() = runBlocking {
        val now = Clock.System.now()
        val owner = User(User.Id("owner"), listOf(UserIdentity.LocalLogin("owner")), "Owner", User.Status.ACTIVE,
            User.Role.OWNER, now, now)
        val route = TelegramAgentRoute(AgentDefinition.Id("agent"))
        val binding = TelegramConversationBinding(-123, conversationId = Conversation.Id("group"), initiatorTelegramUserId = 42,
            routes = listOf(route), enabled = false)
        val old = TelegramConnection(12345, "test_bot", owner.id, "token", true, listOf(binding), revision = 8,
            acceptTriggersAfterEpochSeconds = 1, activationRevision = 2)
        val changed = old.copy(bindings = listOf(binding.copy(enabled = true)))
        val repository = object : TelegramConnectionRepository {
            var value = old
            override suspend fun find(id: String) = value.takeIf { it.id == id }
            override suspend fun list() = listOf(value)
            override suspend fun save(connection: TelegramConnection, expectedRevision: Long): TelegramConnection {
                assertEquals(value.revision, expectedRevision)
                value = connection.copy(revision = expectedRevision + 1)
                return value
            }
        }
        val identities = Mockito.mock(IdentityRepository::class.java)
        Mockito.`when`(identities.findUserById(owner.id)).thenReturn(owner)
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val conversation = Conversation(binding.conversationId, Project.Id("project"),
            setOf(Conversation.Participant.User(owner.id), Conversation.Participant.Agent(route.agentId)),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now, externalChannel = old.channel(binding))
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        val authorization = Mockito.mock(GromozekaRemoteAuthorization::class.java)
        Mockito.`when`(authorization.requireConversation(owner, conversation.id, ProjectPermission.WRITE)).thenReturn(conversation)
        val factory = Mockito.mock(TelegramConnectionApiFactory::class.java)
        val api = Mockito.mock(TelegramHttpApi::class.java)
        Mockito.`when`(factory.create(changed)).thenReturn(api)
        Mockito.`when`(api.call("getMe")).thenReturn(buildJsonObject {
            put("id", old.botId); put("username", old.botUsername); put("can_read_all_group_messages", true)
        })
        for ((method, key) in listOf("getMyName" to "name", "getMyDescription" to "description", "getMyShortDescription" to "short_description")) {
            Mockito.`when`(api.call(method)).thenReturn(buildJsonObject { put(key, "Test") })
        }
        val service = TelegramManagementApplicationService(repository, factory, authorization, identities,
            Mockito.mock(ConversationArtifactApplicationService::class.java), Mockito.mock(DeclarativeStateChangePublisher::class.java),
            Mockito.mock(ProjectAccessService::class.java), conversations, Mockito.mock(AgentDomainService::class.java))
        val saved = service.save(owner, changed, old.revision)
        assertEquals(9L, saved.activationRevision)
        assertTrue(saved.acceptTriggersAfterEpochSeconds >= now.epochSeconds)
        assertTrue(saved.bindings.single().enabled)
    }
}
