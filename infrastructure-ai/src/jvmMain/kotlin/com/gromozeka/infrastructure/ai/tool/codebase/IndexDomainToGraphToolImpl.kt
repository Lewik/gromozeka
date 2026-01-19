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

    init {
        log.info { "Using EmbeddingModel: ${embeddingModel.javaClass.name}" }
    }
    
    override fun execute(
        request: IndexDomainToGraphRequest,
        context: ToolContext?
    ): Map<String, Any> {
        val startTime = System.currentTimeMillis()
        
        return try {
            runBlocking(Dispatchers.IO) {
                log.info { "Starting domain indexing for: ${request.project_path}" }

                // Wipe only CodeSpec entities (keeps other entity types)
                log.info { "Wiping CodeSpec entities..." }
                knowledgeGraphStore.executeQuery("MATCH (n:CodeSpec) DETACH DELETE n", emptyMap())
                log.info { "CodeSpec entities wiped" }

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
                log.info { "Found ${result.inheritanceInfo.size} types with inheritance" }

                // 3. Save to graph (with MERGE - idempotent)
                saveToGraphWithMerge(result.entities, result.relationships)

                // 4. Create inheritance relationships (IMPLEMENTS/EXTENDS)
                val inheritanceCreated = createInheritanceRelationships(result.entities, result.inheritanceInfo, result.typeIsInterface)

                // 5. Cleanup stale entities
                val deletedCount = cleanupStaleEntities(
                    projectPath = request.project_path,
                    indexedAt = indexedAt
                )

                val duration = System.currentTimeMillis() - startTime

                // 6. Build breakdown
                val breakdown = buildBreakdown(result.entities)

                log.info { "✓ Indexing complete: ${result.entities.size} entities, ${result.relationships.size + inheritanceCreated} relationships, ${duration}ms" }

                mapOf(
                    "success" to true,
                    "files_total" to files.size,
                    "files_processed" to result.successCount,
                    "files_failed" to result.failureCount,
                    "symbols_indexed" to result.entities.size,
                    "entities_created" to result.entities.size,
                    "relationships_created" to result.relationships.size,
                    "inheritance_relationships_created" to inheritanceCreated,
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
        val failureCount: Int,
        val inheritanceInfo: List<InheritanceInfo> = emptyList(),
        val typeIsInterface: Map<String, Boolean> = emptyMap()
    )

    data class InheritanceInfo(
        val entityUuid: String,
        val entityName: String,
        val superTypes: List<String>,
        val isInterface: Boolean
    )

    data class HoverTypeInfo(
        val superTypes: List<String>,
        val isInterface: Boolean
    )
    
    private suspend fun extractSymbolsAndCreateGraph(
        projectPath: String,
        files: List<Path>,
        indexedAt: Instant
    ): IndexingResult {
        val entities = mutableListOf<MemoryObject>()
        val relationships = mutableListOf<MemoryLink>()
        val fileUuids = mutableMapOf<String, String>()
        val inheritanceInfo = mutableListOf<InheritanceInfo>()
        val typeIsInterface = mutableMapOf<String, Boolean>()

        val lspClient = lspClientService.getClient("kotlin", projectPath)

        var successCount = 0
        var failureCount = 0

        files.forEachIndexed { index, file ->
            log.info { "=== Processing ${index + 1}/${files.size}: ${file.fileName} (success: $successCount, failed: $failureCount) ===" }

            try {
                val fileContent = Files.readString(file)
                val filePath = file.toString()

                // Create File entity
                val fileEntity = createFileEntity(filePath, fileContent, indexedAt)
                entities.add(fileEntity)
                fileUuids[filePath] = fileEntity.uuid

                // Open file in LSP
                lspClient.didOpenFile(filePath, fileContent)

                // Get document symbols via LSP
                log.info { "Calling LSP documentSymbol for: ${file.fileName}" }
                val symbols = try {
                    lspClient.getDocumentSymbols(filePath)
                } catch (e: Exception) {
                    log.error(e) { "LSP documentSymbol failed for ${file.fileName}: ${e.message}" }
                    log.warn { "Skipping file ${file.fileName} due to LSP error" }
                    lspClient.didCloseFile(filePath)
                    failureCount++
                    return@forEachIndexed // Skip this file
                }

                log.info { "  - Found ${symbols.size} top-level symbols in ${file.fileName}" }

                // Process all symbols (including nested ones)
                symbols.forEach { symbol ->
                    processSymbol(
                        symbol = symbol,
                        filePath = filePath,
                        fileContent = fileContent,
                        indexedAt = indexedAt,
                        entities = entities,
                        relationships = relationships,
                        fileUuids = fileUuids,
                        inheritanceInfo = inheritanceInfo,
                        typeIsInterface = typeIsInterface,
                        lspClient = lspClient,
                        isTopLevel = true
                    )
                }

                // Close file
                lspClient.didCloseFile(filePath)
                successCount++
            } catch (e: Exception) {
                log.warn(e) { "Failed to process file ${file.fileName}: ${e.message}" }
                failureCount++
            }
        }

        log.info { "=== Indexing complete: $successCount files succeeded, $failureCount files failed ===" }

        return IndexingResult(entities, relationships, successCount, failureCount, inheritanceInfo, typeIsInterface)
    }

    private suspend fun processSymbol(
        symbol: org.eclipse.lsp4j.DocumentSymbol,
        filePath: String,
        fileContent: String,
        indexedAt: Instant,
        entities: MutableList<MemoryObject>,
        relationships: MutableList<MemoryLink>,
        fileUuids: Map<String, String>,
        inheritanceInfo: MutableList<InheritanceInfo>,
        typeIsInterface: MutableMap<String, Boolean>,
        lspClient: com.gromozeka.infrastructure.ai.service.lsp.LspClient,
        isTopLevel: Boolean
    ): String? {
        try {
            // Extract KDoc comment via regex
            val kdoc = extractKDoc(fileContent, symbol.range.start.line)

            // Generate structure summary
            val structureSummary = generateSummaryFromDocumentSymbol(symbol)

            // Combine KDoc + structure
            val summary = buildFinalSummary(kdoc, structureSummary)

            // Generate embedding from combined summary
            log.info { "  - Generating embedding for: ${symbol.name}" }
            val embeddingVector = embeddingModel.embed(summary)

            // Create entity
            val entity = createEntityFromDocumentSymbol(
                symbol = symbol,
                filePath = filePath,
                fileContent = fileContent,
                summary = summary,
                embedding = embeddingVector,
                indexedAt = indexedAt
            )
            entities.add(entity)

            // Extract inheritance info for classes and interfaces using LSP hover
            if (symbol.kind == org.eclipse.lsp4j.SymbolKind.Class ||
                symbol.kind == org.eclipse.lsp4j.SymbolKind.Interface) {
                val hoverInfo = extractTypeInfoViaHover(lspClient, filePath, symbol)

                // Store whether this type is an interface (for later use in relationship creation)
                typeIsInterface[symbol.name] = hoverInfo.isInterface

                if (hoverInfo.superTypes.isNotEmpty()) {
                    inheritanceInfo.add(InheritanceInfo(
                        entityUuid = entity.uuid,
                        entityName = symbol.name,
                        superTypes = hoverInfo.superTypes,
                        isInterface = hoverInfo.isInterface
                    ))
                    val typeStr = if (hoverInfo.isInterface) "interface" else "class"
                    log.info { "  - Found inheritance ($typeStr): ${symbol.name} : ${hoverInfo.superTypes.joinToString(", ")}" }
                }
            }

            // Process children recursively and collect their UUIDs
            val childUuids = mutableMapOf<String, String>()
            symbol.children?.forEach { child ->
                val childUuid = processSymbol(child, filePath, fileContent, indexedAt, entities, relationships, fileUuids, inheritanceInfo, typeIsInterface, lspClient, isTopLevel = false)
                if (childUuid != null) {
                    childUuids[child.name] = childUuid
                }
            }

            // Create relationships with actual child UUIDs
            val rels = createRelationshipsFromDocumentSymbol(
                symbol = symbol,
                entityUuid = entity.uuid,
                filePath = filePath,
                childUuids = childUuids,
                fileUuids = fileUuids,
                isTopLevel = isTopLevel
            )
            relationships.addAll(rels)

            log.info { "  - ✓ Indexed: ${symbol.name}" }

            return entity.uuid
        } catch (e: Exception) {
            log.warn(e) { "  - ✗ Failed to index ${symbol.name}: ${e.message}" }
            return null
        }
    }

    /**
     * Extract type info (super types and whether it's interface) using LSP hover.
     *
     * Kotlin LSP hover returns class signature like:
     * ```
     * public class FooImpl : FooInterface
     * public interface Bar : Baz
     * ```
     *
     * This is more reliable than regex parsing as LSP understands the syntax.
     */
    private fun extractTypeInfoViaHover(
        lspClient: com.gromozeka.infrastructure.ai.service.lsp.LspClient,
        filePath: String,
        symbol: org.eclipse.lsp4j.DocumentSymbol
    ): HoverTypeInfo {
        try {
            // Get hover at symbol name position
            val hover = lspClient.getHover(
                filePath,
                symbol.selectionRange.start.line,
                symbol.selectionRange.start.character
            )

            if (hover == null || hover.content.isBlank()) {
                log.debug { "  - No hover info for ${symbol.name}" }
                return HoverTypeInfo(emptyList(), false)
            }

            log.debug { "  - Hover for ${symbol.name}: ${hover.content.take(200)}" }

            return extractTypeInfoFromHoverContent(hover.content)
        } catch (e: Exception) {
            log.warn(e) { "  - Failed to get hover for ${symbol.name}: ${e.message}" }
            return HoverTypeInfo(emptyList(), false)
        }
    }

    /**
     * Parse type info from hover content.
     *
     * Expected formats:
     * - `public class Foo : Bar, Baz`
     * - `class Foo : Bar`
     * - `interface Foo : Bar`
     * - `interface Foo : Bar<X, Y>, Baz` (with generics)
     * - Markdown code blocks with similar content
     *
     * Returns both super types list and whether the type is an interface.
     */
    private fun extractTypeInfoFromHoverContent(hoverContent: String): HoverTypeInfo {
        // Remove markdown code block markers if present
        val content = hoverContent
            .replace("```kotlin", "")
            .replace("```", "")
            .trim()

        // Find the line with class/interface declaration
        val declarationLine = content.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.contains("class ") || line.contains("interface ") || line.contains("object ")
            } ?: return HoverTypeInfo(emptyList(), false)

        // Determine if this is an interface
        val isInterface = declarationLine.contains("interface ")

        // Find inheritance part after ":"
        val colonIndex = declarationLine.indexOf(':')
        if (colonIndex == -1) return HoverTypeInfo(emptyList(), isInterface)

        // Get text after colon
        val afterColon = declarationLine.substring(colonIndex + 1).trim()

        // Split by comma respecting generic brackets
        // "Tool<A, B>, Bar" -> ["Tool<A, B>", "Bar"]
        val superTypes = splitByCommaRespectingBrackets(afterColon)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { typeExpr ->
                // Extract just the type name (remove generics, constructor args, etc.)
                // "Bar<T>" -> "Bar"
                // "Bar()" -> "Bar"
                typeExpr
                    .substringBefore('<')
                    .substringBefore('(')
                    .substringBefore('{')
                    .trim()
            }
            .filter { it.isNotBlank() && it.first().isUpperCase() }

        return HoverTypeInfo(superTypes, isInterface)
    }

    /**
     * Split string by comma, but respect angle brackets (generics).
     * "Tool<A, B>, Bar, Baz<X>" -> ["Tool<A, B>", "Bar", "Baz<X>"]
     */
    private fun splitByCommaRespectingBrackets(input: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var bracketDepth = 0

        for (char in input) {
            when {
                char == '<' -> {
                    bracketDepth++
                    current.append(char)
                }
                char == '>' -> {
                    bracketDepth--
                    current.append(char)
                }
                char == ',' && bracketDepth == 0 -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }

        if (current.isNotBlank()) {
            result.add(current.toString())
        }

        return result
    }

    /**
     * Create IMPLEMENTS and EXTENDS relationships in the graph.
     *
     * Matches super type names to existing entities by name.
     * Creates IMPLEMENTS for interfaces, EXTENDS for classes.
     *
     * @return Number of relationships created
     */
    private suspend fun createInheritanceRelationships(
        entities: List<MemoryObject>,
        inheritanceInfo: List<InheritanceInfo>,
        typeIsInterface: Map<String, Boolean>
    ): Int {
        if (inheritanceInfo.isEmpty()) return 0

        log.info { "Creating inheritance relationships for ${inheritanceInfo.size} types..." }

        // Build name -> entity map for lookup
        val entityByName = entities
            .filter { "Class" in it.labels || "Interface" in it.labels }
            .associateBy { it.name }

        var createdCount = 0
        val now = Clock.System.now()

        for (info in inheritanceInfo) {
            for (superTypeName in info.superTypes) {
                val targetEntity = entityByName[superTypeName]
                if (targetEntity == null) {
                    log.debug { "  - Super type not found in indexed entities: ${info.entityName} : $superTypeName" }
                    continue
                }

                // Determine relationship type based on whether target is interface
                // Use typeIsInterface map which was populated from hover content
                val targetIsInterface = typeIsInterface[superTypeName] ?: false
                val relationType = if (targetIsInterface) {
                    "IMPLEMENTS"
                } else {
                    "EXTENDS"
                }

                val cypher = """
                    MATCH (source:CodeSpec {uuid: ${'$'}sourceUuid})
                    MATCH (target:CodeSpec {uuid: ${'$'}targetUuid})
                    MERGE (source)-[r:$relationType]->(target)
                    ON CREATE SET
                        r.uuid = ${'$'}uuid,
                        r.group_id = ${'$'}groupId,
                        r.created_at = datetime(${'$'}createdAt)
                    RETURN r.uuid as uuid
                """.trimIndent()

                try {
                    knowledgeGraphStore.executeQuery(
                        cypher,
                        mapOf(
                            "sourceUuid" to info.entityUuid,
                            "targetUuid" to targetEntity.uuid,
                            "uuid" to UUID.randomUUID().toString(),
                            "groupId" to groupId,
                            "createdAt" to now.toString()
                        )
                    )
                    createdCount++
                    log.info { "  - Created: ${info.entityName} -[$relationType]-> $superTypeName" }
                } catch (e: Exception) {
                    log.warn(e) { "  - Failed to create $relationType: ${info.entityName} -> $superTypeName" }
                }
            }
        }

        log.info { "Created $createdCount inheritance relationships" }
        return createdCount
    }

    private fun createFileEntity(filePath: String, fileContent: String, indexedAt: Instant): MemoryObject {
        val path = Path.of(filePath)
        val fileName = path.fileName.toString()
        val extension = fileName.substringAfterLast('.', "")
        val lineCount = fileContent.lines().size

        // Extract module, source_set, package from path and content
        val module = extractModule(filePath)
        val sourceSet = extractSourceSet(filePath)
        val packageName = extractPackage(fileContent)

        return MemoryObject(
            uuid = UUID.randomUUID().toString(),
            name = fileName,
            summary = "Kotlin source file: $fileName ($lineCount lines)",
            labels = listOf("CodeSpec", "File"),
            embedding = emptyList(),
            groupId = groupId,
            createdAt = indexedAt,
            attributes = mapOf(
                "file_path" to filePath,
                "file_name" to fileName,
                "extension" to extension,
                "line_count" to lineCount,
                "indexed_at" to indexedAt.toString(),
                "module" to module,
                "source_set" to sourceSet,
                "package" to packageName
            )
        )
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
        fileContent: String,
        summary: String,
        embedding: FloatArray,
        indexedAt: Instant
    ): MemoryObject {
        val symbolType = getSymbolTypeFromLsp(symbol.kind)

        // Extract module and package (inherited from file)
        val module = extractModule(filePath)
        val packageName = extractPackage(fileContent)

        return MemoryObject(
            uuid = UUID.randomUUID().toString(),
            name = symbol.name,
            summary = summary,
            labels = listOf("CodeSpec", symbolType),
            embedding = embedding.toList(),
            groupId = groupId,
            createdAt = indexedAt,
            attributes = mapOf(
                "file_path" to filePath,
                "start_line" to symbol.range.start.line,
                "end_line" to symbol.range.end.line,
                "symbol_kind" to symbolType,
                "fully_qualified_name" to symbol.name,
                "indexed_at" to indexedAt.toString(),
                "module" to module,
                "package" to packageName
            )
        )
    }
    
    private fun getSymbolTypeFromLsp(kind: org.eclipse.lsp4j.SymbolKind): String {
        return when (kind) {
            org.eclipse.lsp4j.SymbolKind.Interface -> "Interface"
            org.eclipse.lsp4j.SymbolKind.Class -> "Class"
            org.eclipse.lsp4j.SymbolKind.Enum -> "Enum"
            org.eclipse.lsp4j.SymbolKind.Function, org.eclipse.lsp4j.SymbolKind.Method -> "Method"
            org.eclipse.lsp4j.SymbolKind.Property, org.eclipse.lsp4j.SymbolKind.Field -> "Property"
            org.eclipse.lsp4j.SymbolKind.Constructor -> "Constructor"
            org.eclipse.lsp4j.SymbolKind.EnumMember -> "EnumMember"
            else -> "Symbol"
        }
    }
    
    private fun createRelationshipsFromDocumentSymbol(
        symbol: org.eclipse.lsp4j.DocumentSymbol,
        entityUuid: String,
        filePath: String,
        childUuids: Map<String, String>,
        fileUuids: Map<String, String>,
        isTopLevel: Boolean
    ): List<MemoryLink> {
        val links = mutableListOf<MemoryLink>()
        val now = Clock.System.now()

        // LOCATED_IN_FILE - only for top-level symbols (classes, top-level functions)
        // Nested symbols (methods, properties) are connected through their parent
        if (isTopLevel) {
            val fileUuid = fileUuids[filePath]
            if (fileUuid != null) {
                links.add(MemoryLink(
                    uuid = UUID.randomUUID().toString(),
                    sourceNodeUuid = entityUuid,
                    targetNodeUuid = fileUuid,
                    relationType = "LOCATED_IN_FILE",
                    description = "located in file",
                    embedding = null,
                    validAt = now,
                    invalidAt = null,
                    createdAt = now,
                    sources = emptyList(),
                    groupId = groupId,
                    attributes = mapOf(
                        "start_line" to symbol.range.start.line,
                        "end_line" to symbol.range.end.line
                    )
                ))
            }
        }

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

        // CONTAINS_CLASS (for nested/inner classes)
        symbol.children?.forEach { child ->
            if (child.kind == org.eclipse.lsp4j.SymbolKind.Class ||
                child.kind == org.eclipse.lsp4j.SymbolKind.Interface ||
                child.kind == org.eclipse.lsp4j.SymbolKind.Enum) {
                val childUuid = childUuids[child.name]
                if (childUuid != null) {
                    links.add(MemoryLink(
                        uuid = UUID.randomUUID().toString(),
                        sourceNodeUuid = entityUuid,
                        targetNodeUuid = childUuid,
                        relationType = "CONTAINS_CLASS",
                        description = "contains nested class",
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

        // UUID mapping: generated UUID -> actual UUID in database
        val uuidMapping = mutableMapOf<String, String>()

        // Save entities - use multi-labeling (CodeSpec + specific type)
        entities.forEach { entity ->
            val specificLabel = entity.labels[1] // Second label is the specific type (File, Class, etc.)
            val isFileEntity = specificLabel == "File"

            if (isFileEntity) {
                // File entity
                val filePath = entity.attributes["file_path"] as String
                val fileName = entity.attributes["file_name"] as String
                val extension = entity.attributes["extension"] as String
                val lineCount = entity.attributes["line_count"] as Int
                val indexedAt = entity.attributes["indexed_at"] as String
                val module = entity.attributes["module"] as String
                val sourceSet = entity.attributes["source_set"] as String
                val packageName = entity.attributes["package"] as String

                val result = knowledgeGraphStore.executeQuery(
                    """
                    MERGE (n:CodeSpec:File {
                        file_path: ${'$'}file_path,
                        group_id: ${'$'}groupId
                    })
                    ON CREATE SET
                        n.uuid = ${'$'}uuid,
                        n.name = ${'$'}name,
                        n.summary = ${'$'}summary,
                        n.created_at = datetime(${'$'}createdAt),
                        n.indexed_at = datetime(${'$'}indexedAt),
                        n.file_name = ${'$'}file_name,
                        n.extension = ${'$'}extension,
                        n.line_count = ${'$'}line_count,
                        n.module = ${'$'}module,
                        n.source_set = ${'$'}source_set,
                        n.package = ${'$'}package
                    ON MATCH SET
                        n.summary = ${'$'}summary,
                        n.indexed_at = datetime(${'$'}indexedAt),
                        n.line_count = ${'$'}line_count,
                        n.module = ${'$'}module,
                        n.source_set = ${'$'}source_set,
                        n.package = ${'$'}package
                    RETURN n.uuid as uuid
                    """.trimIndent(),
                    buildMap<String, Any> {
                        put("file_path", filePath)
                        put("groupId", entity.groupId)
                        put("uuid", entity.uuid)
                        put("name", entity.name)
                        put("summary", entity.summary)
                        put("createdAt", entity.createdAt.toString())
                        put("indexedAt", indexedAt)
                        put("file_name", fileName)
                        put("extension", extension)
                        put("line_count", lineCount)
                        put("module", module)
                        put("source_set", sourceSet)
                        put("package", packageName)
                    }
                )

                // Extract actual UUID from database
                val actualUuid = (result.firstOrNull() as? Map<*, *>)?.get("uuid") as? String
                if (actualUuid != null && actualUuid != entity.uuid) {
                    uuidMapping[entity.uuid] = actualUuid
                }
            } else {
                // Symbol entity - build Cypher with specific label
                val filePath = entity.attributes["file_path"] as String
                val symbolKind = entity.attributes["symbol_kind"] as String
                val indexedAt = entity.attributes["indexed_at"] as String
                val module = entity.attributes["module"] as String
                val packageName = entity.attributes["package"] as String

                val cypher = """
                    MERGE (n:CodeSpec:$specificLabel {
                        name: ${'$'}name,
                        file_path: ${'$'}file_path,
                        symbol_kind: ${'$'}symbol_kind,
                        group_id: ${'$'}groupId
                    })
                    ON CREATE SET
                        n.uuid = ${'$'}uuid,
                        n.embedding = ${'$'}embedding,
                        n.summary = ${'$'}summary,
                        n.created_at = datetime(${'$'}createdAt),
                        n.indexed_at = datetime(${'$'}indexedAt),
                        n.start_line = ${'$'}start_line,
                        n.end_line = ${'$'}end_line,
                        n.fqn = ${'$'}fqn,
                        n.module = ${'$'}module,
                        n.package = ${'$'}package
                    ON MATCH SET
                        n.embedding = ${'$'}embedding,
                        n.summary = ${'$'}summary,
                        n.indexed_at = datetime(${'$'}indexedAt),
                        n.start_line = ${'$'}start_line,
                        n.end_line = ${'$'}end_line,
                        n.fqn = ${'$'}fqn,
                        n.module = ${'$'}module,
                        n.package = ${'$'}package
                    RETURN n.uuid as uuid
                    """.trimIndent()

                val result = knowledgeGraphStore.executeQuery(
                    cypher,
                    buildMap<String, Any> {
                        put("name", entity.name)
                        put("file_path", filePath)
                        put("symbol_kind", symbolKind)
                        put("groupId", entity.groupId)
                        put("uuid", entity.uuid)
                        entity.embedding?.let { put("embedding", it) }
                        put("summary", entity.summary)
                        put("createdAt", entity.createdAt.toString())
                        put("indexedAt", indexedAt)
                        entity.attributes["start_line"]?.let { put("start_line", it) }
                        entity.attributes["end_line"]?.let { put("end_line", it) }
                        entity.attributes["fully_qualified_name"]?.let { put("fqn", it) }
                        put("module", module)
                        put("package", packageName)
                    }
                )

                // Extract actual UUID from database
                val actualUuid = (result.firstOrNull() as? Map<*, *>)?.get("uuid") as? String
                if (actualUuid != null && actualUuid != entity.uuid) {
                    uuidMapping[entity.uuid] = actualUuid
                }
            }
        }
        
        log.info { "Saving ${relationships.size} relationships to graph..." }
        log.info { "UUID mapping: ${uuidMapping.size} entities need remapping" }

        // Save relationships with UUID remapping
        relationships.forEach { link ->
            try {
                // Remap UUIDs if needed
                val actualSourceUuid = uuidMapping[link.sourceNodeUuid] ?: link.sourceNodeUuid
                val actualTargetUuid = uuidMapping[link.targetNodeUuid] ?: link.targetNodeUuid

                val params = buildMap<String, Any> {
                    put("sourceUuid", actualSourceUuid)
                    put("targetUuid", actualTargetUuid)
                    put("uuid", link.uuid)
                    put("groupId", link.groupId)
                    put("description", link.description)
                    put("createdAt", link.createdAt.toString())
                    link.validAt?.let { put("validAt", it.toString()) }
                }

                val cypher = when (link.relationType) {
                    "LOCATED_IN_FILE" -> """
                        MATCH (source:CodeSpec {uuid: ${'$'}sourceUuid})
                        MATCH (target:CodeSpec {uuid: ${'$'}targetUuid})
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
                        MATCH (source:CodeSpec {uuid: ${'$'}sourceUuid})
                        MATCH (target:CodeSpec {uuid: ${'$'}targetUuid})
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
                        MATCH (source:CodeSpec {uuid: ${'$'}sourceUuid})
                        MATCH (target:CodeSpec {uuid: ${'$'}targetUuid})
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

                    "CONTAINS_CLASS" -> """
                        MATCH (source:CodeSpec {uuid: ${'$'}sourceUuid})
                        MATCH (target:CodeSpec {uuid: ${'$'}targetUuid})
                        MERGE (source)-[r:CONTAINS_CLASS {
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
            MATCH (n:CodeSpec)
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

    /**
     * Extract Gradle module name from file path.
     * Example: /path/to/domain/src/jvmMain/kotlin/... → "domain"
     */
    private fun extractModule(filePath: String): String {
        val parts = filePath.split('/')
        val srcIndex = parts.indexOfLast { it == "src" }
        if (srcIndex > 0) {
            return parts[srcIndex - 1]
        }
        return "unknown"
    }

    /**
     * Extract source set from file path.
     * Example: /path/src/jvmMain/kotlin/... → "jvmMain"
     */
    private fun extractSourceSet(filePath: String): String {
        val parts = filePath.split('/')
        val srcIndex = parts.indexOfLast { it == "src" }
        if (srcIndex >= 0 && srcIndex + 1 < parts.size) {
            return parts[srcIndex + 1]
        }
        return "unknown"
    }

    /**
     * Extract package name from file content.
     * Looks for first line starting with "package ".
     */
    private fun extractPackage(fileContent: String): String {
        val packageLine = fileContent.lines()
            .firstOrNull { it.trimStart().startsWith("package ") }

        return packageLine
            ?.trimStart()
            ?.removePrefix("package ")
            ?.trim()
            ?: "unknown"
    }

    /**
     * Extract KDoc comment for a symbol at given line.
     *
     * Looks for /** ... */ block immediately before the symbol declaration.
     * Returns cleaned comment text without /** */ and leading asterisks.
     */
    private fun extractKDoc(fileContent: String, symbolLine: Int): String? {
        val lines = fileContent.lines()

        // Symbol is at line symbolLine (0-indexed)
        // NOTE: LSP sometimes includes KDoc in symbol range, so check from symbolLine itself

        // Helper function to extract single-line KDoc
        fun extractSingleLineKDoc(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.startsWith("/**") && trimmed.endsWith("*/")) {
                val cleaned = trimmed
                    .removePrefix("/**")
                    .removeSuffix("*/")
                    .trim()
                return if (cleaned.isNotBlank() && !cleaned.startsWith("@")) cleaned else null
            }
            return null
        }

        // Check if symbol line itself contains single-line KDoc
        if (symbolLine < lines.size) {
            extractSingleLineKDoc(lines[symbolLine])?.let { return it }
        }

        var commentEndLine = -1
        var commentStartLine = -1

        // Check if symbol line starts with multi-line /** (KDoc included in symbol range)
        if (symbolLine < lines.size && lines[symbolLine].trim().startsWith("/**")) {
            commentStartLine = symbolLine
            // Find end of this comment block
            for (i in symbolLine + 1 until lines.size) {
                if (lines[i].trim() == "*/") {
                    commentEndLine = i
                    break
                }
            }
        } else {
            // Look backwards for KDoc comment before symbol
            for (i in symbolLine - 1 downTo 0) {
                val trimmed = lines[i].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("@")) {
                    // Skip empty lines and @annotations (e.g., @Deprecated)
                    continue
                } else if (trimmed.startsWith("/**") && trimmed.endsWith("*/")) {
                    // Found single-line KDoc
                    return extractSingleLineKDoc(trimmed)
                } else if (trimmed == "*/") {
                    commentEndLine = i
                    // Find start of this comment block
                    for (j in i downTo 0) {
                        if (lines[j].trim().startsWith("/**")) {
                            commentStartLine = j
                            break
                        }
                    }
                    break
                } else {
                    // Found non-comment, non-annotation content - no KDoc here
                    return null
                }
            }
        }

        if (commentStartLine == -1 || commentEndLine == -1) return null

        // Extract and clean comment text
        val commentLines = lines.subList(commentStartLine, commentEndLine + 1)
        val cleaned = commentLines
            .map { it.trim() }                          // Trim each line
            .filter { it != "/**" && it != "*/" }       // Remove comment delimiters
            .map { it.removePrefix("*").trim() }        // Remove leading * from each line
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("@") }             // Remove @param, @return, @throws, etc.
            .joinToString("\n")
            .trim()

        return if (cleaned.isNotBlank()) cleaned else null
    }

    /**
     * Build final summary by combining KDoc and structure summary.
     *
     * If KDoc exists:
     *   "[KDoc content]
     *
     *   Structure: [structure summary]"
     *
     * If no KDoc:
     *   "Structure: [structure summary]"
     */
    private fun buildFinalSummary(kdoc: String?, structureSummary: String): String {
        return if (kdoc != null && kdoc.isNotBlank()) {
            "$kdoc\n\nStructure: $structureSummary"
        } else {
            "Structure: $structureSummary"
        }
    }
}
