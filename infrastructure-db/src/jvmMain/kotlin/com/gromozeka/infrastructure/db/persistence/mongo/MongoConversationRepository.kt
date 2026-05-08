package com.gromozeka.infrastructure.db.persistence.mongo

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationRepository
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class MongoConversationRepository(
    database: MongoDatabase,
) : ConversationRepository {
    private val conversations: MongoCollection<Conversation> = database.getCollection("conversations")
    private val threads: MongoCollection<Conversation.Thread> = database.getCollection("conversation_threads")
    private val messages: MongoCollection<Conversation.Message> = database.getCollection("conversation_messages")
    private val threadMessages: MongoCollection<com.gromozeka.domain.repository.ThreadMessageLink> =
        database.getCollection("conversation_thread_messages")
    private val squashOperations: MongoCollection<Conversation.SquashOperation> =
        database.getCollection("conversation_squash_operations")

    private val indexes = MongoIndexInitializer {
        conversations.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        conversations.createIndex(Indexes.ascending("projectId"))
        conversations.createIndex(Indexes.descending("updatedAt"))
        threads.createIndex(Indexes.ascending("conversationId"))
        messages.createIndex(Indexes.ascending("conversationId"))
        threadMessages.createIndex(Indexes.ascending("threadId"))
        squashOperations.createIndex(Indexes.ascending("conversationId"))
    }

    override suspend fun create(conversation: Conversation): Conversation {
        indexes.ensure()
        conversations.insertNewByDomainId(conversation.id.value, conversation)
        return conversation
    }

    override suspend fun findById(id: Conversation.Id): Conversation? {
        indexes.ensure()
        return conversations.findByDomainId(id.value)
    }

    override suspend fun findByProject(projectId: Project.Id): List<Conversation> {
        indexes.ensure()
        return conversations.find(Filters.eq("projectId", projectId.value))
            .sort(Sorts.descending("updatedAt"))
            .toList()
    }

    override suspend fun delete(id: Conversation.Id) {
        indexes.ensure()
        val threadIds = threads.find(Filters.eq("conversationId", id.value)).toList().map { it.id.value }
        conversations.deleteMany(Filters.eq("id", id.value))
        threads.deleteMany(Filters.eq("conversationId", id.value))
        messages.deleteMany(Filters.eq("conversationId", id.value))
        squashOperations.deleteMany(Filters.eq("conversationId", id.value))
        if (threadIds.isNotEmpty()) {
            threadMessages.deleteMany(Filters.`in`("threadId", threadIds))
        }
    }

    override suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id) {
        update(id) { conversation ->
            conversation.copy(currentThread = threadId, updatedAt = Clock.System.now())
        }
    }

    override suspend fun updateDisplayName(id: Conversation.Id, displayName: String) {
        update(id) { conversation ->
            conversation.copy(displayName = displayName, updatedAt = Clock.System.now())
        }
    }

    override suspend fun updateAgentDefinition(
        id: Conversation.Id,
        agentDefinitionId: AgentDefinition.Id,
    ) {
        update(id) { conversation ->
            conversation.copy(agentDefinitionId = agentDefinitionId, updatedAt = Clock.System.now())
        }
    }

    override suspend fun updateStrideEnabled(id: Conversation.Id, enabled: Boolean) {
        update(id) { conversation ->
            conversation.copy(strideEnabled = enabled, updatedAt = Clock.System.now())
        }
    }

    private suspend fun update(
        id: Conversation.Id,
        transform: (Conversation) -> Conversation,
    ) {
        indexes.ensure()
        val current = conversations.find(Filters.eq("id", id.value)).firstOrNull()
            ?: error("Conversation not found: ${id.value}")
        conversations.upsertByDomainId(id.value, transform(current))
    }
}
