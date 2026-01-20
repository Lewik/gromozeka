package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for unified_search tool.
 *
 * @property query The search query (required)
 * @property entityTypes List of entity types - sources and symbol types to search
 * @property limit Maximum number of results to return (default: 5)
 * @property threadId Optional thread ID to filter conversation messages
 * @property useReranking Whether to apply cross-source reranking (default: true)
 * @property projectIds Optional list of project IDs to filter code specs (null = current project only)
 *
 * @see SearchScope for valid entity type values
 */
data class UnifiedSearchRequest(
    val query: String,
    val entityTypes: List<SearchScope>,
    val limit: Int? = null,
    val threadId: String? = null,
    val useReranking: Boolean? = null,
    val projectIds: List<String>? = null
)

/**
 * Domain specification for semantic search across knowledge graph, code, and conversations.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `unified_search`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Perform semantic search across multiple data sources using hybrid search
 * (BM25 fulltext + vector similarity) with optional cross-source reranking.
 *
 * ## Core Features
 *
 * **Multi-source search:**
 * - Knowledge graph entities (facts, concepts, people, technologies)
 * - Code symbols (classes, methods, functions, interfaces)
 * - Conversation history (past messages and context)
 *
 * **Hybrid search strategy:**
 * - BM25 fulltext search for keyword matching
 * - Vector similarity search for semantic understanding
 * - Graph traversal for connected entities (memory_objects only)
 * - Cross-source reranking for unified relevance
 *
 * **Flexible filtering:**
 * - Search specific entity types or all at once
 * - Filter conversations by thread ID
 * - Control result count with limit
 *
 * # Entity Types
 *
 * ## memory_objects
 *
 * Facts, concepts, people, technologies stored in knowledge graph.
 *
 * **Search methods:** BM25 + Vector + Graph traversal
 *
 * **Use when:**
 * - Looking for facts about technologies, people, concepts
 * - Finding relationships between entities
 * - Answering "What do we know about X?"
 *
 * **Example results:**
 * - "Spring AI: Framework for LLM integration"
 * - "Neo4j: Graph database with vector search"
 *
 * ## code_specs
 *
 * Code symbols extracted from codebase: classes, interfaces, methods, functions.
 *
 * **Search methods:** BM25 + Vector (no graph traversal)
 *
 * **Use when:**
 * - Finding code by functionality description
 * - Locating implementations of concepts
 * - Answering "Where is X implemented?"
 *
 * **Example results:**
 * - "ThreadRepository: Interface for thread persistence (domain/.../ThreadRepository.kt:15)"
 * - "UnifiedSearchService: Hybrid search across entity types"
 *
 * ## conversation_messages
 *
 * Historical conversation messages with semantic search.
 *
 * **Search methods:** Vector similarity only
 *
 * **Use when:**
 * - Recalling past discussions
 * - Finding context from previous conversations
 * - Answering "What did we discuss about X?"
 *
 * **Example results:**
 * - "We decided to use Neo4j for vector search because..."
 * - "The architecture follows Clean Architecture principles..."
 *
 * # Parameters
 *
 * ## query: String (required)
 *
 * The search query - can be natural language or keywords.
 *
 * **Good queries:**
 * - "vector search implementation" - finds related code and facts
 * - "ThreadRepository" - finds specific class and related entities
 * - "architecture decisions" - finds discussions and documented facts
 *
 * **Tips:**
 * - Use natural language for semantic search
 * - Use exact names for precise matching
 * - Combine concepts for targeted results
 *
 * ## entityTypes: List<String> (required)
 *
 * Which entity types to search. Must contain at least one valid type.
 *
 * **Valid values:**
 * - `"memory_objects"` - Knowledge graph entities
 * - `"conversation_messages"` - Chat history
 * - `"code_specs"` - All code symbols (shortcut)
 * - `"code_specs:class"` - Only classes
 * - `"code_specs:interface"` - Only interfaces
 * - `"code_specs:enum"` - Only enums
 * - `"code_specs:method"` - Only methods/functions
 * - `"code_specs:property"` - Only properties/fields
 * - `"code_specs:constructor"` - Only constructors
 *
 * **Examples:**
 * - `["memory_objects"]` - Only facts
 * - `["code_specs"]` - All code symbols
 * - `["code_specs:class", "code_specs:interface"]` - Only type definitions
 * - `["memory_objects", "code_specs"]` - Facts and all code
 * - `["memory_objects", "code_specs", "conversation_messages"]` - Everything
 *
 * ## limit: Int (optional, default: 5)
 *
 * Maximum number of results to return (across all sources).
 *
 * **Guidelines:**
 * - 5 for quick lookup
 * - 10-15 for exploration
 * - 20+ for comprehensive search
 *
 * ## threadId: String? (optional)
 *
 * Filter conversation_messages by specific thread.
 *
 * **When to use:**
 * - Search only current conversation context
 * - Find messages from specific discussion
 *
 * **When to skip:**
 * - Search across all conversations
 * - Thread context not important
 *
 * ## useReranking: Boolean (optional, default: true)
 *
 * Apply cross-source reranking for better relevance.
 *
 * **true (recommended):**
 * - Results ranked by relevance to query
 * - Best results from any source appear first
 * - Slightly slower but more accurate
 *
 * **false:**
 * - Results grouped by source
 * - Faster but may miss relevant results
 * - Use for quick lookups
 *
 * ## symbolKinds: List<String>? (optional)
 *
 * Filter CODE_SPECS results by symbol type. Only applies when searching code_specs.
 *
 * **Valid values:**
 * - `"Class"` - Class definitions
 * - `"Interface"` - Interface definitions
 * - `"Enum"` - Enum definitions
 * - `"Method"` - Methods and functions
 * - `"Property"` - Properties and fields
 * - `"Constructor"` - Constructors
 * - `"EnumMember"` - Enum members
 *
 * **Examples:**
 * - `["Class", "Interface"]` - Only type definitions
 * - `["Method"]` - Only methods
 * - `null` or omit - All symbol types (default)
 *
 * **Use cases:**
 * - Find only classes: `symbolKinds: ["Class", "Interface", "Enum"]`
 * - Find only methods: `symbolKinds: ["Method"]`
 * - Find data structures: `symbolKinds: ["Property", "Field"]`
 *
 * ## projectIds: List<String>? (optional, default: current project only)
 *
 * Filter CODE_SPECS results by project. Only applies when searching code_specs.
 *
 * **Default behavior (null):**
 * - Searches code specs from current project only
 * - Current project determined from tool context (current tab's project)
 * - Prevents cross-project code spec pollution
 *
 * **Explicit project filtering:**
 * - `["project-id-1"]` - Search only specific project
 * - `["project-id-1", "project-id-2"]` - Search multiple projects
 * - `[]` (empty list) - Search ALL projects (not recommended, returns mixed results)
 *
 * **Use cases:**
 * - Default (null): Search current project code → `projectIds: null`
 * - Compare code across projects → `projectIds: ["gromozeka-dev", "gromozeka-prod"]`
 * - Global code search → `projectIds: []` (use sparingly)
 *
 * **Note:** Memory objects (facts, concepts) are NOT filtered by project.
 * They remain global and searchable across all projects.
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with search results:
 *
 * ## Success Response
 *
 * ```json
 * {
 *   "type": "text",
 *   "text": "Found 5 results for 'vector search':\n\n[MEMORY_OBJECT] Neo4j (score: 0.92)\n  Graph database with native vector search support\n\n[CODE_SPEC] VectorMemoryService (score: 0.89)\n  Service for vector-based memory operations\n  File: infrastructure-db/.../VectorMemoryService.kt:25\n\n[CODE_SPEC] UnifiedSearchService (score: 0.85)\n  Hybrid search across entity types\n  File: infrastructure-db/.../UnifiedSearchService.kt:14\n\n[CONVERSATION] (score: 0.82)\n  \"We migrated from Qdrant to Neo4j for vector search...\"\n  Thread: abc-123\n\n[MEMORY_OBJECT] Spring AI (score: 0.78)\n  Framework for LLM integration with embedding support"
 * }
 * ```
 *
 * ## No Results Response
 *
 * ```json
 * {
 *   "type": "text",
 *   "text": "No results found for 'nonexistent query' in [memory_objects, code_specs]"
 * }
 * ```
 *
 * ## Error Response
 *
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error: No valid entity types specified. Use: memory_objects, code_specs, conversation_messages"
 * }
 * ```
 *
 * # Error Cases
 *
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty query | `query` is blank | Provide non-empty search query |
 * | No valid entity types | Invalid or empty entityTypes | Use valid types: memory_objects, code_specs, conversation_messages |
 * | Knowledge graph disabled | Service not available | Enable knowledge-graph in config |
 * | Neo4j error | Database unavailable | Check Neo4j service |
 *
 * # Usage Examples
 *
 * ## Example 1: Find Code Implementation
 *
 * **Use case:** Agent needs to find where something is implemented
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "thread persistence repository",
 *     "entityTypes": ["code_specs"],
 *     "limit": 5
 *   }
 * }
 * ```
 *
 * **Result:** Returns ThreadRepository, ConversationRepository, etc.
 *
 * ## Example 2: Research Technology
 *
 * **Use case:** Agent needs to understand a technology
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "Spring AI",
 *     "entityTypes": ["memory_objects", "code_specs"]
 *   }
 * }
 * ```
 *
 * **Result:** Returns facts about Spring AI AND code that uses it
 *
 * ## Example 3: Recall Past Discussion
 *
 * **Use case:** Agent needs context from previous conversation
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "architecture decision migration",
 *     "entityTypes": ["conversation_messages"],
 *     "threadId": "current-thread-id"
 *   }
 * }
 * ```
 *
 * **Result:** Returns relevant messages from the thread
 *
 * ## Example 4: Comprehensive Search
 *
 * **Use case:** Agent needs all information about a topic
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "vector search Neo4j",
 *     "entityTypes": ["memory_objects", "code_specs", "conversation_messages"],
 *     "limit": 15,
 *     "useReranking": true
 *   }
 * }
 * ```
 *
 * **Result:** Returns facts, code, and discussions - ranked by relevance
 *
 * ## Example 5: Search Specific Project Code
 *
 * **Use case:** Agent needs to find code in specific project (not current)
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "repository pattern",
 *     "entityTypes": ["code_specs"],
 *     "projectIds": ["fedcba98-7654-3210-fedc-ba9876543210"]
 *   }
 * }
 * ```
 *
 * **Result:** Returns code specs only from specified project
 *
 * ## Example 6: Compare Code Across Projects
 *
 * **Use case:** Agent compares implementations between dev and prod
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "authentication service",
 *     "entityTypes": ["code_specs"],
 *     "projectIds": ["gromozeka-dev", "gromozeka-prod"],
 *     "limit": 10
 *   }
 * }
 * ```
 *
 * **Result:** Returns authentication code from both projects for comparison
 *
 * # Common Patterns
 *
 * ## Pattern: Code Discovery
 *
 * Find code by description, then get details:
 *
 * 1. `unified_search("user authentication", ["code_specs"])` - Find relevant code
 * 2. `grz_read_file(path, startLine)` - Read the implementation
 *
 * ## Pattern: Knowledge + Code
 *
 * Understand concept and find implementation:
 *
 * 1. `unified_search("Clean Architecture", ["memory_objects", "code_specs"])`
 * 2. Get both facts about pattern AND code that implements it
 *
 * ## Pattern: Context Recall
 *
 * Before making decisions, recall relevant context:
 *
 * 1. `unified_search("previous decision about X", ["conversation_messages", "memory_objects"])`
 * 2. Use recalled context to inform current decision
 *
 * # Performance Characteristics
 *
 * **Execution time:**
 * - Single source: 50-200ms
 * - Multiple sources: 100-500ms (parallel execution)
 * - With reranking: +100-300ms
 *
 * **Scalability:**
 * - Uses indexed search (HNSW for vectors, fulltext indexes for BM25)
 * - Scales well with data size
 * - Reranking cost increases with candidate count
 *
 * # Related Tools
 *
 * - **get_memory_object** - Get specific entity by exact name (not search)
 * - **add_memory_link** - Add facts to knowledge graph
 * - **build_memory_from_text** - Extract entities from text
 * - **index_domain_to_graph** - Index code to make it searchable
 * - **grz_read_file** - Read code files found by search
 *
 * # Infrastructure Implementation
 *
 * @see com.gromozeka.infrastructure.ai.tool.memory.UnifiedSearchTool
 *
 * # Related Services
 *
 * @see com.gromozeka.infrastructure.db.memory.UnifiedSearchService
 * @see com.gromozeka.infrastructure.db.memory.graph.GraphSearchService
 * @see com.gromozeka.infrastructure.db.memory.graph.CodeSpecSearchService
 */
