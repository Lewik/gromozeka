package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.Conversations
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.Project
import com.gromozeka.shared.repository.ConversationRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.time.Instant

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
        conversation
    }

    override suspend fun findById(id: Conversation.Id): Conversation? = dbQuery {
        Conversations.selectAll()
            .where { Conversations.id eq id.value }
            .singleOrNull()
            ?.toConversation()
    }

    override suspend fun findByProject(projectId: Project.Id): List<Conversation> = dbQuery {
        Conversations.selectAll()
            .where { Conversations.projectId eq projectId.value }
            .orderBy(Conversations.updatedAt, SortOrder.DESC)
            .map { it.toConversation() }
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
            it[updatedAt] = Clock.System.now()
        }
    }

    private fun ResultRow.toConversation() = Conversation(
        id = Conversation.Id(this[Conversations.id]),
        projectId = Project.Id(this[Conversations.projectId]),
        displayName = this[Conversations.displayName],
        currentThread = Conversation.Thread.Id(this[Conversations.currentThreadId]),
        createdAt = this[Conversations.createdAt],
        updatedAt = this[Conversations.updatedAt]
    )
}
