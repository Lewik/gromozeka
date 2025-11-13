package com.gromozeka.bot.services.memory

import com.gromozeka.bot.services.memory.graph.KnowledgeGraphService
import com.gromozeka.bot.services.memory.graph.RerankService
import klog.KLoggers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class UnifiedSearchService(
    private val vectorMemoryService: VectorMemoryService,
    private val knowledgeGraphService: KnowledgeGraphService?,
    private val rerankService: RerankService
) {
    private val log = KLoggers.logger(this)

    suspend fun unifiedSearch(
        query: String,
        searchVector: Boolean = true,
        searchGraph: Boolean = true,
        useReranking: Boolean = true,
        useVectorIndex: Boolean = true,
        threadId: String? = null,
        limit: Int = 5,
        asOf: Instant? = null
    ): List<UnifiedSearchResult> = coroutineScope {
        if (!searchVector && !searchGraph) {
            log.warn { "Both searchVector and searchGraph are false, returning empty results" }
            return@coroutineScope emptyList()
        }

        val candidateLimit = if (useReranking) limit * 3 else limit

        val candidates = mutableListOf<UnifiedSearchResult>()

        if (searchVector) {
            val vectorDeferred = async {
                try {
                    vectorMemoryService.recall(
                        query = query,
                        threadId = threadId,
                        limit = candidateLimit
                    ).map { memory ->
                        UnifiedSearchResult(
                            content = memory.content,
                            source = SearchSource.VECTOR,
                            score = memory.score,
                            metadata = mapOf(
                                "messageId" to memory.messageId,
                                "threadId" to memory.threadId
                            )
                        )
                    }
                } catch (e: Exception) {
                    log.error(e) { "Vector search failed: ${e.message}" }
                    emptyList()
                }
            }
            candidates.addAll(vectorDeferred.await())
        }

        if (searchGraph && knowledgeGraphService != null) {
            val graphDeferred = async {
                try {
                    val graphResults = knowledgeGraphService.hybridSearch(
                        query = query,
                        limit = candidateLimit,
                        useReranking = false,
                        useVectorIndex = useVectorIndex,
                        asOf = asOf
                    )

                    val resultsList = graphResults["results"] as? List<*> ?: emptyList<Any>()
                    resultsList.mapNotNull { result ->
                        val item = result as? Map<*, *> ?: return@mapNotNull null
                        val name = item["name"] as? String ?: ""
                        val summary = item["summary"] as? String ?: ""
                        val content = if (summary.isNotEmpty()) {
                            "$name: $summary"
                        } else {
                            name
                        }
                        val score = item["score"] as? Double ?: 1.0

                        UnifiedSearchResult(
                            content = content,
                            source = SearchSource.GRAPH,
                            score = score,
                            metadata = mapOf(
                                "uuid" to (item["uuid"] as? String ?: ""),
                                "name" to name
                            )
                        )
                    }
                } catch (e: Exception) {
                    log.error(e) { "Graph search failed: ${e.message}" }
                    emptyList()
                }
            }
            candidates.addAll(graphDeferred.await())
        } else if (searchGraph && knowledgeGraphService == null) {
            log.warn { "Graph search requested but KnowledgeGraphService is not available" }
        }

        if (candidates.isEmpty()) {
            log.debug { "No candidates found for query: $query" }
            return@coroutineScope emptyList()
        }

        val finalResults = if (useReranking && candidates.size > limit) {
            log.debug { "Applying final reranking on ${candidates.size} candidates" }

            val documents = candidates.map { it.content }
            val rerankResults = rerankService.rerank(query, documents, topK = limit)

            rerankResults.map { rerankResult ->
                candidates[rerankResult.index].copy(score = rerankResult.relevanceScore.toDouble())
            }
        } else {
            candidates
                .sortedByDescending { it.score }
                .take(limit)
        }

        log.info { "Unified search returned ${finalResults.size} results (vector: $searchVector, graph: $searchGraph, reranking: $useReranking, vectorIndex: $useVectorIndex)" }

        finalResults
    }
}

data class UnifiedSearchResult(
    val content: String,
    val source: SearchSource,
    val score: Double,
    val metadata: Map<String, String> = emptyMap()
)

enum class SearchSource {
    VECTOR,
    GRAPH
}
