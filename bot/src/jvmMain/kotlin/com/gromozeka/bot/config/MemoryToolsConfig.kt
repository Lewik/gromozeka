package com.gromozeka.bot.config

import com.gromozeka.bot.services.memory.UnifiedSearchService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.UUID
import java.util.function.BiFunction

@Configuration
class MemoryToolsConfig {

    private val logger = LoggerFactory.getLogger(MemoryToolsConfig::class.java)

    @Bean
    fun unifiedSearchTool(unifiedSearchService: UnifiedSearchService): ToolCallback {
        val function = object : BiFunction<UnifiedSearchParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: UnifiedSearchParams, context: ToolContext?): Map<String, Any> {
                return try {
                    if (!request.thread_id.isNullOrBlank()) {
                        try {
                            UUID.fromString(request.thread_id)
                        } catch (e: IllegalArgumentException) {
                            return mapOf(
                                "type" to "text",
                                "text" to "Error: thread_id must be a valid UUID format or omitted for global search."
                            )
                        }
                    }

                    val asOf = request.as_of?.takeIf { it.isNotBlank() }?.let { asOfStr ->
                        try {
                            kotlinx.datetime.Instant.parse(asOfStr)
                        } catch (e: Exception) {
                            return mapOf(
                                "type" to "text",
                                "text" to "Error: as_of must be in ISO 8601 format (e.g., '2025-01-15T10:30:00Z')"
                            )
                        }
                    }

                    val results = runBlocking {
                        unifiedSearchService.unifiedSearch(
                            query = request.query,
                            searchVector = request.search_vector ?: true,
                            searchGraph = request.search_graph ?: true,
                            useReranking = request.use_reranking ?: true,
                            useVectorIndex = request.use_vector_index ?: true,
                            threadId = request.thread_id?.takeIf { it.isNotBlank() },
                            limit = request.limit ?: 5,
                            asOf = asOf
                        )
                    }

                    if (results.isEmpty()) {
                        mapOf(
                            "type" to "text",
                            "text" to "No relevant results found for query: ${request.query}"
                        )
                    } else {
                        val resultsText = buildString {
                            appendLine("Found ${results.size} relevant results:")
                            results.forEachIndexed { index, result ->
                                val sourceLabel = when (result.source) {
                                    com.gromozeka.bot.services.memory.SearchSource.VECTOR -> "[Conversation]"
                                    com.gromozeka.bot.services.memory.SearchSource.GRAPH -> "[Knowledge]"
                                }
                                appendLine("${index + 1}. $sourceLabel ${result.content}")
                            }
                        }

                        logger.debug("Unified search returned ${results.size} results for query: ${request.query}")

                        mapOf(
                            "type" to "text",
                            "text" to resultsText
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error in unified search for query: ${request.query}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error searching: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("unified_search", function)
            .description(
                """
                Unified search across conversation history and knowledge graph with flexible source selection.
                This is the PRIMARY tool for all memory and knowledge searches.

                **Search Sources (configurable):**
                - search_vector: Search conversation history via semantic vector search (default: true)
                - search_graph: Search knowledge graph entities and relationships (default: true)
                - Both can be enabled simultaneously for comprehensive results

                **Search Scope:**
                - Without thread_id: searches across all conversations (vector) and all knowledge (graph)
                - With thread_id: filters vector results to specific thread, searches all knowledge (graph)

                **Graph Vector Similarity Search:**
                - use_vector_index: Use HNSW vector index for graph search (default: true)
                - When true (default): Approximate search with ~95-99% recall, fast on any graph size
                - When false: Exhaustive search with 100% accuracy, O(n) complexity
                - Only affects knowledge graph search (search_graph), not conversation search
                - Vector index is created automatically at startup

                **Reranking:**
                - use_reranking: Apply cross-encoder reranking for better relevance (default: true)
                - When enabled: fetches 3x candidates, reranks with mxbai-rerank-xsmall-v1
                - Two-level reranking: internal (within each source) + final (cross-source)

                **Temporal Queries:**
                - as_of: Time-travel query - see knowledge as it existed at specific timestamp
                - Format: ISO 8601 (e.g., "2025-01-15T10:30:00Z")
                - Only affects knowledge graph (filters by valid_at/invalid_at timestamps)

                **When to use:**
                - ANY memory/knowledge lookup - this is your primary search tool
                - Finding user preferences, facts, entities, past decisions
                - Specialized searches: disable sources you don't need
                - Historical queries: use as_of for time-travel

                **Parameters:**
                - query: Search query (required)
                - search_vector: Enable conversation search (default: true)
                - search_graph: Enable knowledge graph search (default: true)
                - use_reranking: Enable cross-encoder reranking (default: true)
                - use_vector_index: Use vector index for graph search (default: true)
                - thread_id: Filter to specific thread (optional, UUID format)
                - limit: Number of results (default: 5)
                - as_of: Time-travel timestamp (optional, ISO 8601)

                **Examples:**
                - "What is Gromozeka?" → Both sources, comprehensive answer
                - "What did user say about Python?" → search_vector=true, search_graph=false
                - "What technologies are stored?" → search_vector=false, search_graph=true
                - "What did we know in October?" → as_of="2024-10-01T00:00:00Z"

                **Features:**
                - Single unified interface for all memory searches
                - Proper two-level reranking with real similarity scores
                - Flexible source selection (vector only, graph only, or both)
                - Parallel source queries for better performance
                """.trimIndent()
            )
            .inputType(object : ParameterizedTypeReference<UnifiedSearchParams>() {})
            .build()
    }
}

data class UnifiedSearchParams(
    val query: String,
    val search_vector: Boolean? = true,
    val search_graph: Boolean? = true,
    val use_reranking: Boolean? = true,
    val use_vector_index: Boolean? = true,
    val thread_id: String? = null,
    val limit: Int? = 5,
    val as_of: String? = null
)
