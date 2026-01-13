package com.gromozeka.domain.tool.codebase

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for index_domain_to_graph tool.
 *
 * @property project_path Absolute path to project root directory
 * @property source_patterns File patterns to scan (e.g., ["domain/src/**/*.kt"])
 */
data class IndexDomainToGraphRequest(
    val project_path: String,
    val source_patterns: List<String> = listOf("domain/src/**/*.kt")
)

/**
 * Domain specification for indexing codebase structure to knowledge graph.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `index_domain_to_graph`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Index domain layer code structure into knowledge graph for semantic navigation and search.
 * Extracts symbols (classes, interfaces, functions, properties) with their KDoc documentation
 * and creates graph representation with vectorized embeddings for semantic search.
 *
 * ## Core Features
 *
 * **LSP-based parsing (no LLM):**
 * - Uses Serena LSP via MCP for deterministic symbol extraction
 * - Parses Kotlin AST to extract structure
 * - No AI interpretation, pure code analysis
 * - Fast and reliable
 *
 * **Knowledge graph structure:**
 * - Entities: DomainInterface, DomainClass, DomainFunction, DomainProperty
 * - Relationships: DEFINES_METHOD, HAS_PROPERTY, EXTENDS, IMPLEMENTS, etc.
 * - File locations preserved for navigation
 *
 * **Semantic search via embeddings:**
 * - KDoc vectorized using OpenAI text-embedding-3-large
 * - Enables finding symbols by semantic similarity
 * - Search by concept, not just keyword matching
 *
 * **Project activation:**
 * - Checks if project registered in Serena
 * - Activates project automatically if needed
 * - Ensures LSP has indexed the codebase
 *
 * # When to Use
 *
 * **Use index_domain_to_graph when:**
 * - Setting up new project for semantic navigation
 * - After major domain refactoring (update graph)
 * - Building AI assistant with domain knowledge
 * - Enabling semantic search across specifications
 *
 * **Don't use when:**
 * - Need to index runtime behavior → use conversation memory
 * - Need to track implementation details → graph stores specs only
 * - Working with non-Kotlin code → extend source_patterns first
 *
 * # Parameters
 *
 * ## project_path: String (required)
 *
 * Absolute path to project root directory.
 *
 * **Examples:**
 * - `"/Users/dev/gromozeka/dev"` - Development version
 * - `"/Users/dev/my-project"` - Custom project
 *
 * **Validation:**
 * - Must be existing directory
 * - Must contain source files matching patterns
 *
 * ## source_patterns: List<String> (optional, default: ["domain/src/**/*.kt"])
 *
 * Glob patterns for source files to index.
 *
 * **Default behavior:**
 * - Scans only `domain/src/` directory
 * - Kotlin files only (`*.kt`)
 * - Recursive search (`**`)
 *
 * **Extensibility:**
 * - Future: `["domain/src/**/*.kt", "domain/src/**/*.java"]`
 * - Future: `["src/**/*.py"]` for Python projects
 * - Current: Only Kotlin supported
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with indexing results:
 *
 * ## Success Response
 *
 * ```json
 * {
 *   "success": true,
 *   "files_processed": 67,
 *   "symbols_indexed": 423,
 *   "entities_created": 423,
 *   "relationships_created": 856,
 *   "duration_ms": 12450,
 *   "project_activated": false,
 *   "breakdown": {
 *     "interfaces": 45,
 *     "classes": 89,
 *     "functions": 234,
 *     "properties": 55
 *   }
 * }
 * ```
 *
 * **Fields:**
 * - `success` - Always `true` on success
 * - `files_processed` - Number of Kotlin files analyzed
 * - `symbols_indexed` - Total symbols extracted
 * - `entities_created` - MemoryObjects created in graph
 * - `relationships_created` - MemoryLinks created in graph
 * - `duration_ms` - Total execution time
 * - `project_activated` - Whether project was activated in Selene
 * - `breakdown` - Symbol counts by type
 *
 * ## Error Response
 *
 * ```json
 * {
 *   "error": "Project not found: /invalid/path",
 *   "details": "Directory does not exist or is not accessible"
 * }
 * ```
 *
 * # Error Cases
 *
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Project not found | Path doesn't exist | Check project_path spelling |
 * | No source files found | Patterns don't match any files | Verify source_patterns |
 * | Serena not available | MCP server not running | Start Serena MCP server |
 * | LSP parsing failed | Syntax errors in code | Fix compilation errors first |
 * | Graph write failed | Neo4j connection error | Check Neo4j status |
 * | Embedding failed | OpenAI API error | Check API key and quota |
 *
 * # Graph Structure
 *
 * ## Entity Types (MemoryObject)
 *
 * **DomainInterface:**
 * ```kotlin
 * MemoryObject(
 *   name = "ThreadRepository",
 *   summary = "Repository for managing conversation threads...", // KDoc
 *   labels = ["DomainInterface", "Repository"],
 *   embedding = [0.123, -0.456, ...], // 3072 dimensions
 *   attributes = mapOf(
 *     "file_path" to "domain/src/.../ThreadRepository.kt",
 *     "start_line" to 14,
 *     "end_line" to 69,
 *     "fully_qualified_name" to "com.gromozeka.domain.repository.ThreadRepository"
 *   )
 * )
 * ```
 *
 * **DomainClass:**
 * ```kotlin
 * MemoryObject(
 *   name = "Conversation.Message",
 *   summary = "Single message in conversation thread...",
 *   labels = ["DomainClass", "DataClass"],
 *   embedding = [...],
 *   attributes = mapOf(
 *     "file_path" to "domain/src/.../Conversation.kt",
 *     "is_data_class" to true,
 *     "is_sealed" to false
 *   )
 * )
 * ```
 *
 * **DomainFunction:**
 * ```kotlin
 * MemoryObject(
 *   name = "findById",
 *   summary = "Finds thread by unique identifier...",
 *   labels = ["DomainFunction", "SuspendFunction"],
 *   embedding = [...],
 *   attributes = mapOf(
 *     "signature" to "suspend fun findById(id: Thread.Id): Thread?",
 *     "is_suspend" to true,
 *     "return_type" to "Thread?"
 *   )
 * )
 * ```
 *
 * **DomainProperty:**
 * ```kotlin
 * MemoryObject(
 *   name = "id",
 *   summary = "Unique thread identifier (UUIDv7)...",
 *   labels = ["DomainProperty"],
 *   embedding = [...],
 *   attributes = mapOf(
 *     "type" to "Thread.Id",
 *     "is_nullable" to false,
 *     "is_mutable" to false
 *   )
 * )
 * ```
 *
 * ## Relationship Types (MemoryLink)
 *
 * **DEFINES_METHOD:**
 * ```kotlin
 * MemoryLink(
 *   from = "ThreadRepository", // Interface
 *   to = "findById",           // Function
 *   description = "defines method",
 *   relationType = "DEFINES_METHOD"
 * )
 * ```
 *
 * **HAS_PROPERTY:**
 * ```kotlin
 * MemoryLink(
 *   from = "Conversation.Thread", // Class
 *   to = "id",                    // Property
 *   description = "has property",
 *   relationType = "HAS_PROPERTY"
 * )
 * ```
 *
 * **IMPLEMENTS:**
 * ```kotlin
 * MemoryLink(
 *   from = "ExposedThreadRepository", // Class
 *   to = "ThreadRepository",          // Interface
 *   description = "implements",
 *   relationType = "IMPLEMENTS"
 * )
 * ```
 *
 * **EXTENDS:**
 * ```kotlin
 * MemoryLink(
 *   from = "SpecificException",  // Class
 *   to = "BaseException",        // Class
 *   description = "extends",
 *   relationType = "EXTENDS"
 * )
 * ```
 *
 * **RETURNS_TYPE:**
 * ```kotlin
 * MemoryLink(
 *   from = "findById",    // Function
 *   to = "Thread",        // Type
 *   description = "returns type",
 *   relationType = "RETURNS_TYPE"
 * )
 * ```
 *
 * **PARAMETER_TYPE:**
 * ```kotlin
 * MemoryLink(
 *   from = "findById",    // Function
 *   to = "Thread.Id",     // Type
 *   description = "parameter type: id",
 *   relationType = "PARAMETER_TYPE",
 *   attributes = mapOf("parameter_name" to "id")
 * )
 * ```
 *
 * **LOCATED_IN_FILE:**
 * ```kotlin
 * MemoryLink(
 *   from = "ThreadRepository",                        // Symbol
 *   to = "domain/src/.../ThreadRepository.kt",        // File
 *   description = "located in file",
 *   relationType = "LOCATED_IN_FILE",
 *   attributes = mapOf(
 *     "start_line" to 14,
 *     "end_line" to 69
 *   )
 * )
 * ```
 *
 * # Implementation Flow
 *
 * ## Step 1: Project Activation
 *
 * ```kotlin
 * // Check if project already active in Serena
 * val config = mcpClient.call("get_current_config")
 * val isActive = config.contains("Active project:") && config.contains(project_path)
 *
 * if (!isActive) {
 *   // Activate project (Serena auto-registers if needed)
 *   mcpClient.call("activate_project", mapOf("project" to project_path))
 *   projectActivated = true
 * }
 * ```
 *
 * ## Step 2: File Discovery
 *
 * ```kotlin
 * // Find files matching patterns
 * val files = source_patterns.flatMap { pattern ->
 *   // Current: hardcoded Kotlin
 *   findKotlinFiles(project_path, pattern)
 *   
 *   // Future: extensible
 *   // when {
 *   //   pattern.endsWith("*.kt") -> findKotlinFiles(...)
 *   //   pattern.endsWith("*.java") -> findJavaFiles(...)
 *   //   pattern.endsWith("*.py") -> findPythonFiles(...)
 *   // }
 * }
 * ```
 *
 * ## Step 3: Symbol Extraction (per file)
 *
 * ```kotlin
 * for (file in files) {
 *   // Get symbols via LSP (no LLM)
 *   val symbols = mcpClient.call("get_symbols_overview", mapOf(
 *     "relative_path" to file.relativePath,
 *     "depth" to 2 // Get nested symbols
 *   ))
 *   
 *   // Parse symbols
 *   for (symbol in symbols) {
 *     extractSymbol(symbol, file)
 *   }
 * }
 * ```
 *
 * ## Step 4: Entity Creation
 *
 * ```kotlin
 * fun extractSymbol(symbol: LspSymbol, file: File) {
 *   // Get full symbol with body and KDoc
 *   val fullSymbol = mcpClient.call("find_symbol", mapOf(
 *     "name_path_pattern" to symbol.name,
 *     "relative_path" to file.relativePath,
 *     "include_body" to true
 *   ))
 *   
 *   // Extract KDoc
 *   val kdoc = parseKDoc(fullSymbol.body)
 *   
 *   // Generate embedding
 *   val embedding = embeddingService.embed(kdoc)
 *   
 *   // Create entity
 *   val entity = MemoryObject(
 *     name = symbol.name,
 *     summary = kdoc,
 *     labels = listOf(getSymbolType(symbol)),
 *     embedding = embedding,
 *     attributes = mapOf(
 *       "file_path" to file.path,
 *       "start_line" to symbol.startLine,
 *       "end_line" to symbol.endLine
 *     )
 *   )
 *   
 *   entities.add(entity)
 * }
 * ```
 *
 * ## Step 5: Relationship Creation
 *
 * ```kotlin
 * fun createRelationships(symbol: LspSymbol, entity: MemoryObject) {
 *   when (symbol.kind) {
 *     SymbolKind.INTERFACE -> {
 *       // DEFINES_METHOD for each method
 *       symbol.children.forEach { method ->
 *         relationships.add(MemoryLink(
 *           from = entity.name,
 *           to = method.name,
 *           description = "defines method",
 *           relationType = "DEFINES_METHOD"
 *         ))
 *       }
 *     }
 *     
 *     SymbolKind.CLASS -> {
 *       // HAS_PROPERTY for each property
 *       // IMPLEMENTS for each interface
 *       // EXTENDS for superclass
 *     }
 *     
 *     SymbolKind.FUNCTION -> {
 *       // RETURNS_TYPE for return type
 *       // PARAMETER_TYPE for each parameter
 *     }
 *   }
 *   
 *   // LOCATED_IN_FILE for all symbols
 *   relationships.add(MemoryLink(
 *     from = entity.name,
 *     to = file.path,
 *     description = "located in file",
 *     relationType = "LOCATED_IN_FILE"
 *   ))
 * }
 * ```
 *
 * ## Step 6: Batch Graph Insert
 *
 * ```kotlin
 * // Save all entities and relationships in single transaction
 * knowledgeGraphStore.saveToGraph(
 *   entities = entities,
 *   relationships = relationships
 * )
 * ```
 *
 * # Usage Examples
 *
 * ## Example 1: Index Gromozeka Domain (Default)
 *
 * ```json
 * {
 *   "tool": "index_domain_to_graph",
 *   "parameters": {
 *     "project_path": "/Users/lewik/code/gromozeka/dev"
 *   }
 * }
 * ```
 *
 * **Result:** Domain layer indexed with default patterns
 *
 * ## Example 2: Index Custom Patterns
 *
 * ```json
 * {
 *   "tool": "index_domain_to_graph",
 *   "parameters": {
 *     "project_path": "/Users/dev/my-project",
 *     "source_patterns": [
 *       "core/src/**/*.kt",
 *       "api/src/**/*.kt"
 *     ]
 *   }
 * }
 * ```
 *
 * **Result:** Multiple directories indexed
 *
 * ## Example 3: Re-index After Refactoring
 *
 * ```json
 * {
 *   "tool": "index_domain_to_graph",
 *   "parameters": {
 *     "project_path": "/Users/lewik/code/gromozeka/dev"
 *   }
 * }
 * ```
 *
 * **Result:** Graph updated with new structure (old entities remain for history)
 *
 * # Semantic Search Examples
 *
 * After indexing, you can search domain by concept:
 *
 * ```kotlin
 * // Find all repositories
 * unified_search("repository interface for data access")
 * 
 * // Find message-related entities
 * unified_search("message content and metadata")
 * 
 * // Find thread operations
 * unified_search("create and manage conversation threads")
 * ```
 *
 * # Performance Characteristics
 *
 * **Gromozeka domain (~67 files):**
 * - File scanning: ~100ms
 * - LSP parsing: ~5s (67 files × 75ms avg)
 * - Embedding generation: ~6s (423 symbols × 14ms avg)
 * - Graph insert: ~1s (batch operation)
 * - **Total: ~12-15 seconds**
 *
 * **Scalability:**
 * - 100 files: ~20s
 * - 500 files: ~90s
 * - 1000 files: ~3min
 *
 * **Bottlenecks:**
 * - Embedding API calls (can be parallelized)
 * - LSP parsing (sequential, depends on Selene)
 *
 * # Transactionality
 *
 * **Graph write is transactional:**
 * - All entities and relationships created atomically
 * - Failure rolls back entire operation
 * - No partial graph state
 *
 * **Idempotency:**
 * - NOT idempotent (creates duplicate entities on re-run)
 * - Recommendation: Clear old entities first or use MERGE logic
 * - Future: Add `clear_before_index` parameter
 *
 * # Limitations
 *
 * **Current (v1):**
 * - Kotlin only (hardcoded file discovery)
 * - No incremental updates (full re-index)
 * - No duplicate detection (creates duplicates on re-run)
 * - No cross-module relationships (domain-only)
 *
 * **Future improvements:**
 * - Multi-language support (Java, Python, TypeScript)
 * - Incremental indexing (track file changes)
 * - Duplicate detection (MERGE by name + file path)
 * - Cross-module relationships (domain → infrastructure)
 * - Parallel processing (faster embedding generation)
 *
 * # Related Tools
 *
 * - **build_memory_from_text** - LLM-based entity extraction (for unstructured text)
 * - **add_memory_link** - Manual fact addition (for runtime knowledge)
 * - **unified_search** - Search indexed entities by semantic similarity
 *
 * # Infrastructure Implementation
 *
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.IndexDomainToGraphToolImpl
 *
 * # Related Domain Services
 *
 * This tool uses:
 * @see com.gromozeka.domain.service.KnowledgeGraphService For graph operations
 * @see com.gromozeka.domain.repository.KnowledgeGraphStore For graph persistence
 */
interface IndexDomainToGraphTool : Tool<IndexDomainToGraphRequest, Map<String, Any>> {

    override val name: String
        get() = "index_domain_to_graph"

    override val description: String
        get() = """
            Index domain layer code structure into knowledge graph for semantic navigation.
            
            Extracts symbols (classes, interfaces, functions, properties) from source files
            using LSP (no LLM), vectorizes KDoc for semantic search, and creates graph
            representation with relationships.
            
            Features:
            - LSP-based parsing (deterministic, no AI)
            - Automatic project activation in Selene
            - KDoc vectorization for semantic search
            - Graph structure preserves code relationships
            
            Use cases:
            - Set up semantic navigation in new project
            - Update graph after domain refactoring
            - Enable AI assistant with domain knowledge
        """.trimIndent()

    override val requestType: Class<IndexDomainToGraphRequest>
        get() = IndexDomainToGraphRequest::class.java

    override fun execute(request: IndexDomainToGraphRequest, context: ToolContext?): Map<String, Any>
}
