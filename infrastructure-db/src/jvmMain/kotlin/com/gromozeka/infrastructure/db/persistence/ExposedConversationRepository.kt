package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.infrastructure.db.persistence.tables.ConversationAgentParticipants
import com.gromozeka.infrastructure.db.persistence.tables.ConversationUserParticipants
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import org.springframework.stereotype.Service

@Service
class ExposedConversationRepository : ConversationRepository {

    override suspend fun create(conversation: Conversation): Conversation = dbQuery {
        Conversations.insert {
            it[id] = conversation.id.value
            it[projectId] = conversation.projectId.value
            it[displayName] = conversation.displayName
            it[currentThreadId] = conversation.currentThread.value
            it[createdAt] = conversation.createdAt
            it[updatedAt] = conversation.updatedAt
        }
        saveParticipants(conversation.id, conversation.participants)
        conversation
    }

    override suspend fun findById(id: Conversation.Id): Conversation? = dbQuery {
        val row = Conversations.selectAll()
            .where { Conversations.id eq id.value }
            .singleOrNull()
            ?: return@dbQuery null
        row.toConversation(loadParticipants(setOf(id))[id].orEmpty())
    }

    override suspend fun findByProject(projectId: Project.Id): List<Conversation> = dbQuery {
        val rows = Conversations.selectAll()
            .where { Conversations.projectId eq projectId.value }
            .orderBy(Conversations.updatedAt, SortOrder.DESC)
            .toList()
        val participantsByConversation = loadParticipants(rows.mapTo(mutableSetOf()) { Conversation.Id(it[Conversations.id]) })
        rows.map { row ->
            val id = Conversation.Id(row[Conversations.id])
            row.toConversation(participantsByConversation[id].orEmpty())
        }
    }

    override suspend fun delete(id: Conversation.Id): Unit = dbQuery {
        Conversations.deleteWhere { Conversations.id eq id.value }
    }

    override suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id): Unit = dbQuery {
        Conversations.update({ Conversations.id eq id.value }) {
            it[currentThreadId] = threadId.value
            it[updatedAt] = Clock.System.now()
        }
    }

    override suspend fun updateDisplayName(id: Conversation.Id, displayName: String): Unit = dbQuery {
        Conversations.update({ Conversations.id eq id.value }) {
            it[Conversations.displayName] = displayName
        }
    }

    override suspend fun updateParticipants(
        id: Conversation.Id,
        participants: Set<Conversation.Participant>,
    ): Unit = dbQuery {
        val existing = loadParticipants(setOf(id))[id].orEmpty()
        val removed = existing - participants
        val added = participants - existing

        val removedUserIds = removed.filterIsInstance<Conversation.Participant.User>()
            .map(Conversation.Participant.User::userId)
        if (removedUserIds.isNotEmpty()) {
            ConversationUserParticipants.deleteWhere {
                (conversationId eq id.value) and (userId inList removedUserIds.map { it.value })
            }
        }
        val removedAgentIds = removed.filterIsInstance<Conversation.Participant.Agent>()
            .map(Conversation.Participant.Agent::agentDefinitionId)
        if (removedAgentIds.isNotEmpty()) {
            ConversationAgentParticipants.deleteWhere {
                (conversationId eq id.value) and (agentDefinitionId inList removedAgentIds.map { it.value })
            }
        }
        saveParticipants(id, added)
        Conversations.update({ Conversations.id eq id.value }) { it[updatedAt] = Clock.System.now() }
    }

    override suspend fun touch(id: Conversation.Id): Unit = dbQuery {
        Conversations.update({ Conversations.id eq id.value }) {
            it[updatedAt] = Clock.System.now()
        }
    }

    private fun saveParticipants(
        conversationId: Conversation.Id,
        participants: Set<Conversation.Participant>,
    ) {
        participants.filterIsInstance<Conversation.Participant.User>().forEach { participant ->
            ConversationUserParticipants.insert {
                it[ConversationUserParticipants.conversationId] = conversationId.value
                it[userId] = participant.userId.value
            }
        }
        participants.filterIsInstance<Conversation.Participant.Agent>().forEach { participant ->
            ConversationAgentParticipants.insert {
                it[ConversationAgentParticipants.conversationId] = conversationId.value
                it[agentDefinitionId] = participant.agentDefinitionId.value
            }
        }
    }

    private fun loadParticipants(
        conversationIds: Set<Conversation.Id>,
    ): Map<Conversation.Id, Set<Conversation.Participant>> {
        if (conversationIds.isEmpty()) return emptyMap()
        val ids = conversationIds.map(Conversation.Id::value)
        val participants = mutableMapOf<Conversation.Id, MutableSet<Conversation.Participant>>()
        ConversationUserParticipants.selectAll()
            .where { ConversationUserParticipants.conversationId inList ids }
            .forEach { row ->
                participants.getOrPut(Conversation.Id(row[ConversationUserParticipants.conversationId]), ::mutableSetOf) +=
                    Conversation.Participant.User(com.gromozeka.domain.model.User.Id(row[ConversationUserParticipants.userId]))
            }
        ConversationAgentParticipants.selectAll()
            .where { ConversationAgentParticipants.conversationId inList ids }
            .forEach { row ->
                participants.getOrPut(Conversation.Id(row[ConversationAgentParticipants.conversationId]), ::mutableSetOf) +=
                    Conversation.Participant.Agent(
                        com.gromozeka.domain.model.AgentDefinition.Id(row[ConversationAgentParticipants.agentDefinitionId])
                    )
            }
        return participants
    }

    private fun ResultRow.toConversation(participants: Set<Conversation.Participant>) = Conversation(
        id = Conversation.Id(this[Conversations.id]),
        projectId = Project.Id(this[Conversations.projectId]),
        participants = participants,
        displayName = this[Conversations.displayName],
        currentThread = Conversation.Thread.Id(this[Conversations.currentThreadId]),
        createdAt = this[Conversations.createdAt],
        updatedAt = this[Conversations.updatedAt]
    )
}
