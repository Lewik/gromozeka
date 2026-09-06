package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class ConversationTest {
    private val user = Conversation.Participant.User(User.Id("user"))
    private val agent = Conversation.Participant.Agent(AgentDefinition.Id("agent"))
    private val otherUser = Conversation.Participant.User(User.Id("other-user"))
    private val otherAgent = Conversation.Participant.Agent(AgentDefinition.Id("other-agent"))

    @Test
    fun `one user and one agent enables an automatic responder by default`() {
        assertEquals(setOf(agent.agentDefinitionId), Conversation.defaultAutoRespondAgentIds(setOf(user, agent)))
        assertEquals(emptySet(), Conversation.defaultAutoRespondAgentIds(setOf(user, agent, otherUser)))
        assertEquals(emptySet(), Conversation.defaultAutoRespondAgentIds(setOf(user, agent, otherAgent)))
    }

    @Test
    fun `manual disable survives unchanged participants and conversation growth`() {
        val disabled = conversation(setOf(user, agent))
        assertEquals(emptySet(), disabled.withParticipants(disabled.participants).autoRespondAgentIds)
        assertEquals(emptySet(), disabled.withParticipants(setOf(user, agent, otherUser)).autoRespondAgentIds)
        val enabled = disabled.copy(autoRespondAgentIds = setOf(agent.agentDefinitionId))
        assertEquals(enabled.autoRespondAgentIds, enabled.withParticipants(setOf(user, agent, otherAgent)).autoRespondAgentIds)
    }

    @Test
    fun `participant changes enable the sole agent and prune disconnected responders`() {
        val group = conversation(setOf(user, agent, otherUser, otherAgent)).copy(
            autoRespondAgentIds = setOf(agent.agentDefinitionId, otherAgent.agentDefinitionId),
        )
        assertEquals(setOf(otherAgent.agentDefinitionId), group.withParticipants(setOf(user, otherUser, otherAgent)).autoRespondAgentIds)
        assertEquals(setOf(agent.agentDefinitionId), group.withParticipants(setOf(user, agent)).autoRespondAgentIds)
        assertEquals(emptySet(), group.withParticipants(setOf(user, otherUser)).autoRespondAgentIds)
        assertEquals(setOf(otherAgent.agentDefinitionId), conversation(setOf(user, agent)).withParticipants(setOf(user, otherAgent)).autoRespondAgentIds)
        assertFailsWith<IllegalArgumentException> { group.withParticipants(setOf(agent, otherAgent)) }
    }

    @Test
    fun `disconnected agent cannot be an automatic responder`() {
        assertFailsWith<IllegalArgumentException> {
            conversation(setOf(user, agent)).copy(autoRespondAgentIds = setOf(otherAgent.agentDefinitionId))
        }
    }

    @Test
    fun `automatic responses never react to agent system or tool messages`() {
        val conversation = conversation(setOf(user, agent)).copy(autoRespondAgentIds = setOf(agent.agentDefinitionId))
        val message = Conversation.Message(
            id = Conversation.Message.Id("message"), conversationId = conversation.id,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")), createdAt = Clock.System.now(),
        )
        assertEquals(conversation.autoRespondAgentIds, conversation.autoRespondersFor(message))
        assertEquals(emptySet(), conversation.autoRespondersFor(message.copy(role = Conversation.Message.Role.ASSISTANT)))
        assertEquals(emptySet(), conversation.autoRespondersFor(message.copy(role = Conversation.Message.Role.SYSTEM)))
        assertEquals(emptySet(), conversation.autoRespondersFor(message.copy(
            instructions = listOf(Conversation.Message.Instruction.Source.Agent("source")),
        )))
        assertEquals(emptySet(), conversation.autoRespondersFor(message.copy(
            author = Conversation.Message.Author.Agent(agent.agentDefinitionId, "Agent"),
        )))
    }

    private fun conversation(participants: Set<Conversation.Participant>) = Conversation(
        id = Conversation.Id("conversation"), projectId = Project.Id("project"), participants = participants,
        currentThread = Conversation.Thread.Id("thread"), createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    @Test
    fun `conversation accepts a user without agents`() {
        val now = Clock.System.now()
        val user = Conversation.Participant.User(User.Id("user"))

        val conversation = Conversation(
            id = Conversation.Id("conversation"),
            projectId = Project.Id("project"),
            participants = setOf(user),
            currentThread = Conversation.Thread.Id("thread"),
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(setOf(user), conversation.participants)
    }

    @Test
    fun `conversation requires a user participant`() {
        val now = Clock.System.now()

        assertFailsWith<IllegalArgumentException> {
            Conversation(
                id = Conversation.Id("conversation"),
                projectId = Project.Id("project"),
                participants = setOf(
                    Conversation.Participant.Agent(AgentDefinition.Id("agent")),
                ),
                currentThread = Conversation.Thread.Id("thread"),
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
