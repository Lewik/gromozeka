package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.Messages
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.repository.MessageRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant

class ExposedMessageRepository(
    private val json: Json
) : MessageRepository {

    override suspend fun save(message: Conversation.Message): Conversation.Message = dbQuery {
        Messages.insert {
            it[id] = message.id.value
            it[conversationId] = message.conversationId.value
            it[originalIdsJson] = json.encodeToString(message.originalIds.map { id -> id.value })
            it[replyToId] = message.replyTo?.value
            it[squashOperationId] = message.squashOperationId?.value
            it[role] = message.role.name
            it[createdAt] = message.createdAt
            it[messageJson] = json.encodeToString(message)
        }
        message
    }

    override suspend fun findById(id: Conversation.Message.Id): Conversation.Message? = dbQuery {
        Messages.selectAll()
            .where { Messages.id eq id.value }
            .singleOrNull()
            ?.let { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }

    override suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message> = dbQuery {
        if (ids.isEmpty()) return@dbQuery emptyList()

        Messages.selectAll()
            .where { Messages.id inList ids.map { it.value } }
            .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
            .sortedBy { message -> ids.indexOf(message.id) }
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message> = dbQuery {
        Messages.selectAll()
            .where { Messages.conversationId eq conversationId.value }
            .orderBy(Messages.createdAt, SortOrder.ASC)
            .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }

    override suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message> = dbQuery {
        Messages.selectAll()
            .where { Messages.originalIdsJson like "%\"${originalId.value}\"%"}
            .orderBy(Messages.createdAt, SortOrder.ASC)
            .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }
}
