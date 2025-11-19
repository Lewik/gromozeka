package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.SquashOperations
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.SquashOperationRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service

@Service
class ExposedSquashOperationRepository(
    private val json: Json
) : SquashOperationRepository {

    override suspend fun save(operation: Conversation.SquashOperation): Conversation.SquashOperation = dbQuery {
        SquashOperations.insert {
            it[id] = operation.id.value
            it[conversationId] = operation.conversationId.value
            it[sourceMessageIdsJson] = json.encodeToString(operation.sourceMessageIds.map { id -> id.value })
            it[resultMessageId] = operation.resultMessageId.value
            it[prompt] = operation.prompt
            it[model] = operation.model
            it[performedByAgent] = operation.performedByAgent
            it[createdAt] = operation.createdAt.toKotlin()
        }
        operation
    }

    override suspend fun findById(id: Conversation.SquashOperation.Id): Conversation.SquashOperation? = dbQuery {
        SquashOperations.selectAll()
            .where { SquashOperations.id eq id.value }
            .singleOrNull()
            ?.toSquashOperation()
    }

    override suspend fun findByResultMessage(messageId: Conversation.Message.Id): Conversation.SquashOperation? = dbQuery {
        SquashOperations.selectAll()
            .where { SquashOperations.resultMessageId eq messageId.value }
            .singleOrNull()
            ?.toSquashOperation()
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.SquashOperation> = dbQuery {
        SquashOperations.selectAll()
            .where { SquashOperations.conversationId eq conversationId.value }
            .orderBy(SquashOperations.createdAt, SortOrder.ASC)
            .map { it.toSquashOperation() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toSquashOperation(): Conversation.SquashOperation {
        val sourceIdsStrings = json.decodeFromString<List<String>>(this[SquashOperations.sourceMessageIdsJson])
        return Conversation.SquashOperation(
            id = Conversation.SquashOperation.Id(this[SquashOperations.id]),
            conversationId = Conversation.Id(this[SquashOperations.conversationId]),
            sourceMessageIds = sourceIdsStrings.map { Conversation.Message.Id(it) },
            resultMessageId = Conversation.Message.Id(this[SquashOperations.resultMessageId]),
            prompt = this[SquashOperations.prompt],
            model = this[SquashOperations.model],
            performedByAgent = this[SquashOperations.performedByAgent],
            createdAt = this[SquashOperations.createdAt].toKotlinx()
        )
    }
}
