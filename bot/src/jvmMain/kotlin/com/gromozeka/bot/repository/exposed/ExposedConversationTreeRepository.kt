package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.ConversationMessages
import com.gromozeka.bot.repository.exposed.tables.ConversationTrees
import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.domain.project.Project
import com.gromozeka.shared.repository.ConversationTreeRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant

class ExposedConversationTreeRepository(
    private val database: Database,
    private val json: Json
) : ConversationTreeRepository {

    override suspend fun save(tree: ConversationTree): ConversationTree = dbQuery {
        val exists = ConversationTrees.selectAll()
            .where { ConversationTrees.id eq tree.id.value }
            .count() > 0

        if (exists) {
            ConversationTrees.update({ ConversationTrees.id eq tree.id.value }) {
                it[projectId] = tree.projectId.value
                it[displayName] = tree.displayName
                it[parentConversationId] = tree.parentConversation?.value
                it[branchFromMessageId] = tree.branchFromMessage?.value
                it[headMessageId] = tree.head?.value
                it[branchSelectionsJson] = json.encodeToString(tree.branchSelections.map { id -> id.value })
                it[tags] = json.encodeToString(tree.tags)
                it[updatedAt] = tree.updatedAt.toEpochMilliseconds()
            }
        } else {
            ConversationTrees.insert {
                it[id] = tree.id.value
                it[projectId] = tree.projectId.value
                it[displayName] = tree.displayName
                it[parentConversationId] = tree.parentConversation?.value
                it[branchFromMessageId] = tree.branchFromMessage?.value
                it[headMessageId] = tree.head?.value
                it[branchSelectionsJson] = json.encodeToString(tree.branchSelections.map { id -> id.value })
                it[tags] = json.encodeToString(tree.tags)
                it[createdAt] = tree.createdAt.toEpochMilliseconds()
                it[updatedAt] = tree.updatedAt.toEpochMilliseconds()
            }
        }

        // Сохранить сообщения - заменяем все
        ConversationMessages.deleteWhere { treeId eq tree.id.value }
        tree.messages.forEach { message ->
            ConversationMessages.insert {
                it[id] = message.id.value
                it[treeId] = tree.id.value
                it[parentIdsJson] = json.encodeToString(message.parentIds.map { id -> id.value })
                it[role] = message.role.name
                it[timestampMs] = message.timestamp.toEpochMilliseconds()
                it[messageJson] = json.encodeToString(message)
            }
        }

        tree
    }

    override suspend fun findById(id: ConversationTree.Id): ConversationTree? = dbQuery {
        val treeRow = ConversationTrees.selectAll()
            .where { ConversationTrees.id eq id.value }
            .singleOrNull() ?: return@dbQuery null

        val messages = loadMessages(id)

        treeRow.toConversationTree(messages)
    }

    override suspend fun findByProject(projectId: Project.Id): List<ConversationTree> = dbQuery {
        ConversationTrees.selectAll()
            .where { ConversationTrees.projectId eq projectId.value }
            .orderBy(ConversationTrees.updatedAt, SortOrder.DESC)
            .map { row ->
                val treeId = ConversationTree.Id(row[ConversationTrees.id])
                val messages = loadMessages(treeId)
                row.toConversationTree(messages)
            }
    }

    override suspend fun delete(id: ConversationTree.Id): Unit = dbQuery {
        ConversationTrees.deleteWhere { ConversationTrees.id eq id.value }
    }

    private fun loadMessages(treeId: ConversationTree.Id): List<ConversationTree.Message> {
        return ConversationMessages.selectAll()
            .where { ConversationMessages.treeId eq treeId.value }
            .orderBy(ConversationMessages.timestampMs, SortOrder.ASC)
            .map { row ->
                json.decodeFromString<ConversationTree.Message>(row[ConversationMessages.messageJson])
            }
    }

    private fun ResultRow.toConversationTree(messages: List<ConversationTree.Message>): ConversationTree {
        val parentId: String? = this.getOrNull(ConversationTrees.parentConversationId)
        return ConversationTree(
            id = ConversationTree.Id(this[ConversationTrees.id]),
            projectId = Project.Id(this[ConversationTrees.projectId]),
            displayName = this[ConversationTrees.displayName],
            parentConversation = parentId?.let { ConversationTree.Id(it) },
            branchFromMessage = this[ConversationTrees.branchFromMessageId]?.let { ConversationTree.Message.Id(it) },
            messages = messages,
            head = this[ConversationTrees.headMessageId]?.let { ConversationTree.Message.Id(it) },
            branchSelections = json.decodeFromString<List<String>>(this[ConversationTrees.branchSelectionsJson])
                .map { ConversationTree.Message.Id(it) }.toSet(),
            tags = json.decodeFromString(this[ConversationTrees.tags]),
            createdAt = Instant.fromEpochMilliseconds(this[ConversationTrees.createdAt]),
            updatedAt = Instant.fromEpochMilliseconds(this[ConversationTrees.updatedAt])
        )
    }
}
