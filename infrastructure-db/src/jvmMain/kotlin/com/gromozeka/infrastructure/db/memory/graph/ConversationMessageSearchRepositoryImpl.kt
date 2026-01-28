package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationMessageSearchRepository
import com.gromozeka.domain.repository.ConversationMessageSearchRepository.ConversationMessageNode
import com.gromozeka.domain.repository.ConversationMessageSearchRepository.MessageSearchResult
import com.gromozeka.domain.repository.ConversationMessageSearchRepository.SearchFilters
import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.vector.enabled"], havingValue = "true", matchIfMissing = true)
class ConversationMessageSearchRepositoryImpl(
    private val neo4jGraphStore: Neo4jGraphStore
) : ConversationMessageSearchRepository {

    private val log = KLoggers.logger(this)

    override suspend fun keywordSearch(
        query: String,
        filters: SearchFilters,
        limit: Int,
        offset: Int
    ): List<MessageSearchResult> {
        log.debug { "Keyword search: query='$query', limit=$limit, offset=$offset" }

        val whereClause = buildWhereClause(filters)

        val cypherQuery = """
            CALL db.index.fulltext.queryNodes('conversation_message_fulltext', ${'$'}query)
            YIELD node, score
            $whereClause
            RETURN node.id AS id,
                   node.conversationId AS conversationId,
                   node.threadId AS threadId,
                   node.projectId AS projectId,
                   node.role AS role,
                   node.content AS content,
                   node.createdAt AS createdAt,
                   score
            ORDER BY score DESC
            SKIP ${'$'}offset
            LIMIT ${'$'}limit
        """.trimIndent()

        val params = buildParams(query, filters, limit, offset)

        return try {
            val results = neo4jGraphStore.executeQuery(cypherQuery, params)
            results.map { row -> mapToSearchResult(row) }
        } catch (e: Exception) {
            log.error(e) { "Keyword search failed: ${e.message}" }
            throw e
        }
    }

    override suspend fun semanticSearch(
        queryEmbedding: List<Float>,
        filters: SearchFilters,
        limit: Int,
        offset: Int
    ): List<MessageSearchResult> {
        log.debug { "Semantic search: embedding size=${queryEmbedding.size}, limit=$limit, offset=$offset" }

        val whereClause = buildWhereClause(filters, nodeAlias = "node")

        val cypherQuery = """
            CALL db.index.vector.queryNodes('conversation_message_vector', $limit, ${'$'}queryEmbedding)
            YIELD node, score
            $whereClause
            RETURN node.id AS id,
                   node.conversationId AS conversationId,
                   node.threadId AS threadId,
                   node.projectId AS projectId,
                   node.role AS role,
                   node.content AS content,
                   node.createdAt AS createdAt,
                   score
            ORDER BY score DESC
        """.trimIndent()

        val params = buildParams(null, filters, limit, offset).toMutableMap()
        params["queryEmbedding"] = queryEmbedding

        return try {
            val results = neo4jGraphStore.executeQuery(cypherQuery, params)
            // Vector search returns all results, need to skip manually
            results.drop(offset).map { row -> mapToSearchResult(row) }
        } catch (e: Exception) {
            log.error(e) { "Semantic search failed: ${e.message}" }
            throw e
        }
    }

    override suspend fun hybridSearch(
        query: String,
        queryEmbedding: List<Float>,
        filters: SearchFilters,
        keywordWeight: Double,
        semanticWeight: Double,
        limit: Int,
        offset: Int
    ): List<MessageSearchResult> = coroutineScope {
        log.debug { "Hybrid search: query='$query', weights=($keywordWeight, $semanticWeight), limit=$limit" }

        // Execute both searches in parallel with higher candidate limit
        val candidateLimit = maxOf(limit * 3, 50)

        val (keywordResults, semanticResults) = listOf(
            async { keywordSearch(query, filters, candidateLimit, 0) },
            async { semanticSearch(queryEmbedding, filters, candidateLimit, 0) }
        ).awaitAll()

        // Combine and re-score results
        val combinedScores = mutableMapOf<Conversation.Message.Id, Pair<MessageSearchResult, Double>>()

        keywordResults.forEach { result ->
            val weightedScore = result.score * keywordWeight
            combinedScores[result.id] = result to weightedScore
        }

        semanticResults.forEach { result ->
            val existing = combinedScores[result.id]
            val weightedScore = result.score * semanticWeight
            
            if (existing != null) {
                // Combine scores
                val combinedScore = existing.second + weightedScore
                combinedScores[result.id] = result to combinedScore
            } else {
                combinedScores[result.id] = result to weightedScore
            }
        }

        // Sort by combined score and apply pagination
        combinedScores.values
            .sortedByDescending { it.second }
            .drop(offset)
            .take(limit)
            .map { (result, combinedScore) ->
                result.copy(score = combinedScore)
            }
    }

    override suspend fun saveMessage(message: ConversationMessageNode) {
        log.debug { "Saving message: id=${message.id.value}" }

        val cypherQuery = """
            MERGE (m:ConversationMessage {id: ${'$'}id})
            SET m.conversationId = ${'$'}conversationId,
                m.threadId = ${'$'}threadId,
                m.projectId = ${'$'}projectId,
                m.role = ${'$'}role,
                m.content = ${'$'}content,
                m.embedding = ${'$'}embedding,
                m.createdAt = datetime(${'$'}createdAt)
        """.trimIndent()

        val params = mapOf(
            "id" to message.id.value,
            "conversationId" to message.conversationId.value,
            "threadId" to message.threadId.value,
            "projectId" to message.projectId.value,
            "role" to message.role.name,
            "content" to message.content,
            "embedding" to message.embedding,
            "createdAt" to message.createdAt.toString()
        )

        try {
            neo4jGraphStore.executeQuery(cypherQuery, params)
            log.debug { "Successfully saved message: id=${message.id.value}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to save message: ${e.message}" }
            throw e
        }
    }

    override suspend fun deleteMessage(messageId: Conversation.Message.Id) {
        log.debug { "Deleting message: id=${messageId.value}" }

        val cypherQuery = """
            MATCH (m:ConversationMessage {id: ${'$'}id})
            DELETE m
        """.trimIndent()

        try {
            neo4jGraphStore.executeQuery(cypherQuery, mapOf("id" to messageId.value))
            log.debug { "Successfully deleted message: id=${messageId.value}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete message: ${e.message}" }
            throw e
        }
    }

    override suspend fun initializeIndexes() {
        log.info { "Initializing conversation message indexes..." }

        try {
            // Create vector index (HNSW)
            neo4jGraphStore.executeQuery(
                """
                CREATE VECTOR INDEX conversation_message_vector IF NOT EXISTS
                FOR (m:ConversationMessage) ON (m.embedding)
                OPTIONS {
                    indexConfig: {
                        `vector.dimensions`: 3072,
                        `vector.similarity_function`: 'cosine',
                        `vector.hnsw.m`: 32,
                        `vector.hnsw.ef_construction`: 200
                    }
                }
                """.trimIndent()
            )
            log.info { "Vector index 'conversation_message_vector' created successfully" }

            // Create fulltext index (BM25)
            neo4jGraphStore.executeQuery(
                """
                CREATE FULLTEXT INDEX conversation_message_fulltext IF NOT EXISTS
                FOR (m:ConversationMessage) ON EACH [m.content]
                """.trimIndent()
            )
            log.info { "Fulltext index 'conversation_message_fulltext' created successfully" }

        } catch (e: Exception) {
            log.error(e) { "Failed to initialize indexes: ${e.message}" }
            throw e
        }
    }

    // Helper functions

    private fun buildWhereClause(filters: SearchFilters, nodeAlias: String = "node"): String {
        val conditions = mutableListOf<String>()

        if (!filters.projectIds.isNullOrEmpty()) {
            conditions.add("$nodeAlias.projectId IN \$projectIds")
        }
        if (!filters.conversationIds.isNullOrEmpty()) {
            conditions.add("$nodeAlias.conversationId IN \$conversationIds")
        }
        if (!filters.threadIds.isNullOrEmpty()) {
            conditions.add("$nodeAlias.threadId IN \$threadIds")
        }
        if (!filters.roles.isNullOrEmpty()) {
            conditions.add("$nodeAlias.role IN \$roles")
        }
        if (filters.dateFrom != null) {
            conditions.add("datetime($nodeAlias.createdAt) >= datetime(\$dateFrom)")
        }
        if (filters.dateTo != null) {
            conditions.add("datetime($nodeAlias.createdAt) <= datetime(\$dateTo)")
        }

        return if (conditions.isEmpty()) {
            ""
        } else {
            "WHERE " + conditions.joinToString(" AND ")
        }
    }

    private fun buildParams(
        query: String?,
        filters: SearchFilters,
        limit: Int,
        offset: Int
    ): Map<String, Any> {
        val params = mutableMapOf<String, Any>(
            "limit" to limit,
            "offset" to offset
        )

        query?.let { params["query"] = it }

        filters.projectIds?.let { 
            params["projectIds"] = it.map { id -> id.value }
        }
        filters.conversationIds?.let {
            params["conversationIds"] = it.map { id -> id.value }
        }
        filters.threadIds?.let {
            params["threadIds"] = it.map { id -> id.value }
        }
        filters.roles?.let {
            params["roles"] = it.map { role -> role.name }
        }
        filters.dateFrom?.let {
            params["dateFrom"] = it.toString()
        }
        filters.dateTo?.let {
            params["dateTo"] = it.toString()
        }

        return params
    }

    private fun mapToSearchResult(row: Map<String, Any>): MessageSearchResult {
        // Neo4j returns datetime as java.time.ZonedDateTime
        val createdAt = when (val value = row["createdAt"]) {
            is java.time.ZonedDateTime -> Instant.fromEpochMilliseconds(value.toInstant().toEpochMilli())
            else -> throw IllegalStateException("Unexpected createdAt type: ${value?.javaClass}")
        }
        
        return MessageSearchResult(
            id = Conversation.Message.Id(row["id"] as String),
            conversationId = Conversation.Id(row["conversationId"] as String),
            threadId = Conversation.Thread.Id(row["threadId"] as String),
            projectId = Project.Id(row["projectId"] as String),
            role = Conversation.Message.Role.valueOf(row["role"] as String),
            content = row["content"] as String,
            score = (row["score"] as Number).toDouble(),
            createdAt = createdAt,
            highlights = emptyList() // TODO: Extract highlights from fulltext search if needed
        )
    }
}
