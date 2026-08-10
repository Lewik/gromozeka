package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationTabLayoutRepository
import com.gromozeka.infrastructure.db.persistence.tables.ConversationTabLayouts
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedConversationTabLayoutRepository : ConversationTabLayoutRepository {
    private val json = Json

    override suspend fun load(userId: User.Id): ConversationTabLayout = dbQuery {
        ConversationTabLayouts.selectAll()
            .where { ConversationTabLayouts.userId eq userId.value }
            .singleOrNull()
            ?.toLayout()
            ?: ConversationTabLayout()
    }

    override suspend fun loadAll(): Map<User.Id, ConversationTabLayout> = dbQuery {
        ConversationTabLayouts.selectAll().associate { row ->
            User.Id(row[ConversationTabLayouts.userId]) to row.toLayout()
        }
    }

    override suspend fun save(userId: User.Id, layout: ConversationTabLayout): ConversationTabLayout = dbQuery {
        val current = ConversationTabLayouts.selectAll()
            .where { ConversationTabLayouts.userId eq userId.value }
            .singleOrNull()
        val currentRevision = current?.get(ConversationTabLayouts.revision) ?: 0L
        require(layout.revision == currentRevision + 1) {
            "Conversation tab layout revision ${layout.revision} does not follow $currentRevision"
        }
        val encodedIds = json.encodeToString(
            ListSerializer(String.serializer()),
            layout.conversationIds.map { it.value },
        )
        if (current == null) {
            ConversationTabLayouts.insert {
                it[ConversationTabLayouts.userId] = userId.value
                it[conversationIdsJson] = encodedIds
                it[revision] = layout.revision
                it[updatedAt] = layout.updatedAt
            }
        } else {
            ConversationTabLayouts.update({ ConversationTabLayouts.userId eq userId.value }) {
                it[conversationIdsJson] = encodedIds
                it[revision] = layout.revision
                it[updatedAt] = layout.updatedAt
            }
        }
        layout
    }

    private fun ResultRow.toLayout(): ConversationTabLayout =
        ConversationTabLayout(
            conversationIds = json.decodeFromString(
                ListSerializer(String.serializer()),
                this[ConversationTabLayouts.conversationIdsJson],
            ).map(Conversation::Id),
            revision = this[ConversationTabLayouts.revision],
            updatedAt = this[ConversationTabLayouts.updatedAt],
        )
}
