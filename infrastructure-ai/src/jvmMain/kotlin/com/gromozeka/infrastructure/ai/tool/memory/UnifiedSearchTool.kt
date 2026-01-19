package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.domain.tool.memory.SearchScope
import com.gromozeka.domain.tool.memory.UnifiedSearchRequest
import com.gromozeka.infrastructure.db.memory.EntityType
import com.gromozeka.infrastructure.db.memory.UnifiedSearchResult
import com.gromozeka.infrastructure.db.memory.UnifiedSearchService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of UnifiedSearchTool.
 *
 * Delegates to UnifiedSearchService for hybrid search across multiple sources.
 *
 * @see com.gromozeka.domain.tool.memory.UnifiedSearchTool Domain specification
 */
@Service
class UnifiedSearchTool(
    private val unifiedSearchService: UnifiedSearchService?
) : com.gromozeka.domain.tool.memory.UnifiedSearchTool {

    private val logger = LoggerFactory.getLogger(UnifiedSearchTool::class.java)

    override fun execute(request: UnifiedSearchRequest, context: ToolContext?): Map<String, Any> {
        if (request.query.isBlank()) {
            return errorResponse("Query cannot be empty")
        }

        if (unifiedSearchService == null) {
            return errorResponse("Knowledge graph is not enabled. Enable it in configuration.")
        }

        val scopes = request.scopes.toSet()
        if (scopes.isEmpty()) {
            return errorResponse("No scopes specified. Use: memory_objects, conversation_messages, code_specs, code_specs:class, etc.")
        }

        val entityTypes = buildSet {
            if (SearchScope.hasMemoryObjects(scopes)) add(EntityType.MEMORY_OBJECTS)
            if (SearchScope.hasConversationMessages(scopes)) add(EntityType.CONVERSATION_MESSAGES)
            if (SearchScope.hasCodeSpecs(scopes)) add(EntityType.CODE_SPECS)
        }

        val symbolKinds = SearchScope.extractSymbolKinds(scopes)

        return try {
            val results = runBlocking {
                unifiedSearchService.unifiedSearch(
                    query = request.query,
                    entityTypes = entityTypes,
                    threadId = request.threadId,
                    limit = request.limit ?: 5,
                    useReranking = request.useReranking ?: true,
                    symbolKinds = symbolKinds
                )
            }

            logger.debug("Unified search for '${request.query}' returned ${results.size} results")

            if (results.isEmpty()) {
                val scopeNames = scopes.joinToString { it.toJson() }
                return mapOf(
                    "type" to "text",
                    "text" to "No results found for '${request.query}' in [$scopeNames]"
                )
            }

            mapOf(
                "type" to "text",
                "text" to formatResults(request.query, results)
            )
        } catch (e: Exception) {
            logger.error("Error in unified search for '${request.query}': ${e.message}", e)
            errorResponse("Search failed: ${e.message}")
        }
    }

    private fun formatResults(query: String, results: List<UnifiedSearchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} results for '$query':")
        sb.appendLine()

        results.forEach { result ->
            val typeLabel = when (result.source) {
                EntityType.MEMORY_OBJECTS -> "MEMORY_OBJECT"
                EntityType.CODE_SPECS -> "CODE_SPEC"
                EntityType.CONVERSATION_MESSAGES -> "CONVERSATION"
            }

            val score = String.format("%.2f", result.score)

            when (result.source) {
                EntityType.MEMORY_OBJECTS -> {
                    val name = result.metadata["name"] ?: "Unknown"
                    sb.appendLine("[$typeLabel] $name (score: $score)")
                    sb.appendLine("  ${result.content}")
                }

                EntityType.CODE_SPECS -> {
                    val name = result.metadata["name"] ?: "Unknown"
                    val filePath = result.metadata["file_path"]
                    val startLine = result.metadata["start_line"]
                    val symbolKind = result.metadata["symbol_kind"]

                    sb.appendLine("[$typeLabel] $name (score: $score)")

                    val summary = result.metadata["summary"]
                        ?: result.content.substringAfter(": ").substringBefore(" (")
                    if (summary.isNotBlank() && summary != name) {
                        sb.appendLine("  $summary")
                    }

                    if (!filePath.isNullOrBlank()) {
                        val location = if (!startLine.isNullOrBlank()) "$filePath:$startLine" else filePath
                        val kindInfo = if (!symbolKind.isNullOrBlank()) " [$symbolKind]" else ""
                        sb.appendLine("  File: $location$kindInfo")
                    }
                }

                EntityType.CONVERSATION_MESSAGES -> {
                    sb.appendLine("[$typeLabel] (score: $score)")
                    val contentPreview = result.content.take(200).let {
                        if (result.content.length > 200) "$it..." else it
                    }
                    sb.appendLine("  \"$contentPreview\"")
                    val threadId = result.metadata["threadId"]
                    if (!threadId.isNullOrBlank()) {
                        sb.appendLine("  Thread: $threadId")
                    }
                }
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    private fun errorResponse(message: String): Map<String, Any> = mapOf(
        "type" to "text",
        "text" to "Error: $message"
    )
}
