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
import org.springframework.stereotype.Service

@Service
class ExposedMessageRepository(
    private val json: Json
) : MessageRepository {

    override suspend fun save(message: Conversation.Message): Conversation.Message {
        saveBatch(listOf(message))
        return message
    }

    override suspend fun saveBatch(messages: List<Conversation.Message>) {
        if (messages.isEmpty()) return
        dbQuery {
            messages.chunked(BATCH_SIZE).forEach { batch ->
                val artifactIdsByMessage = batch.associate { message ->
                    message.id to message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                        .flatMap { it.result }
                        .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>()
                        .map { it.artifact.id.value }.distinct()
                }
                val artifactText = artifactIdsByMessage.values.flatten().distinct().chunked(BATCH_SIZE)
                    .flatMap { ids -> Artifacts.select(Artifacts.id, Artifacts.searchText)
                        .where { Artifacts.id inList ids }
                        .map { it[Artifacts.id] to it[Artifacts.searchText] } }.toMap()
                Messages.batchInsert(batch, shouldReturnGeneratedValues = false) { message ->
                    val toolText = artifactIdsByMessage.getValue(message.id).mapNotNull(artifactText::get).joinToString("\n")
                    this[Messages.id] = message.id.value
                    this[Messages.conversationId] = message.conversationId.value
                    this[Messages.originalIdsJson] = json.encodeToString(message.originalIds.map { it.value })
                    this[Messages.replyToId] = message.replyTo?.value
                    this[Messages.role] = message.role.name
                    this[Messages.createdAt] = message.createdAt
                    this[Messages.searchText] = listOf(message.searchText(), toolText).filter(String::isNotBlank).joinToString("\n")
                    this[Messages.messageText] = message.searchText(includeToolResults = false)
                    this[Messages.messageJson] = json.encodeToString(message)
                }
            }
        }
    }

    override suspend fun findById(id: Conversation.Message.Id): Conversation.Message? = dbQuery {
        Messages.select(Messages.messageJson)
            .where { Messages.id eq id.value }
            .singleOrNull()
            ?.let { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
    }

    override suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message> {
        if (ids.isEmpty()) return emptyList()
        val orderedIds = ids.distinct()
        return dbQuery {
            val found = orderedIds.chunked(BATCH_SIZE).flatMap { batch ->
                Messages.select(Messages.messageJson).where { Messages.id inList batch.map { it.value } }
                    .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
            }.associateBy { it.id }
            orderedIds.mapNotNull(found::get)
        }
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
    private companion object { const val BATCH_SIZE = 256 }

}

internal fun Conversation.Message.searchText(includeToolResults: Boolean = true): String = content.mapNotNull { item ->
    when (item) {
        is Conversation.Message.ContentItem.UserMessage -> item.text
        is Conversation.Message.ContentItem.VisualInteraction -> "[Visual button action] ${item.caption()}"
        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
        is Conversation.Message.ContentItem.ToolResult -> if (includeToolResults) item.result
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
            .joinToString("\n") { it.content } else null
        else -> null
    }
}.joinToString("\n").safeToolOutputText().trim()
