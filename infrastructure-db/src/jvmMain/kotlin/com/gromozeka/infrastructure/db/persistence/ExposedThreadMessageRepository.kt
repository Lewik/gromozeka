package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageContextHeader
import com.gromozeka.domain.model.MessageContextMetadata
import com.gromozeka.domain.model.selectModelHistoryMessageIds
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.core.inList
import java.sql.Connection
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.ThreadMessageRepository
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.max
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

    override suspend fun contains(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Boolean = dbQuery {
        ThreadMessages.select(ThreadMessages.messageId)
            .where { (ThreadMessages.threadId eq threadId.value) and (ThreadMessages.messageId eq messageId.value) }
            .singleOrNull() != null
    }

    override suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int? = dbQuery {
        val maximum = ThreadMessages.position.max()
        ThreadMessages.select(maximum)
            .where { ThreadMessages.threadId eq threadId.value }
            .single()[maximum]
    }

    override suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message> = dbQuery {
        (ThreadMessages innerJoin Messages)
            .select(Messages.messageJson)
            .where { ThreadMessages.threadId eq threadId.value }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .map { row ->
                json.decodeFromString<Conversation.Message>(row[Messages.messageJson])
            }
            .let(::resolveCurrentMessageAuthors)
    }

    override suspend fun getContextMessagesByThread(
        threadId: Conversation.Thread.Id,
        nativeProvider: String?,
    ): List<Conversation.Message> = newSuspendedTransaction(
        Dispatchers.IO, transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true,
    ) {
        val storedHeaders = (ThreadMessages innerJoin Messages)
            .select(ThreadMessages.messageId, Messages.contextMetadataJson)
            .where { ThreadMessages.threadId eq threadId.value }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .map { row -> Conversation.Message.Id(row[ThreadMessages.messageId]) to row[Messages.contextMetadataJson] }
        val bodies = readContextBodies(threadId, storedHeaders.filter { it.second == null }.map { it.first })
            .associateByTo(mutableMapOf()) { it.id }
        val headers = storedHeaders.map { (id, metadata) -> MessageContextHeader(id,
            if (metadata != null) json.decodeFromString<MessageContextMetadata>(metadata)
            else MessageContextMetadata.from(bodies[id] ?: error("Missing body for Unicode context metadata: ${id.value}")),
        ) }
        val checkpointIds = headers.filter { it.metadata.hasCompaction }.map { it.id }
        readContextBodies(threadId, checkpointIds.filterNot(bodies::containsKey)).forEach { bodies[it.id] = it }
        val checkpoints = checkpointIds.map(bodies::getValue)
        val required = selectModelHistoryMessageIds(headers, checkpoints, nativeProvider)
        readContextBodies(threadId, required.filterNot(bodies::containsKey)).forEach { bodies[it.id] = it }
        val selected = headers.filter { it.id in required }.map { header ->
            val message = bodies[header.id] ?: error("Missing message in captured context: ${header.id.value}")
            check(message.id == header.id && MessageContextMetadata.from(message) == header.metadata) {
                "Message context metadata does not match its canonical body: ${header.id.value}"
            }
            message
        }
        resolveCurrentMessageAuthors(selected)
    }

    private fun readContextBodies(threadId: Conversation.Thread.Id, ids: List<Conversation.Message.Id>): List<Conversation.Message> =
        ids.chunked(512).flatMap { chunk ->
            (ThreadMessages innerJoin Messages).select(Messages.messageJson)
                .where { (ThreadMessages.threadId eq threadId.value) and (ThreadMessages.messageId inList chunk.map { it.value }) }
                .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
        }

    override suspend fun deleteByThread(threadId: Conversation.Thread.Id): Unit = dbQuery {
        ThreadMessages.deleteWhere { ThreadMessages.threadId eq threadId.value }
    }
}
