package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.Threads
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.repository.ThreadRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant

class ExposedThreadRepository : ThreadRepository {

    override suspend fun save(thread: Conversation.Thread): Conversation.Thread = dbQuery {
        Threads.insert {
            it[id] = thread.id.value
            it[conversationId] = thread.conversationId.value
            it[originalThreadId] = thread.originalThread?.value
            it[lastTurnNumber] = thread.lastTurnNumber
            it[createdAt] = thread.createdAt
            it[updatedAt] = thread.updatedAt
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
            it[Threads.updatedAt] = updatedAt
        }
    }

    override suspend fun incrementTurnNumber(id: Conversation.Thread.Id): Int = dbQuery {
        val currentTurn = Threads.selectAll()
            .where { Threads.id eq id.value }
            .single()[Threads.lastTurnNumber]

        val newTurn = currentTurn + 1

        Threads.update({ Threads.id eq id.value }) {
            it[lastTurnNumber] = newTurn
        }

        newTurn
    }

    private fun ResultRow.toThread() = Conversation.Thread(
        id = Conversation.Thread.Id(this[Threads.id]),
        conversationId = Conversation.Id(this[Threads.conversationId]),
        originalThread = this[Threads.originalThreadId]?.let { Conversation.Thread.Id(it) },
        lastTurnNumber = this[Threads.lastTurnNumber],
        createdAt = this[Threads.createdAt],
        updatedAt = this[Threads.updatedAt]
    )
}