interface UnifiedSearchTool : Tool<UnifiedSearchRequest, Map<String, Any>> {

    override val name: String
        get() = "unified_search"

    override val description: String
        get() = """
            Semantic search across knowledge graph, code, and conversation history.

            **Entity Types (what to search):**
            - `memory_objects` - Facts, concepts, technologies in knowledge graph
            - `conversation_messages` - Past conversation messages
            - `code_specs` - All code symbols (shortcut)
            - `code_specs:class` - Only classes
            - `code_specs:interface` - Only interfaces
            - `code_specs:enum` - Only enums
            - `code_specs:method` - Only methods/functions
            - `code_specs:property` - Only properties/fields
            - `code_specs:constructor` - Only constructors

            **Parameters:**
            - query: Search query - natural language ("how authentication works") or exact names ("ThreadRepository")
            - entityTypes: List of entity types to search (required)
            - limit: Max results (default: 5)
            - threadId: Filter conversations by thread (optional)
            - useReranking: Cross-source relevance ranking (default: true)

            **Examples:**
            ```json
            // Find only classes and interfaces
            {"query": "Repository", "entityTypes": ["code_specs:class", "code_specs:interface"]}

            // Search facts and all code
            {"query": "Spring AI", "entityTypes": ["memory_objects", "code_specs"]}

            // Search everything
            {"query": "vector search", "entityTypes": ["memory_objects", "code_specs", "conversation_messages"]}
            ```

            **What is Reranking:**
            When useReranking=true (default), results are re-scored using ML model for unified relevance ranking across all sources.
        """.trimIndent()

    override val requestType: Class<UnifiedSearchRequest>
        get() = UnifiedSearchRequest::class.java

    override fun execute(request: UnifiedSearchRequest, context: ToolContext?): Map<String, Any>
}
