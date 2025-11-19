package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class ExposedConversationRepository : ConversationRepository {

    override suspend fun create(conversation: Conversation): Conversation = dbQuery {
        Conversations.insert {
            it[id] = conversation.id.value
            it[projectId] = conversation.projectId.value
            it[displayName] = conversation.displayName
            it[aiProvider] = conversation.aiProvider
            it[modelName] = conversation.modelName
            it[currentThreadId] = conversation.currentThread.value
            it[createdAt] = conversation.createdAt.toKotlin()
            it[updatedAt] = conversation.updatedAt.toKotlin()
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
            it[updatedAt] = Clock.System.now().toKotlin()
        }
    }

    override suspend fun updateDisplayName(id: Conversation.Id, displayName: String): Unit = dbQuery {
        Conversations.update({ Conversations.id eq id.value }) {
            it[Conversations.displayName] = displayName
            it[updatedAt] = Clock.System.now().toKotlin()
        }
    }

    private fun ResultRow.toConversation() = Conversation(
        id = Conversation.Id(this[Conversations.id]),
        projectId = Project.Id(this[Conversations.projectId]),
        displayName = this[Conversations.displayName],
        aiProvider = this[Conversations.aiProvider],
        modelName = this[Conversations.modelName],
        currentThread = Conversation.Thread.Id(this[Conversations.currentThreadId]),
        createdAt = this[Conversations.createdAt].toKotlinx(),
        updatedAt = this[Conversations.updatedAt].toKotlinx()
    )
}
