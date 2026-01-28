package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.EntityType
import com.gromozeka.domain.model.MessageRole
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SearchMode
import com.gromozeka.domain.model.SearchResultMetadata
import com.gromozeka.domain.model.UnifiedSearchResult
import com.gromozeka.domain.repository.ConversationMessageSearchRepository
import com.gromozeka.infrastructure.db.memory.graph.CodeSpecSearchService
import com.gromozeka.infrastructure.db.memory.graph.GraphSearchService
import com.gromozeka.infrastructure.db.memory.graph.RerankService

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service

@Service
class UnifiedSearchService(
    private val conversationMessageSearchRepository: ConversationMessageSearchRepository?,
    private val graphSearchService: GraphSearchService?,
    private val codeSpecSearchService: CodeSpecSearchService?,
    private val rerankService: RerankService,
    private val embeddingModel: EmbeddingModel?
) {
    private val log = KLoggers.logger(this)

    suspend fun unifiedSearch(
        query: String,
        entityTypes: Set<EntityType>,
        searchMode: SearchMode = SearchMode.HYBRID,
        threadId: String? = null,
        conversationIds: List<String>? = null,
        roles: List<String>? = null,
        dateFrom: Instant? = null,
        dateTo: Instant? = null,
        projectIds: List<String>? = null,
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
            if (conversationMessageSearchRepository != null) {
                val conversationDeferred = async {
                    try {
                        searchConversationMessages(
                            query = query,
                            searchMode = searchMode,
                            threadId = threadId,
                            conversationIds = conversationIds,
                            roles = roles,
                            dateFrom = dateFrom,
                            dateTo = dateTo,
                            projectIds = projectIds,
                            limit = candidateLimit
                        )
                    } catch (e: Exception) {
                        log.error(e) { "Conversation messages search failed: ${e.message}" }
                        emptyList()
                    }
                }
                candidates.addAll(conversationDeferred.await())
            } else {
                log.warn { "Conversation messages search requested but ConversationMessageSearchRepository is not available" }
            }
        }

        if (EntityType.MEMORY_OBJECTS in entityTypes) {
            if (graphSearchService != null) {
                val memoryObjectsDeferred = async {
                    try {
                        searchMemoryObjects(
                            query = query,
                            searchMode = searchMode,
                            limit = candidateLimit,
                            asOf = asOf
                        )
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
                        searchCodeSpecs(
                            query = query,
                            searchMode = searchMode,
                            limit = candidateLimit,
                            symbolKinds = symbolKinds
                        )
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

    private suspend fun searchConversationMessages(
        query: String,
        searchMode: SearchMode,
        threadId: String?,
        conversationIds: List<String>?,
        roles: List<String>?,
        dateFrom: Instant?,
        dateTo: Instant?,
        projectIds: List<String>?,
        limit: Int
    ): List<UnifiedSearchResult> {
        val filters = ConversationMessageSearchRepository.SearchFilters(
            projectIds = projectIds?.map { Project.Id(it) },
            conversationIds = conversationIds?.map { Conversation.Id(it) },
            threadIds = threadId?.let { listOf(Conversation.Thread.Id(it)) },
            roles = roles?.map { Conversation.Message.Role.valueOf(it) },
            dateFrom = dateFrom,
            dateTo = dateTo
        )

        val results = when (searchMode) {
            SearchMode.KEYWORD -> {
                conversationMessageSearchRepository!!.keywordSearch(
                    query = query,
                    filters = filters,
                    limit = limit
                )
            }
            SearchMode.SEMANTIC -> {
                val queryEmbedding = withContext(Dispatchers.IO) {
                    embeddingModel!!.embed(query).toList()
                }
                conversationMessageSearchRepository!!.semanticSearch(
                    queryEmbedding = queryEmbedding,
                    filters = filters,
                    limit = limit
                )
            }
            SearchMode.HYBRID -> {
                val queryEmbedding = withContext(Dispatchers.IO) {
                    embeddingModel!!.embed(query).toList()
                }
                conversationMessageSearchRepository!!.hybridSearch(
                    query = query,
                    queryEmbedding = queryEmbedding,
                    filters = filters,
                    keywordWeight = 0.5,
                    semanticWeight = 0.5,
                    limit = limit
                )
            }
        }

        return results.map { result ->
            UnifiedSearchResult(
                content = result.content,
                source = EntityType.CONVERSATION_MESSAGES,
                score = result.score,
                metadata = SearchResultMetadata.ConversationMessage(
                    messageId = result.id.value,
                    threadId = result.threadId.value,
                    conversationId = result.conversationId.value,
                    role = MessageRole.valueOf(result.role.name),
                    createdAt = result.createdAt
                )
            )
        }
    }

    private suspend fun searchMemoryObjects(
        query: String,
        searchMode: SearchMode,
        limit: Int,
        asOf: Instant?
    ): List<UnifiedSearchResult> {
        val effectiveAsOf = asOf ?: kotlinx.datetime.Clock.System.now()
        val graphResults = when (searchMode) {
            SearchMode.KEYWORD -> {
                val results = graphSearchService!!.bm25Search(query, limit, effectiveAsOf)
                mapOf("results" to results)
            }
            SearchMode.SEMANTIC -> {
                val results = graphSearchService!!.vectorSimilaritySearch(query, limit, asOf = effectiveAsOf)
                mapOf("results" to results)
            }
            SearchMode.HYBRID -> {
                graphSearchService!!.hybridSearch(
                    query = query,
                    limit = limit,
                    useReranking = false,
                    asOf = effectiveAsOf
                )
            }
        }

        val resultsList = graphResults["results"] as? List<*> ?: emptyList<Any>()
        return resultsList.mapNotNull { result ->
            val item = result as? Map<*, *> ?: return@mapNotNull null
            val name = item["name"] as? String ?: ""
            val summary = item["summary"] as? String ?: ""
            val content = if (summary.isNotEmpty()) {
                "$name: $summary"
            } else {
                name
            }
            val score = item["score"] as? Double ?: 1.0
            
            // Parse temporal fields from Neo4j DateTime
            val createdAt = parseNeo4jDateTime(item["createdAt"])
            val validAt = parseNeo4jDateTime(item["validAt"])
            val invalidAt = parseNeo4jDateTime(item["invalidAt"])

            UnifiedSearchResult(
                content = content,
                source = EntityType.MEMORY_OBJECTS,
                score = score,
                metadata = SearchResultMetadata.MemoryObject(
                    objectId = item["uuid"] as? String ?: "",
                    objectType = item["type"] as? String ?: "",
                    createdAt = createdAt,
                    validAt = validAt,
                    invalidAt = invalidAt
                )
            )
        }
    }

    private suspend fun searchCodeSpecs(
        query: String,
        searchMode: SearchMode,
        limit: Int,
        symbolKinds: Set<String>?
    ): List<UnifiedSearchResult> {
        val codeResults = when (searchMode) {
            SearchMode.KEYWORD -> {
                val results = codeSpecSearchService!!.bm25Search(query, limit)
                mapOf("results" to results)
            }
            SearchMode.SEMANTIC -> {
                val results = codeSpecSearchService!!.vectorSimilaritySearch(query, limit)
                mapOf("results" to results)
            }
            SearchMode.HYBRID -> {
                codeSpecSearchService!!.hybridSearch(
                    query = query,
                    limit = limit,
                    useReranking = false,
                    symbolKinds = symbolKinds
                )
            }
        }

        val resultsList = codeResults["results"] as? List<*> ?: emptyList<Any>()
        return resultsList.mapNotNull { result ->
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
                metadata = SearchResultMetadata.CodeSpec(
                    symbolName = name,
                    symbolKind = item["symbol_kind"] as? String ?: "",
                    filePath = filePath,
                    lineNumber = startLine ?: 0,
                    projectId = item["project_id"] as? String ?: "",
                    lastModified = null  // TODO: Add git commit time if available
                )
            )
        }
    }
    
    /**
     * Parse Neo4j DateTime to kotlinx.datetime.Instant.
     * Returns DISTANT_PAST for null or unparseable values.
     */
    private fun parseNeo4jDateTime(value: Any?): Instant {
        return when (value) {
            null -> Instant.DISTANT_PAST  // Use DISTANT_PAST for null values
            is java.time.ZonedDateTime -> {
                try {
                    Instant.fromEpochMilliseconds(value.toInstant().toEpochMilli())
                } catch (e: Exception) {
                    log.warn(e) { "Failed to parse ZonedDateTime: $value" }
                    Instant.DISTANT_PAST
                }
            }
            is String -> {
                try {
                    Instant.parse(value)
                } catch (e: Exception) {
                    log.warn(e) { "Failed to parse DateTime string: $value" }
                    Instant.DISTANT_PAST
                }
            }
            else -> {
                log.warn { "Unexpected DateTime type: ${value.javaClass}, value: $value" }
                Instant.DISTANT_PAST
            }
        }
    }
}
