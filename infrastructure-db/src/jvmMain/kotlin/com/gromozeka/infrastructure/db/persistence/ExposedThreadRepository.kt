package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Threads
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ThreadRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class ExposedThreadRepository : ThreadRepository {

    override suspend fun save(thread: Conversation.Thread): Conversation.Thread = dbQuery {
        Threads.insert {
            it[id] = thread.id.value
            it[conversationId] = thread.conversationId.value
            it[originalThreadId] = thread.originalThread?.value
            it[lastTurnNumber] = thread.lastTurnNumber
            it[createdAt] = thread.createdAt.toKotlin()
            it[updatedAt] = thread.updatedAt.toKotlin()
        }
        thread
    }

    override suspend fun findById(id: Conversation.Thread.Id): Conversation.Thread? = dbQuery {
        Threads.selectAll()
            .where { Threads.id eq id.value }
            .singleOrNull()
            ?.toThread()
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Thread> = dbQuery {
        Threads.selectAll()
            .where { Threads.conversationId eq conversationId.value }
            .orderBy(Threads.createdAt, SortOrder.DESC)
            .map { it.toThread() }
    }

    override suspend fun delete(id: Conversation.Thread.Id): Unit = dbQuery {
        Threads.deleteWhere { Threads.id eq id.value }
    }

    override suspend fun updateTimestamp(id: Conversation.Thread.Id, updatedAt: Instant): Unit = dbQuery {
        Threads.update({ Threads.id eq id.value }) {
            it[Threads.updatedAt] = updatedAt.toKotlin()
        }
    }

    private fun ResultRow.toThread() = Conversation.Thread(
        id = Conversation.Thread.Id(this[Threads.id]),
        conversationId = Conversation.Id(this[Threads.conversationId]),
        originalThread = this[Threads.originalThreadId]?.let { Conversation.Thread.Id(it) },
        lastTurnNumber = this[Threads.lastTurnNumber],
        createdAt = this[Threads.createdAt].toKotlinx(),
        updatedAt = this[Threads.updatedAt].toKotlinx()
    )
}
