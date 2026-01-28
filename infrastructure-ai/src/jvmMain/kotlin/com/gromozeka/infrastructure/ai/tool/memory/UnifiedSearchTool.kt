package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.domain.model.EntityType
import com.gromozeka.domain.model.SearchResultMetadata
import com.gromozeka.domain.model.UnifiedSearchResult
import com.gromozeka.domain.tool.memory.SearchScope
import com.gromozeka.domain.tool.memory.UnifiedSearchRequest
import com.gromozeka.infrastructure.db.memory.UnifiedSearchService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.hours

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

        val entityTypeScopes = request.entityTypes.toSet()
        if (entityTypeScopes.isEmpty()) {
            return errorResponse("No entity types specified. Use: memory_objects, conversation_messages, code_specs, code_specs:class, etc.")
        }

        val entityTypes = buildSet {
            if (SearchScope.hasMemoryObjects(entityTypeScopes)) add(EntityType.MEMORY_OBJECTS)
            if (SearchScope.hasConversationMessages(entityTypeScopes)) add(EntityType.CONVERSATION_MESSAGES)
            if (SearchScope.hasCodeSpecs(entityTypeScopes)) add(EntityType.CODE_SPECS)
        }

        val symbolKinds = SearchScope.extractSymbolKinds(entityTypeScopes)

        return try {
            val searchMode = parseSearchMode(request.searchMode)
            
            // Treat empty strings as null (LLM cannot omit parameters if they're in schema)
            val results = runBlocking {
                unifiedSearchService.unifiedSearch(
                    query = request.query,
                    entityTypes = entityTypes,
                    searchMode = searchMode,
                    threadId = request.threadId?.takeIf { it.isNotBlank() },
                    conversationIds = request.conversationIds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                    roles = request.roles?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                    dateFrom = request.dateFrom?.takeIf { it.isNotBlank() }?.let { kotlinx.datetime.Instant.parse(it) },
                    dateTo = request.dateTo?.takeIf { it.isNotBlank() }?.let { kotlinx.datetime.Instant.parse(it) },
                    projectIds = request.projectIds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                    limit = request.limit ?: 5,
                    useReranking = request.useReranking ?: true,
                    symbolKinds = symbolKinds
                )
            }

            logger.debug("Unified search for '${request.query}' returned ${results.size} results")

            if (results.isEmpty()) {
                val scopeNames = entityTypeScopes.joinToString { it.toJson() }
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

            when (val meta = result.metadata) {
                is SearchResultMetadata.MemoryObject -> {
                    val temporal = formatTemporal(meta.createdAt, meta.validAt, meta.invalidAt)
                    sb.appendLine("[$typeLabel] ${meta.objectId} (score: $score)$temporal")
                    sb.appendLine("  ${result.content}")
                    if (meta.objectType.isNotBlank()) {
                        sb.appendLine("  Type: ${meta.objectType}")
                    }
                }

                is SearchResultMetadata.CodeSpec -> {
                    val temporal = meta.lastModified?.let { formatAge(it) } ?: ""
                    sb.appendLine("[$typeLabel] ${meta.symbolName} (score: $score)$temporal")

                    val summary = result.content.substringAfter(": ", "").substringBefore(" (", "")
                    if (summary.isNotBlank() && summary != meta.symbolName) {
                        sb.appendLine("  $summary")
                    }

                    if (meta.filePath.isNotBlank()) {
                        val location = if (meta.lineNumber > 0) "${meta.filePath}:${meta.lineNumber}" else meta.filePath
                        val kindInfo = if (meta.symbolKind.isNotBlank()) " [${meta.symbolKind}]" else ""
                        sb.appendLine("  File: $location$kindInfo")
                    }
                }

                is SearchResultMetadata.ConversationMessage -> {
                    val temporal = formatAge(meta.createdAt)
                    sb.appendLine("[$typeLabel] [${meta.role.name}] (score: $score)$temporal")
                    val contentPreview = result.content.take(200).let {
                        if (result.content.length > 200) "$it..." else it
                    }
                    sb.appendLine("  \"$contentPreview\"")
                    
                    sb.appendLine("  Conversation: ${meta.conversationId}")
                    sb.appendLine("  Thread: ${meta.threadId}")
                    sb.appendLine("  Message: ${meta.messageId}")
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

    private fun parseSearchMode(mode: String?): com.gromozeka.domain.model.SearchMode {
        return when (mode?.uppercase()) {
            "KEYWORD" -> com.gromozeka.domain.model.SearchMode.KEYWORD
            "SEMANTIC" -> com.gromozeka.domain.model.SearchMode.SEMANTIC
            "HYBRID", null -> com.gromozeka.domain.model.SearchMode.HYBRID
            else -> {
                logger.warn("Unknown search mode '$mode', defaulting to HYBRID")
                com.gromozeka.domain.model.SearchMode.HYBRID
            }
        }
    }
    
    /**
     * Format age of timestamp as human-readable relative time.
     * Returns empty string for DISTANT_PAST (null values from DB).
     */
    private fun formatAge(timestamp: Instant): String {
        if (timestamp == Instant.DISTANT_PAST) return ""
        
        val now = kotlinx.datetime.Clock.System.now()
        val duration = now - timestamp
        
        return when {
            duration.inWholeMinutes < 1 -> " [just now]"
            duration.inWholeMinutes < 60 -> " [${duration.inWholeMinutes}m ago]"
            duration.inWholeHours < 24 -> " [${duration.inWholeHours}h ago]"
            duration.inWholeDays < 7 -> " [${duration.inWholeDays}d ago]"
            duration.inWholeDays < 30 -> " [${duration.inWholeDays / 7}w ago]"
            duration.inWholeDays < 365 -> " [${duration.inWholeDays / 30}mo ago]"
            else -> " [${duration.inWholeDays / 365}y ago]"
        }
    }
    
    /**
     * Format bi-temporal metadata for memory objects.
     * Shows creation time, validity period, and invalidation status.
     */
    private fun formatTemporal(createdAt: Instant, validAt: Instant?, invalidAt: Instant?): String {
        val parts = mutableListOf<String>()
        
        // Age
        if (createdAt != Instant.DISTANT_PAST) {
            parts.add(formatAge(createdAt).trim('[', ']'))
        }
        
        // Validity status
        if (invalidAt != null && invalidAt != Instant.DISTANT_PAST) {
            parts.add("INVALIDATED")
        } else if (validAt != null && validAt != Instant.DISTANT_PAST && validAt > kotlinx.datetime.Clock.System.now()) {
            parts.add("FUTURE")
        }
        
        return if (parts.isNotEmpty()) " [${parts.joinToString(", ")}]" else ""
    }
}
