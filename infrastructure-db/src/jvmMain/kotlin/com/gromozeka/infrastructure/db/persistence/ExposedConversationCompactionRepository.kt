package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationCompactionRepository
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.infrastructure.db.persistence.tables.Threads
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        newLinks: List<ThreadMessageLink>,
    ): Boolean = dbQuery {
        val updated = Conversations.update({
            (Conversations.id eq compactionMessage.conversationId.value) and
                (Conversations.currentThreadId eq expectedThreadId.value)
        }) {
            it[currentThreadId] = newThread.id.value
            it[updatedAt] = newThread.updatedAt
        }
        if (updated != 1) return@dbQuery false

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
            it[lastTurnNumber] = newThread.lastTurnNumber
            it[createdAt] = newThread.createdAt
            it[updatedAt] = newThread.updatedAt
        }
        ThreadMessages.batchInsert(newLinks) { link ->
            this[ThreadMessages.threadId] = link.threadId.value
            this[ThreadMessages.messageId] = link.messageId.value
            this[ThreadMessages.position] = link.position
        }
        true
    }
}
