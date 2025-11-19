package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class GraphSearchService(
    private val neo4jGraphStore: Neo4jGraphStore,
    private val embeddingModel: EmbeddingModel,
    private val rerankService: RerankService
) {
    private val log = KLoggers.logger(this)
    private val groupId = "dev-user"

    suspend fun hybridSearch(
        query: String,
        limit: Int = 5,
        useReranking: Boolean = false,
        useVectorIndex: Boolean = true,
        asOf: Instant? = null
    ): Map<String, Any> = coroutineScope {
        val candidateLimit = if (useReranking) maxOf(limit * 5, 50) else limit

        val results = listOf(
            async { bm25Search(query, candidateLimit) },
            async { vectorSimilaritySearch(query, candidateLimit, useVectorIndex) },
            async { graphTraversal(query, candidateLimit, asOf) }
        ).awaitAll()

        val candidates = results.flatten().distinctBy { it["uuid"] }

        val finalResults = if (useReranking && candidates.isNotEmpty()) {
            val documents = candidates.map { result ->
                val name = result["name"] as? String ?: ""
                val summary = result["summary"] as? String ?: ""
                "$name: $summary"
            }

            val rerankResults = rerankService.rerank(query, documents, topK = limit)

            rerankResults.map { rerankResult ->
                candidates[rerankResult.index]
            }
        } else {
            candidates.take(limit)
        }

        mapOf(
            "results" to finalResults,
            "count" to finalResults.size
        )
    }

    suspend fun bm25Search(query: String, limit: Int): List<Map<String, Any>> {
        return try {
            val results = neo4jGraphStore.executeQuery(
                """
                CALL db.index.fulltext.queryNodes('memory_object_index', ${'$'}query)
                YIELD node, score
                WHERE node.group_id = ${'$'}groupId
                RETURN node.uuid AS uuid, node.name AS name, node.summary AS summary, score
                LIMIT ${'$'}limit
                """.trimIndent(),
                mapOf("query" to query, "groupId" to groupId, "limit" to limit)
            )

            results.map { record ->
                mapOf(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: ""),
                    "score" to (record["score"] as? Double ?: 0.0)
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "BM25 search failed: ${e.message}" }
            emptyList()
        }
    }

    suspend fun graphTraversal(query: String, limit: Int, asOf: Instant? = null): List<Map<String, Any>> {
        return try {
            val temporalFilter = if (asOf != null) {
                """
                AND ALL(rel IN r WHERE
                    datetime(rel.valid_at) <= datetime(${'$'}asOf)
                    AND (rel.invalid_at IS NULL OR datetime(rel.invalid_at) > datetime(${'$'}asOf))
                )
                """.trimIndent()
            } else {
                ""
            }

            val params = mutableMapOf<String, Any>(
                "query" to query,
                "groupId" to groupId,
                "limit" to limit
            )
            if (asOf != null) {
                params["asOf"] = asOf.toString()
            }

            val results = neo4jGraphStore.executeQuery(
                """
                MATCH (n:MemoryObject)-[r:LINKS_TO*1..2]-(connected:MemoryObject)
                WHERE n.group_id = ${'$'}groupId
                  AND connected.group_id = ${'$'}groupId
                  AND (n.name CONTAINS ${'$'}query OR connected.name CONTAINS ${'$'}query)
                  $temporalFilter
                RETURN DISTINCT connected.uuid AS uuid,
                       connected.name AS name,
                       connected.summary AS summary
                LIMIT ${'$'}limit
                """.trimIndent(),
                params
            )

            results.map { record ->
                mapOf(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: "")
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Graph traversal failed: ${e.message}" }
            emptyList()
        }
    }

    suspend fun vectorSimilaritySearch(
        query: String,
        limit: Int,
        useIndex: Boolean,
        minScore: Double = 0.5
    ): List<Map<String, Any>> {
        val queryEmbedding = withContext(Dispatchers.IO) {
            embeddingModel.embed(query).toList()
        }

        return if (useIndex) {
            vectorSimilaritySearchIndexed(queryEmbedding, limit, minScore)
        } else {
            vectorSimilaritySearchExhaustive(queryEmbedding, limit, minScore)
        }
    }

    suspend fun vectorSimilaritySearchExhaustive(
        queryEmbedding: List<Float>,
        limit: Int,
        minScore: Double
    ): List<Map<String, Any>> {
        return try {
            val results = neo4jGraphStore.executeQuery(
                """
                MATCH (n:MemoryObject)
                WHERE n.group_id = ${'$'}groupId
                  AND n.embedding IS NOT NULL
                WITH n, vector.similarity.cosine(n.embedding, ${'$'}queryEmbedding) AS score
                WHERE score > ${'$'}minScore
                RETURN n.uuid AS uuid,
                       n.name AS name,
                       n.summary AS summary,
                       score
                ORDER BY score DESC
                LIMIT ${'$'}limit
                """.trimIndent(),
                mapOf(
                    "queryEmbedding" to queryEmbedding,
                    "groupId" to groupId,
                    "limit" to limit,
                    "minScore" to minScore
                )
            )

            results.map { record ->
                mapOf(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: ""),
                    "score" to (record["score"] as? Double ?: 0.0)
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Vector similarity search (exhaustive) failed: ${e.message}" }
            emptyList()
        }
    }

    suspend fun vectorSimilaritySearchIndexed(
        queryEmbedding: List<Float>,
        limit: Int,
        minScore: Double
    ): List<Map<String, Any>> {
        return try {
            val results = neo4jGraphStore.executeQuery(
                """
                CALL db.index.vector.queryNodes('memory_object_vector', ${'$'}limit, ${'$'}queryEmbedding)
                YIELD node, score
                WHERE node.group_id = ${'$'}groupId
                  AND score > ${'$'}minScore
                RETURN node.uuid AS uuid,
                       node.name AS name,
                       node.summary AS summary,
                       score
                ORDER BY score DESC
                """.trimIndent(),
                mapOf(
                    "queryEmbedding" to queryEmbedding,
                    "groupId" to groupId,
                    "limit" to limit,
                    "minScore" to minScore
                )
            )

            results.map { record ->
                mapOf(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: ""),
                    "score" to (record["score"] as? Double ?: 0.0)
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Vector similarity search (indexed) failed: ${e.message}" }
            emptyList()
        }
    }
}
