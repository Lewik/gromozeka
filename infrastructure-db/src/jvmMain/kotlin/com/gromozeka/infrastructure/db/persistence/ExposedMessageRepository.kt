package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Artifacts
import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.domain.model.safeToolOutputText
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.MessageRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class ExposedMessageRepository(
    private val json: Json
) : MessageRepository {

    override suspend fun save(message: Conversation.Message): Conversation.Message = dbQuery {
        val artifactIds = message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
            .flatMap { it.result }
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>()
            .map { it.artifact.id.value }
            .distinct()
        val artifactText = if (artifactIds.isEmpty()) "" else Artifacts.select(Artifacts.searchText)
            .where { Artifacts.id inList artifactIds }
            .mapNotNull { it[Artifacts.searchText] }
            .joinToString("\n")
        Messages.insert {
            it[id] = message.id.value
            it[conversationId] = message.conversationId.value
            it[originalIdsJson] = json.encodeToString(message.originalIds.map { id -> id.value })
            it[replyToId] = message.replyTo?.value
            it[role] = message.role.name
            it[createdAt] = message.createdAt
            it[searchText] = listOf(message.searchText(), artifactText).filter(String::isNotBlank).joinToString("\n")
            it[messageJson] = json.encodeToString(message)
        }
        message
    }

    override suspend fun findById(id: Conversation.Message.Id): Conversation.Message? = dbQuery {
        Messages.select(Messages.messageJson)
            .where { Messages.id eq id.value }
            .singleOrNull()
            ?.let { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }

    override suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message> = dbQuery {
        if (ids.isEmpty()) return@dbQuery emptyList()

        Messages.select(Messages.messageJson)
            .where { Messages.id inList ids.map { it.value } }
            .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
            .sortedBy { message -> ids.indexOf(message.id) }
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message> = dbQuery {
        Messages.select(Messages.messageJson)
            .where { Messages.conversationId eq conversationId.value }
            .orderBy(Messages.createdAt, SortOrder.ASC)
            .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }

    override suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message> = dbQuery {
        Messages.select(Messages.messageJson)
            .where { Messages.originalIdsJson like "%\"${originalId.value}\"%"}
            .orderBy(Messages.createdAt, SortOrder.ASC)
            .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }
}

internal fun Conversation.Message.searchText(): String = content.mapNotNull { item ->
    when (item) {
        is Conversation.Message.ContentItem.UserMessage -> item.text
        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
        is Conversation.Message.ContentItem.ToolResult -> item.result
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
            .joinToString("\n") { it.content }
        else -> null
    }
}.joinToString("\n").safeToolOutputText().trim()
