package com.gromozeka.infrastructure.db.memory

import com.gromozeka.infrastructure.db.memory.graph.CodeSpecSearchService
import com.gromozeka.infrastructure.db.memory.graph.GraphSearchService
import com.gromozeka.infrastructure.db.memory.graph.RerankService

import klog.KLoggers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class UnifiedSearchService(
    private val vectorMemoryService: VectorMemoryService,
    private val graphSearchService: GraphSearchService?,
    private val codeSpecSearchService: CodeSpecSearchService?,
    private val rerankService: RerankService
) {
    private val log = KLoggers.logger(this)

    suspend fun unifiedSearch(
        query: String,
        entityTypes: Set<EntityType>,
        threadId: String? = null,
        limit: Int = 5,
        useReranking: Boolean = true,
        asOf: Instant? = null,
        symbolKinds: Set<String>? = null
    ): List<UnifiedSearchResult> = coroutineScope {
        if (entityTypes.isEmpty()) {
            log.warn { "No entity types specified for search, returning empty results" }
            return@coroutineScope emptyList()
        }

        val candidateLimit = if (useReranking) limit * 3 else limit

        val candidates = mutableListOf<UnifiedSearchResult>()

        if (EntityType.CONVERSATION_MESSAGES in entityTypes) {
            val conversationDeferred = async {
                try {
                    vectorMemoryService.recall(
                        query = query,
                        threadId = threadId,
                        limit = candidateLimit
                    ).map { memory ->
                        UnifiedSearchResult(
                            content = memory.content,
                            source = EntityType.CONVERSATION_MESSAGES,
                            score = memory.score,
                            metadata = mapOf(
                                "messageId" to memory.messageId,
                                "threadId" to memory.threadId
                            )
                        )
                    }
                } catch (e: Exception) {
                    log.error(e) { "Conversation messages search failed: ${e.message}" }
                    emptyList()
                }
            }
            candidates.addAll(conversationDeferred.await())
        }

        if (EntityType.MEMORY_OBJECTS in entityTypes) {
            if (graphSearchService != null) {
                val memoryObjectsDeferred = async {
                    try {
                        val graphResults = graphSearchService.hybridSearch(
                            query = query,
                            limit = candidateLimit,
                            useReranking = false,
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
                                source = EntityType.MEMORY_OBJECTS,
                                score = score,
                                metadata = mapOf(
                                    "uuid" to (item["uuid"] as? String ?: ""),
                                    "name" to name
                                )
                            )
                        }
                    } catch (e: Exception) {
                        log.error(e) { "Memory objects search failed: ${e.message}" }
                        emptyList()
                    }
                }
                candidates.addAll(memoryObjectsDeferred.await())
            } else {
                log.warn { "Memory objects search requested but GraphSearchService is not available" }
            }
        }

        if (EntityType.CODE_SPECS in entityTypes) {
            if (codeSpecSearchService != null) {
                val codeSpecsDeferred = async {
                    try {
                        val codeResults = codeSpecSearchService.hybridSearch(
                            query = query,
                            limit = candidateLimit,
                            useReranking = false,
                            symbolKinds = symbolKinds
                        )

                        val resultsList = codeResults["results"] as? List<*> ?: emptyList<Any>()
                        resultsList.mapNotNull { result ->
                            val item = result as? Map<*, *> ?: return@mapNotNull null
                            val name = item["name"] as? String ?: ""
                            val summary = item["summary"] as? String ?: ""
                            val filePath = item["file_path"] as? String ?: ""
                            val startLine = item["start_line"] as? Int
                            val content = buildString {
                                append(name)
                                if (summary.isNotEmpty()) {
                                    append(": ")
                                    append(summary)
                                }
                                if (filePath.isNotEmpty()) {
                                    append(" (")
                                    append(filePath)
                                    if (startLine != null) {
                                        append(":")
                                        append(startLine)
                                    }
                                    append(")")
                                }
                            }
                            val score = item["score"] as? Double ?: 1.0

                            UnifiedSearchResult(
                                content = content,
                                source = EntityType.CODE_SPECS,
                                score = score,
                                metadata = mapOf(
                                    "uuid" to (item["uuid"] as? String ?: ""),
                                    "name" to name,
                                    "file_path" to filePath,
                                    "start_line" to (startLine?.toString() ?: ""),
                                    "symbol_kind" to (item["symbol_kind"] as? String ?: "")
                                ).filterValues { it.isNotEmpty() }
                            )
                        }
                    } catch (e: Exception) {
                        log.error(e) { "Code specs search failed: ${e.message}" }
                        emptyList()
                    }
                }
                candidates.addAll(codeSpecsDeferred.await())
            } else {
                log.warn { "Code specs search requested but CodeSpecSearchService is not available" }
            }
        }

        if (candidates.isEmpty()) {
            log.debug { "No candidates found for query: $query" }
            return@coroutineScope emptyList()
        }

        val finalResults = if (useReranking && candidates.size > limit) {
            log.debug { "Applying final reranking on ${candidates.size} candidates" }

            val documents = candidates.map { it.content }
            val rerankResults = rerankService.rerank(query, documents, topN = limit)

            rerankResults.map { rerankResult ->
                candidates[rerankResult.index].copy(score = rerankResult.relevanceScore.toDouble())
            }
        } else {
            candidates
                .sortedByDescending { it.score }
                .take(limit)
        }

        log.info { "Unified search returned ${finalResults.size} results (entityTypes: ${entityTypes.joinToString()}, reranking: $useReranking)" }

        finalResults
    }
}

data class UnifiedSearchResult(
    val content: String,
    val source: EntityType,
    val score: Double,
    val metadata: Map<String, String> = emptyMap()
)

enum class EntityType {
    CONVERSATION_MESSAGES,
    MEMORY_OBJECTS,
    CODE_SPECS
}
