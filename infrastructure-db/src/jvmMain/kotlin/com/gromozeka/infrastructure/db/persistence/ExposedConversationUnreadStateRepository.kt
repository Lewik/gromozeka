package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationUnreadState
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationUnreadStateRepository
import com.gromozeka.infrastructure.db.persistence.tables.ConversationUnreadStates
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service

@Service
class ExposedConversationUnreadStateRepository : ConversationUnreadStateRepository {
    override suspend fun load(userId: User.Id): ConversationUnreadState = dbQuery {
        ConversationUnreadState(
            conversationIds = ConversationUnreadStates.selectAll()
                .where { ConversationUnreadStates.userId eq userId.value }
                .mapTo(mutableSetOf()) { Conversation.Id(it[ConversationUnreadStates.conversationId]) },
        )
    }

    override suspend fun markUnread(
        conversationId: Conversation.Id,
        userIds: Set<User.Id>,
    ): Set<User.Id> = dbQuery {
        userIds.filterTo(mutableSetOf()) { userId ->
            ConversationUnreadStates.insertIgnore {
                it[ConversationUnreadStates.conversationId] = conversationId.value
                it[ConversationUnreadStates.userId] = userId.value
                it[createdAt] = Clock.System.now()
            }.insertedCount > 0
        }
    }

    override suspend fun markRead(
        conversationId: Conversation.Id,
        userId: User.Id,
    ): Boolean = dbQuery {
        ConversationUnreadStates.deleteWhere {
            (ConversationUnreadStates.conversationId eq conversationId.value) and
                (ConversationUnreadStates.userId eq userId.value)
        } > 0
    }
}
