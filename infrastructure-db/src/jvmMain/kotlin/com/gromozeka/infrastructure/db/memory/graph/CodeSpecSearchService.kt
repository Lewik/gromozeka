package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class CodeSpecSearchService(
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
        symbolKinds: Set<String>? = null
    ): Map<String, Any> = coroutineScope {
        val candidateLimit = if (useReranking) maxOf(limit * 5, 50) else limit

        val results = listOf(
            async { bm25Search(query, candidateLimit) },
            async { vectorSimilaritySearch(query, candidateLimit) }
        ).awaitAll()

        var candidates = results.flatten().distinctBy { it["uuid"] }

        if (!symbolKinds.isNullOrEmpty()) {
            val normalizedKinds = symbolKinds.map { it.lowercase() }.toSet()
            candidates = candidates.filter { result ->
                val kind = (result["symbol_kind"] as? String)?.lowercase() ?: ""
                kind in normalizedKinds
            }
        }

        val finalResults = if (useReranking && candidates.isNotEmpty()) {
            val documents = candidates.map { result ->
                val name = result["name"] as? String ?: ""
                val summary = result["summary"] as? String ?: ""
                "$name: $summary"
            }

            val rerankResults = rerankService.rerank(query, documents, topN = limit)

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
                CALL db.index.fulltext.queryNodes('code_spec_index', ${'$'}query)
                YIELD node, score
                WHERE node.group_id = ${'$'}groupId
                RETURN node.uuid AS uuid,
                       node.name AS name,
                       node.summary AS summary,
                       node.file_path AS file_path,
                       node.start_line AS start_line,
                       node.end_line AS end_line,
                       node.symbol_kind AS symbol_kind,
                       score
                ORDER BY score DESC
                LIMIT ${'$'}limit
                """.trimIndent(),
                mapOf("query" to query, "groupId" to groupId, "limit" to limit)
            )

            results.map { record ->
                mapOf<String, Any>(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: ""),
                    "file_path" to (record["file_path"] as? String ?: ""),
                    "start_line" to ((record["start_line"] as? Number)?.toInt() ?: 0),
                    "end_line" to ((record["end_line"] as? Number)?.toInt() ?: 0),
                    "symbol_kind" to (record["symbol_kind"] as? String ?: ""),
                    "score" to (record["score"] as? Double ?: 0.0)
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "CodeSpec BM25 search failed: ${e.message}" }
            emptyList()
        }
    }

    suspend fun vectorSimilaritySearch(
        query: String,
        limit: Int,
        minScore: Double = 0.5
    ): List<Map<String, Any>> {
        val queryEmbedding = withContext(Dispatchers.IO) {
            embeddingModel.embed(query).toList()
        }

        return vectorSimilaritySearchIndexed(queryEmbedding, limit, minScore)
    }

    suspend fun vectorSimilaritySearchExhaustive(
        queryEmbedding: List<Float>,
        limit: Int,
        minScore: Double
    ): List<Map<String, Any>> {
        return try {
            val results = neo4jGraphStore.executeQuery(
                """
                MATCH (n:CodeSpec)
                WHERE n.group_id = ${'$'}groupId
                  AND n.embedding IS NOT NULL
                WITH n, vector.similarity.cosine(n.embedding, ${'$'}queryEmbedding) AS score
                WHERE score > ${'$'}minScore
                RETURN n.uuid AS uuid,
                       n.name AS name,
                       n.summary AS summary,
                       n.file_path AS file_path,
                       n.start_line AS start_line,
                       n.end_line AS end_line,
                       n.symbol_kind AS symbol_kind,
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
                mapOf<String, Any>(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: ""),
                    "file_path" to (record["file_path"] as? String ?: ""),
                    "start_line" to ((record["start_line"] as? Number)?.toInt() ?: 0),
                    "end_line" to ((record["end_line"] as? Number)?.toInt() ?: 0),
                    "symbol_kind" to (record["symbol_kind"] as? String ?: ""),
                    "score" to (record["score"] as? Double ?: 0.0)
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "CodeSpec vector similarity search (exhaustive) failed: ${e.message}" }
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
                CALL db.index.vector.queryNodes('code_spec_vector', ${'$'}limit, ${'$'}queryEmbedding)
                YIELD node, score
                WHERE node.group_id = ${'$'}groupId
                  AND score > ${'$'}minScore
                RETURN node.uuid AS uuid,
                       node.name AS name,
                       node.summary AS summary,
                       node.file_path AS file_path,
                       node.start_line AS start_line,
                       node.end_line AS end_line,
                       node.symbol_kind AS symbol_kind,
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
                mapOf<String, Any>(
                    "uuid" to (record["uuid"] as? String ?: ""),
                    "name" to (record["name"] as? String ?: ""),
                    "summary" to (record["summary"] as? String ?: ""),
                    "file_path" to (record["file_path"] as? String ?: ""),
                    "start_line" to ((record["start_line"] as? Number)?.toInt() ?: 0),
                    "end_line" to ((record["end_line"] as? Number)?.toInt() ?: 0),
                    "symbol_kind" to (record["symbol_kind"] as? String ?: ""),
                    "score" to (record["score"] as? Double ?: 0.0)
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "CodeSpec vector similarity search (indexed) failed: ${e.message}" }
            emptyList()
        }
    }
}
