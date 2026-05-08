package com.gromozeka.infrastructure.db.persistence.mongo

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.SquashOperationRepository
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class MongoThreadRepository(
    database: MongoDatabase,
) : ThreadRepository {
    private val threads: MongoCollection<Conversation.Thread> = database.getCollection("conversation_threads")
    private val threadMessages: MongoCollection<ThreadMessageLink> = database.getCollection("conversation_thread_messages")
    private val indexes = MongoIndexInitializer {
        threads.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        threads.createIndex(Indexes.ascending("conversationId"))
        threads.createIndex(Indexes.descending("createdAt"))
        threadMessages.createIndex(Indexes.ascending("threadId"))
    }

    override suspend fun save(thread: Conversation.Thread): Conversation.Thread {
        indexes.ensure()
        threads.insertNewByDomainId(thread.id.value, thread)
        return thread
    }

    override suspend fun findById(id: Conversation.Thread.Id): Conversation.Thread? {
        indexes.ensure()
        return threads.findByDomainId(id.value)
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Thread> {
        indexes.ensure()
        return threads.find(Filters.eq("conversationId", conversationId.value))
            .sort(Sorts.descending("createdAt"))
            .toList()
    }

    override suspend fun delete(id: Conversation.Thread.Id) {
        indexes.ensure()
        threads.deleteMany(Filters.eq("id", id.value))
        threadMessages.deleteMany(Filters.eq("threadId", id.value))
    }

    override suspend fun updateTimestamp(id: Conversation.Thread.Id, updatedAt: Instant) {
        indexes.ensure()
        val current = threads.findByDomainId(id.value) ?: error("Thread not found: ${id.value}")
        threads.upsertByDomainId(id.value, current.copy(updatedAt = updatedAt))
    }
}

@Service
@Primary
class MongoMessageRepository(
    database: MongoDatabase,
) : MessageRepository {
    private val messages: MongoCollection<Conversation.Message> = database.getCollection("conversation_messages")
    private val indexes = MongoIndexInitializer {
        messages.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        messages.createIndex(Indexes.ascending("conversationId"))
        messages.createIndex(Indexes.ascending("originalIds"))
        messages.createIndex(Indexes.ascending("createdAt"))
    }

    override suspend fun save(message: Conversation.Message): Conversation.Message {
        indexes.ensure()
        messages.insertNewByDomainId(message.id.value, message)
        return message
    }

    override suspend fun findById(id: Conversation.Message.Id): Conversation.Message? {
        indexes.ensure()
        return messages.findByDomainId(id.value)
    }

    override suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message> {
        indexes.ensure()
        if (ids.isEmpty()) return emptyList()

        val idsByValue = ids.map { it.value }
        val messagesById = messages.find(Filters.`in`("id", idsByValue))
            .toList()
            .associateBy { it.id }
        return ids.mapNotNull(messagesById::get)
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message> {
        indexes.ensure()
        return messages.find(Filters.eq("conversationId", conversationId.value))
            .sort(Sorts.ascending("createdAt"))
            .toList()
    }

    override suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message> {
        indexes.ensure()
        return messages.find(Filters.eq("originalIds", originalId.value))
            .sort(Sorts.ascending("createdAt"))
            .toList()
    }
}

@Service
@Primary
class MongoThreadMessageRepository(
    database: MongoDatabase,
) : ThreadMessageRepository {
    private val links: MongoCollection<ThreadMessageLink> = database.getCollection("conversation_thread_messages")
    private val messages: MongoCollection<Conversation.Message> = database.getCollection("conversation_messages")
    private val indexes = MongoIndexInitializer {
        links.createIndex(
            Indexes.ascending("threadId", "position"),
            IndexOptions().unique(true),
        )
        links.createIndex(Indexes.ascending("messageId"))
        messages.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
    }

    override suspend fun add(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
        position: Int,
    ) {
        indexes.ensure()
        links.insertOne(ThreadMessageLink(threadId, messageId, position))
    }

    override suspend fun addBatch(links: List<ThreadMessageLink>) {
        indexes.ensure()
        if (links.isNotEmpty()) {
            this.links.insertMany(links)
        }
    }

    override suspend fun getByThread(threadId: Conversation.Thread.Id): List<ThreadMessageLink> {
        indexes.ensure()
        return links.find(Filters.eq("threadId", threadId.value))
            .sort(Sorts.ascending("position"))
            .toList()
    }

    override suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int? {
        indexes.ensure()
        return links.find(Filters.eq("threadId", threadId.value))
            .sort(Sorts.descending("position"))
            .limit(1)
            .firstOrNull()
            ?.position
    }

    override suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message> {
        indexes.ensure()
        val messageIds = getByThread(threadId).map { it.messageId }
        if (messageIds.isEmpty()) return emptyList()

        val messagesById = messages.find(Filters.`in`("id", messageIds.map { it.value }))
            .toList()
            .associateBy { it.id }
        return messageIds.mapNotNull(messagesById::get)
    }

    override suspend fun deleteByThread(threadId: Conversation.Thread.Id) {
        indexes.ensure()
        links.deleteMany(Filters.eq("threadId", threadId.value))
    }
}

@Service
@Primary
class MongoSquashOperationRepository(
    database: MongoDatabase,
) : SquashOperationRepository {
    private val operations: MongoCollection<Conversation.SquashOperation> =
        database.getCollection("conversation_squash_operations")
    private val indexes = MongoIndexInitializer {
        operations.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        operations.createIndex(Indexes.ascending("conversationId"))
        operations.createIndex(Indexes.ascending("resultMessageId"))
        operations.createIndex(Indexes.ascending("createdAt"))
    }

    override suspend fun save(operation: Conversation.SquashOperation): Conversation.SquashOperation {
        indexes.ensure()
        operations.insertNewByDomainId(operation.id.value, operation)
        return operation
    }

    override suspend fun findById(id: Conversation.SquashOperation.Id): Conversation.SquashOperation? {
        indexes.ensure()
        return operations.findByDomainId(id.value)
    }

    override suspend fun findByResultMessage(messageId: Conversation.Message.Id): Conversation.SquashOperation? {
        indexes.ensure()
        return operations.find(Filters.eq("resultMessageId", messageId.value)).firstOrNull()
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.SquashOperation> {
        indexes.ensure()
        return operations.find(Filters.eq("conversationId", conversationId.value))
            .sort(Sorts.ascending("createdAt"))
            .toList()
    }
}
