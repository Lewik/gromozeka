package com.gromozeka.infrastructure.ai.tool.codebase

import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.domain.repository.KnowledgeGraphStore
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphRequest
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphTool
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/**
 * Infrastructure implementation of IndexDomainToGraphTool.
 *
 * Indexes domain layer code structure into knowledge graph using:
 * - Direct LSP integration via LspClient (not Serena MCP)
 * - OpenAI embeddings for semantic search
 * - Neo4j for graph storage
 *
 * **Implementation notes:**
 * - Uses LSP documentSymbol request for hierarchical symbol extraction
 * - Generates summary from symbol structure (no KDoc extraction)
 * - Line numbers available from LSP DocumentSymbol
 * - Fast: ~5-7 seconds for 67 files (75ms avg per file)
 *
 * @see com.gromozeka.domain.tool.codebase.IndexDomainToGraphTool Full specification
 */
@Service
class IndexDomainToGraphToolImpl(
    private val lspClientService: com.gromozeka.infrastructure.ai.service.lsp.LspClientService,
    private val knowledgeGraphStore: KnowledgeGraphStore,
    private val embeddingModel: EmbeddingModel
) : IndexDomainToGraphTool {
    
    private val log = KLoggers.logger(this)
    private val groupId = "dev-user" // TODO: multi-tenancy
    
    override fun execute(
        request: IndexDomainToGraphRequest,
        context: ToolContext?
    ): Map<String, Any> {
        val startTime = System.currentTimeMillis()
        
        return try {
            runBlocking(Dispatchers.IO) {
                log.info { "Starting domain indexing for: ${request.project_path}" }

                // 1. File discovery
                val files = discoverFiles(request.project_path, request.source_patterns)
                log.info { "Found ${files.size} files to index" }
                
                if (files.isEmpty()) {
                    return@runBlocking mapOf(
                        "error" to "No source files found matching patterns: ${request.source_patterns}",
                        "project_path" to request.project_path
                    )
                }
                
                // 2. Symbol extraction + entity creation
                val indexedAt = Clock.System.now()
                val result = extractSymbolsAndCreateGraph(
                    projectPath = request.project_path,
                    files = files,
                    indexedAt = indexedAt
                )

                log.info { "Extracted ${result.entities.size} entities and ${result.relationships.size} relationships" }

                // 3. Save to graph (with MERGE - idempotent)
                saveToGraphWithMerge(result.entities, result.relationships)

                // 4. Cleanup stale entities
                val deletedCount = cleanupStaleEntities(
                    projectPath = request.project_path,
                    indexedAt = indexedAt
                )

                val duration = System.currentTimeMillis() - startTime

                // 5. Build breakdown
                val breakdown = buildBreakdown(result.entities)

                log.info { "✓ Indexing complete: ${result.entities.size} entities, ${result.relationships.size} relationships, ${duration}ms" }

                mapOf(
                    "success" to true,
                    "files_total" to files.size,
                    "files_processed" to result.successCount,
                    "files_failed" to result.failureCount,
                    "symbols_indexed" to result.entities.size,
                    "entities_created" to result.entities.size,
                    "relationships_created" to result.relationships.size,
                    "entities_deleted" to deletedCount,
                    "duration_ms" to duration,
                    "breakdown" to breakdown
                )
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to index domain: ${e.message}" }
            mapOf(
                "error" to "Failed to index domain: ${e.message}",
                "details" to e.stackTraceToString()
            )
        }
    }

    private fun discoverFiles(
        projectPath: String,
        patterns: List<String>
    ): List<Path> {
        log.info { "Scanning files with patterns: ${patterns.joinToString()}" }
        
        val projectRoot = Paths.get(projectPath)
        
        if (!Files.exists(projectRoot)) {
            throw IllegalArgumentException("Project path does not exist: $projectPath")
        }
        
        return patterns.flatMap { pattern ->
            val matcher = FileSystems.getDefault()
                .getPathMatcher("glob:$pattern")
            
            Files.walk(projectRoot)
                .filter { path ->
                    val relativePath = projectRoot.relativize(path)
                    matcher.matches(relativePath)
                }
                .filter { it.toString().endsWith(".kt") }
                .toList()
        }.distinct()
    }
    
    data class IndexingResult(
        val entities: List<MemoryObject>,
        val relationships: List<MemoryLink>,
        val successCount: Int,
        val failureCount: Int
    )
    
    private suspend fun extractSymbolsAndCreateGraph(
        projectPath: String,
        files: List<Path>,
        indexedAt: Instant
    ): IndexingResult {
        val entities = mutableListOf<MemoryObject>()
        val relationships = mutableListOf<MemoryLink>()

        val lspClient = lspClientService.getClient("kotlin", projectPath)

        var successCount = 0
        var failureCount = 0

        files.forEachIndexed { index, file ->
            log.info { "=== Processing ${index + 1}/${files.size}: ${file.fileName} (success: $successCount, failed: $failureCount) ===" }

            try {
                val fileContent = Files.readString(file)

                // Open file in LSP
                lspClient.didOpenFile(file.toString(), fileContent)

                // Get document symbols via LSP
                log.info { "Calling LSP documentSymbol for: ${file.fileName}" }
                val symbols = try {
                    lspClient.getDocumentSymbols(file.toString())
                } catch (e: Exception) {
                    log.error(e) { "LSP documentSymbol failed for ${file.fileName}: ${e.message}" }
                    log.warn { "Skipping file ${file.fileName} due to LSP error" }
                    lspClient.didCloseFile(file.toString())
                    failureCount++
                    return@forEachIndexed // Skip this file
                }

                log.info { "  - Found ${symbols.size} top-level symbols in ${file.fileName}" }

                // Process all symbols (including nested ones)
                symbols.forEach { symbol ->
                    processSymbol(
                        symbol = symbol,
                        filePath = file.toString(),
                        indexedAt = indexedAt,
                        entities = entities,
                        relationships = relationships
                    )
                }

                // Close file
                lspClient.didCloseFile(file.toString())
                successCount++
            } catch (e: Exception) {
                log.warn(e) { "Failed to process file ${file.fileName}: ${e.message}" }
                failureCount++
            }
        }

        log.info { "=== Indexing complete: $successCount files succeeded, $failureCount files failed ===" }

        return IndexingResult(entities, relationships, successCount, failureCount)
    }

    private suspend fun processSymbol(
        symbol: org.eclipse.lsp4j.DocumentSymbol,
        filePath: String,
        indexedAt: Instant,
        entities: MutableList<MemoryObject>,
        relationships: MutableList<MemoryLink>
    ): String? {
        try {
            // Generate summary from structure
            val summary = generateSummaryFromDocumentSymbol(symbol)

            // Generate embedding from summary
            log.info { "  - Generating embedding for: ${symbol.name}" }
            val embeddingVector = embeddingModel.embed(summary)

            // Create entity
            val entity = createEntityFromDocumentSymbol(
                symbol = symbol,
                filePath = filePath,
                summary = summary,
                embedding = embeddingVector,
                indexedAt = indexedAt
            )
            entities.add(entity)

            // Process children recursively and collect their UUIDs
            val childUuids = mutableMapOf<String, String>()
            symbol.children?.forEach { child ->
                val childUuid = processSymbol(child, filePath, indexedAt, entities, relationships)
                if (childUuid != null) {
                    childUuids[child.name] = childUuid
                }
            }

            // Create relationships with actual child UUIDs
            val rels = createRelationshipsFromDocumentSymbol(
                symbol = symbol,
                entityUuid = entity.uuid,
                filePath = filePath,
                childUuids = childUuids
            )
            relationships.addAll(rels)

            log.info { "  - ✓ Indexed: ${symbol.name}" }

            return entity.uuid
        } catch (e: Exception) {
            log.warn(e) { "  - ✗ Failed to index ${symbol.name}: ${e.message}" }
            return null
        }
    }
    
    private fun generateSummaryFromDocumentSymbol(symbol: org.eclipse.lsp4j.DocumentSymbol): String {
        val parts = mutableListOf<String>()

        // Base description
        val kind = symbol.kind.toString().lowercase().capitalize()
        parts.add("Kotlin $kind ${symbol.name}")

        // Add detail if present (type signature, etc.)
        symbol.detail?.let { detail ->
            if (detail.isNotBlank()) {
                parts.add(detail)
            }
        }

        // Add children info
        if (!symbol.children.isNullOrEmpty()) {
            val childrenByKind = symbol.children.groupBy { it.kind }

            childrenByKind.forEach { (symbolKind, children) ->
                val names = children.map { it.name }.take(10) // Limit to avoid huge summaries
                val moreCount = children.size - names.size
                val namesList = names.joinToString(", ")
                val moreText = if (moreCount > 0) " and $moreCount more" else ""

                val kindName = symbolKind.toString().lowercase()
                when (kindName) {
                    "method", "function" -> parts.add("with methods: $namesList$moreText")
                    "property", "field" -> parts.add("with properties: $namesList$moreText")
                    "constructor" -> parts.add("with constructors: $namesList$moreText")
                    "class" -> parts.add("with nested classes: $namesList$moreText")
                    "object" -> parts.add("with nested objects: $namesList$moreText")
                    "enummember" -> parts.add("with enum constants: $namesList$moreText")
                }
            }
        }

        return parts.joinToString(" ")
    }
    
    private fun createEntityFromDocumentSymbol(
        symbol: org.eclipse.lsp4j.DocumentSymbol,
        filePath: String,
        summary: String,
        embedding: FloatArray,
        indexedAt: Instant
    ): MemoryObject {
        val symbolType = getSymbolTypeFromLsp(symbol.kind)

        return MemoryObject(
            uuid = UUID.randomUUID().toString(),
            name = symbol.name,
            summary = summary.take(500), // Limit summary length
            labels = listOf(symbolType),
            embedding = embedding.toList(),
            groupId = groupId,
            createdAt = indexedAt,
            attributes = mapOf(
                "file_path" to filePath,
                "start_line" to symbol.range.start.line,
                "end_line" to symbol.range.end.line,
                "symbol_kind" to symbolType,
                "fully_qualified_name" to symbol.name,
                "indexed_at" to indexedAt.toString()
            )
        )
    }
    
    private fun getSymbolTypeFromLsp(kind: org.eclipse.lsp4j.SymbolKind): String {
        return when (kind) {
            org.eclipse.lsp4j.SymbolKind.Interface -> "DomainInterface"
            org.eclipse.lsp4j.SymbolKind.Class -> "DomainClass"
            org.eclipse.lsp4j.SymbolKind.Enum -> "DomainEnum"
            org.eclipse.lsp4j.SymbolKind.Function, org.eclipse.lsp4j.SymbolKind.Method -> "DomainFunction"
            org.eclipse.lsp4j.SymbolKind.Property, org.eclipse.lsp4j.SymbolKind.Field -> "DomainProperty"
            org.eclipse.lsp4j.SymbolKind.Constructor -> "DomainConstructor"
            org.eclipse.lsp4j.SymbolKind.EnumMember -> "DomainEnumMember"
            else -> "DomainSymbol"
        }
    }
    
    private fun createRelationshipsFromDocumentSymbol(
        symbol: org.eclipse.lsp4j.DocumentSymbol,
        entityUuid: String,
        filePath: String,
        childUuids: Map<String, String>
    ): List<MemoryLink> {
        val links = mutableListOf<MemoryLink>()
        val now = Clock.System.now()

        // LOCATED_IN_FILE - relationship to file path (stored as string)
        links.add(MemoryLink(
            uuid = UUID.randomUUID().toString(),
            sourceNodeUuid = entityUuid,
            targetNodeUuid = "file:$filePath", // Pseudo-UUID for file
            relationType = "LOCATED_IN_FILE",
            description = "located in file",
            embedding = null,
            validAt = now,
            invalidAt = null,
            createdAt = now,
            sources = emptyList(),
            groupId = groupId,
            attributes = mapOf(
                "file_path" to filePath,
                "start_line" to symbol.range.start.line,
                "end_line" to symbol.range.end.line
            )
        ))

        // DEFINES_METHOD (for interfaces/classes)
        symbol.children?.forEach { child ->
            if (child.kind == org.eclipse.lsp4j.SymbolKind.Method || child.kind == org.eclipse.lsp4j.SymbolKind.Function) {
                val childUuid = childUuids[child.name]
                if (childUuid != null) {
                    links.add(MemoryLink(
                        uuid = UUID.randomUUID().toString(),
                        sourceNodeUuid = entityUuid,
                        targetNodeUuid = childUuid,
                        relationType = "DEFINES_METHOD",
                        description = "defines method",
                        embedding = null,
                        validAt = now,
                        invalidAt = null,
                        createdAt = now,
                        sources = emptyList(),
                        groupId = groupId
                    ))
                }
            }
        }

        // HAS_PROPERTY (for classes)
        symbol.children?.forEach { child ->
            if (child.kind == org.eclipse.lsp4j.SymbolKind.Property || child.kind == org.eclipse.lsp4j.SymbolKind.Field) {
                val childUuid = childUuids[child.name]
                if (childUuid != null) {
                    links.add(MemoryLink(
                        uuid = UUID.randomUUID().toString(),
                        sourceNodeUuid = entityUuid,
                        targetNodeUuid = childUuid,
                        relationType = "HAS_PROPERTY",
                        description = "has property",
                        embedding = null,
                        validAt = now,
                        invalidAt = null,
                        createdAt = now,
                        sources = emptyList(),
                        groupId = groupId
                    ))
                }
            }
        }

        return links
    }
    
    private suspend fun saveToGraphWithMerge(
        entities: List<MemoryObject>,
        relationships: List<MemoryLink>
    ) {
        log.info { "Saving ${entities.size} entities to graph..." }
        
        // Save entities with MERGE on composite key (name, file_path, symbol_kind, group_id)
        entities.forEach { entity ->
            val filePath = entity.attributes["file_path"] as String
            val symbolKind = entity.attributes["symbol_kind"] as String
            val indexedAt = entity.attributes["indexed_at"] as String
            
            knowledgeGraphStore.executeQuery(
                """
                MERGE (n:MemoryObject {
                    name: ${'$'}name,
                    file_path: ${'$'}file_path,
                    symbol_kind: ${'$'}symbol_kind,
                    group_id: ${'$'}groupId
                })
                ON CREATE SET
                    n.uuid = ${'$'}uuid,
                    n.embedding = ${'$'}embedding,
                    n.summary = ${'$'}summary,
                    n.labels = ${'$'}labels,
                    n.created_at = datetime(${'$'}createdAt),
                    n.indexed_at = datetime(${'$'}indexedAt),
                    n.start_line = ${'$'}start_line,
                    n.end_line = ${'$'}end_line,
                    n.fqn = ${'$'}fqn
                ON MATCH SET
                    n.embedding = ${'$'}embedding,
                    n.summary = ${'$'}summary,
                    n.labels = ${'$'}labels,
                    n.indexed_at = datetime(${'$'}indexedAt),
                    n.start_line = ${'$'}start_line,
                    n.end_line = ${'$'}end_line,
                    n.fqn = ${'$'}fqn
                RETURN n.uuid as uuid
                """.trimIndent(),
                buildMap<String, Any> {
                    put("name", entity.name)
                    put("file_path", filePath)
                    put("symbol_kind", symbolKind)
                    put("groupId", entity.groupId)
                    put("uuid", entity.uuid)
                    entity.embedding?.let { put("embedding", it) }
                    put("summary", entity.summary)
                    put("labels", entity.labels)
                    put("createdAt", entity.createdAt.toString())
                    put("indexedAt", indexedAt)
                    entity.attributes["start_line"]?.let { put("start_line", it) }
                    entity.attributes["end_line"]?.let { put("end_line", it) }
                    entity.attributes["fully_qualified_name"]?.let { put("fqn", it) }
                }
            )
        }
        
        log.info { "Saving ${relationships.size} relationships to graph..." }

        // Save relationships
        relationships.forEach { link ->
            try {
                val params = buildMap<String, Any> {
                    put("sourceUuid", link.sourceNodeUuid)
                    put("uuid", link.uuid)
                    put("groupId", link.groupId)
                    put("description", link.description)
                    put("createdAt", link.createdAt.toString())
                    link.validAt?.let { put("validAt", it.toString()) }
                    if (!link.targetNodeUuid.startsWith("file:")) {
                        put("targetUuid", link.targetNodeUuid)
                    }
                }

                val cypher = when (link.relationType) {
                    "LOCATED_IN_FILE" -> """
                        MATCH (source:MemoryObject {uuid: ${'$'}sourceUuid})
                        MERGE (source)-[r:LOCATED_IN_FILE {
                            uuid: ${'$'}uuid,
                            group_id: ${'$'}groupId
                        }]->(target)
                        ON CREATE SET
                            r.description = ${'$'}description,
                            r.created_at = datetime(${'$'}createdAt),
                            r.valid_at = datetime(${'$'}validAt)
                        RETURN r.uuid as uuid
                        """.trimIndent()

                    "DEFINES_METHOD" -> """
                        MATCH (source:MemoryObject {uuid: ${'$'}sourceUuid})
                        MATCH (target:MemoryObject {uuid: ${'$'}targetUuid})
                        MERGE (source)-[r:DEFINES_METHOD {
                            uuid: ${'$'}uuid,
                            group_id: ${'$'}groupId
                        }]->(target)
                        ON CREATE SET
                            r.description = ${'$'}description,
                            r.created_at = datetime(${'$'}createdAt),
                            r.valid_at = datetime(${'$'}validAt)
                        RETURN r.uuid as uuid
                        """.trimIndent()

                    "HAS_PROPERTY" -> """
                        MATCH (source:MemoryObject {uuid: ${'$'}sourceUuid})
                        MATCH (target:MemoryObject {uuid: ${'$'}targetUuid})
                        MERGE (source)-[r:HAS_PROPERTY {
                            uuid: ${'$'}uuid,
                            group_id: ${'$'}groupId
                        }]->(target)
                        ON CREATE SET
                            r.description = ${'$'}description,
                            r.created_at = datetime(${'$'}createdAt),
                            r.valid_at = datetime(${'$'}validAt)
                        RETURN r.uuid as uuid
                        """.trimIndent()

                    else -> {
                        log.warn { "Unknown relationship type: ${link.relationType}" }
                        null
                    }
                }

                if (cypher != null) {
                    knowledgeGraphStore.executeQuery(cypher, params)
                }
            } catch (e: Exception) {
                log.warn(e) { "Failed to create relationship ${link.relationType}: ${e.message}" }
            }
        }
    }
    
    private suspend fun cleanupStaleEntities(
        projectPath: String,
        indexedAt: Instant
    ): Int {
        log.info { "Cleaning up stale entities..." }
        
        val result = knowledgeGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject)
            WHERE n.group_id = ${'$'}groupId
              AND n.file_path STARTS WITH ${'$'}projectPath
              AND (n.indexed_at IS NULL OR datetime(n.indexed_at) < datetime(${'$'}indexedAt))
            DETACH DELETE n
            RETURN count(n) as deleted
            """.trimIndent(),
            mapOf(
                "groupId" to groupId,
                "projectPath" to projectPath,
                "indexedAt" to indexedAt.toString()
            )
        )
        
        val deletedCount = (result.firstOrNull()?.get("deleted") as? Number)?.toInt() ?: 0
        log.info { "Deleted $deletedCount stale entities" }
        
        return deletedCount
    }
    
    private fun buildBreakdown(entities: List<MemoryObject>): Map<String, Int> {
        return mapOf(
            "interfaces" to entities.count { "DomainInterface" in it.labels },
            "classes" to entities.count { "DomainClass" in it.labels },
            "functions" to entities.count { "DomainFunction" in it.labels },
            "properties" to entities.count { "DomainProperty" in it.labels }
        )
    }
}
