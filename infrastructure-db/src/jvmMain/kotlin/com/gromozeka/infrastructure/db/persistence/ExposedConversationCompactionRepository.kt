package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationCompactionRepository
import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.infrastructure.db.persistence.tables.Threads
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedConversationCompactionRepository(
    private val json: Json,
) : ConversationCompactionRepository {
    override suspend fun commitIfCurrent(
        expectedThreadId: Conversation.Thread.Id,
        compactionMessage: Conversation.Message,
        newThread: Conversation.Thread,
        insertionAnchorId: Conversation.Message.Id,
    ): Boolean = dbQuery {
        require(newThread.conversationId == compactionMessage.conversationId && newThread.originalThread == expectedThreadId) {
            "Compaction thread must derive from the captured conversation history"
        }
        val updated = Conversations.update({
            (Conversations.id eq compactionMessage.conversationId.value) and
                (Conversations.currentThreadId eq expectedThreadId.value)
        }) {
            it[currentThreadId] = newThread.id.value
            it[updatedAt] = newThread.updatedAt
        }
        if (updated != 1) return@dbQuery false

        // Clone the current links under the conversation's CAS lock, not the prepare-time
        // snapshot. Appends accepted before this commit must survive in the new version.
        val currentIds = ThreadMessages.selectAll()
            .where { ThreadMessages.threadId eq expectedThreadId.value }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .map { it[ThreadMessages.messageId] }
        val anchor = currentIds.indexOf(insertionAnchorId.value)
        check(anchor >= 0) { "Compaction insertion anchor is missing from the captured thread" }
        val newMessageIds = currentIds.take(anchor + 1) + compactionMessage.id.value + currentIds.drop(anchor + 1)
        val lastTurnNumber = Threads.selectAll().where { Threads.id eq expectedThreadId.value }
            .single()[Threads.lastTurnNumber]

        Messages.insert {
            it[id] = compactionMessage.id.value
            it[conversationId] = compactionMessage.conversationId.value
            it[originalIdsJson] = json.encodeToString(compactionMessage.originalIds.map { id -> id.value })
            it[replyToId] = compactionMessage.replyTo?.value
            it[role] = compactionMessage.role.name
            it[createdAt] = compactionMessage.createdAt
            it[searchText] = compactionMessage.searchText()
            it[messageJson] = json.encodeToString(compactionMessage)
        }
        Threads.insert {
            it[id] = newThread.id.value
            it[conversationId] = newThread.conversationId.value
            it[originalThreadId] = newThread.originalThread?.value
            it[Threads.lastTurnNumber] = lastTurnNumber
            it[createdAt] = newThread.createdAt
            it[updatedAt] = newThread.updatedAt
        }
        ThreadMessages.batchInsert(newMessageIds.withIndex().toList()) { (position, messageId) ->
            this[ThreadMessages.threadId] = newThread.id.value
            this[ThreadMessages.messageId] = messageId
            this[ThreadMessages.position] = position
        }
        true
    }
}
