package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.ThreadMessageRepository
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service

@Service
class ExposedThreadMessageRepository(
    private val json: Json
) : ThreadMessageRepository {

    override suspend fun add(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
        position: Int
    ): Unit = dbQuery {
        ThreadMessages.insert {
            it[ThreadMessages.threadId] = threadId.value
            it[ThreadMessages.messageId] = messageId.value
            it[ThreadMessages.position] = position
        }
    }

    override suspend fun addBatch(links: List<ThreadMessageLink>): Unit = dbQuery {
        ThreadMessages.batchInsert(links) { link ->
            this[ThreadMessages.threadId] = link.threadId.value
            this[ThreadMessages.messageId] = link.messageId.value
            this[ThreadMessages.position] = link.position
        }
    }

    override suspend fun getByThread(threadId: Conversation.Thread.Id): List<ThreadMessageLink> = dbQuery {
        ThreadMessages.selectAll()
            .where { ThreadMessages.threadId eq threadId.value }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .map { row ->
                ThreadMessageLink(
                    threadId = Conversation.Thread.Id(row[ThreadMessages.threadId]),
                    messageId = Conversation.Message.Id(row[ThreadMessages.messageId]),
                    position = row[ThreadMessages.position]
                )
            }
    }

    override suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int? = dbQuery {
        ThreadMessages.selectAll()
            .where { ThreadMessages.threadId eq threadId.value }
            .maxOfOrNull { it[ThreadMessages.position] }
    }

    override suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message> = dbQuery {
        (ThreadMessages innerJoin Messages)
            .selectAll()
            .where { ThreadMessages.threadId eq threadId.value }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .map { row ->
                json.decodeFromString<Conversation.Message>(row[Messages.messageJson])
            }
    }

    override suspend fun deleteByThread(threadId: Conversation.Thread.Id): Unit = dbQuery {
        ThreadMessages.deleteWhere { ThreadMessages.threadId eq threadId.value }
    }
}
