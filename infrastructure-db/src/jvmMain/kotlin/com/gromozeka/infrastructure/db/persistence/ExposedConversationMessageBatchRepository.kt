package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationMessageBatchRepository
import com.gromozeka.domain.repository.samePersistentBodyAs
import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.infrastructure.db.persistence.tables.Threads
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service
import kotlin.time.Instant

@Service
class ExposedConversationMessageBatchRepository(
    private val json: Json,
    private val messageStore: ExposedMessageRepository,
) : ConversationMessageBatchRepository {
    override suspend fun append(
        conversationId: Conversation.Id,
        expectedThreadId: Conversation.Thread.Id,
        messages: List<Conversation.Message>,
        at: Instant,
    ): List<Conversation.Message> = dbQuery {
        require(messages.all { it.conversationId == conversationId }) { "Message conversationId mismatch" }
        require(messages.map { it.id }.distinct().size == messages.size) { "Duplicate message ID in append batch" }
        if (messages.isEmpty()) return@dbQuery emptyList()
        val conversation = Conversations.selectAll().where { Conversations.id eq conversationId.value }.forUpdate().singleOrNull()
            ?: error("Conversation not found: ${conversationId.value}")
        check(conversation[Conversations.currentThreadId] == expectedThreadId.value) { "Conversation thread changed before batch append" }
        val ids = messages.map { it.id.value }
        val stored = ids.chunked(256).flatMap { chunk ->
            Messages.select(Messages.messageJson).where { Messages.id inList chunk }
                .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
        }.associateBy { it.id }
        messages.forEach { message ->
            require(stored[message.id]?.samePersistentBodyAs(message) != false) { "Message id collision with different content: ${message.id.value}" }
        }
        val linked = ids.chunked(256).flatMap { chunk ->
            ThreadMessages.select(ThreadMessages.messageId).where {
                (ThreadMessages.threadId eq expectedThreadId.value) and (ThreadMessages.messageId inList chunk)
            }.map { Conversation.Message.Id(it[ThreadMessages.messageId]) }
        }.toSet()
        val appended = messages.filterNot { it.id in linked }
        if (appended.isEmpty()) return@dbQuery emptyList()
        messageStore.insertBatch(messages.filterNot { it.id in stored })
        val maximum = ThreadMessages.position.max()
        val start = (ThreadMessages.select(maximum).where { ThreadMessages.threadId eq expectedThreadId.value }.single()[maximum] ?: -1) + 1
        ThreadMessages.batchInsert(appended.withIndex(), shouldReturnGeneratedValues = false) { indexed ->
            this[ThreadMessages.threadId] = expectedThreadId.value
            this[ThreadMessages.messageId] = indexed.value.id.value
            this[ThreadMessages.position] = start + indexed.index
        }
        Threads.update({ Threads.id eq expectedThreadId.value }) { it[updatedAt] = at }
        Conversations.update({ Conversations.id eq conversationId.value }) { it[updatedAt] = at }
        appended.map { stored[it.id] ?: it }
    }
}
