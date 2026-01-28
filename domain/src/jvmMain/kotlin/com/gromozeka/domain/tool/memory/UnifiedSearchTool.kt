package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for unified_search tool.
 *
 * **Optional parameters:** Pass `null`, empty string `""`, or omit to disable filter.
 * Empty strings and blank strings are automatically treated as null (no filter applied).
 *
 * @property query The search query (required, non-empty)
 * @property entityTypes List of entity types - sources and symbol types to search (required, non-empty)
 * @property searchMode Search mode for ALL sources: KEYWORD, SEMANTIC, or HYBRID (optional, default: SEMANTIC - recommended)
 * @property limit Maximum number of results to return (optional, default: 5)
 * @property useReranking Whether to apply cross-source reranking (optional, default: true)
 * @property projectIds Optional list of project IDs to filter code specs and conversation messages (null/empty = current project only)
 * @property threadId Optional thread ID to filter conversation messages (null/empty/blank = no filter)
 * @property conversationIds Optional list of conversation IDs to filter conversation messages (null/empty = no filter)
 * @property roles Optional list of message roles to filter conversation messages (USER, ASSISTANT, SYSTEM) (null/empty = no filter)
 * @property dateFrom Optional start date to filter conversation messages (ISO 8601 format, null/empty/blank = no filter)
 * @property dateTo Optional end date to filter conversation messages (ISO 8601 format, null/empty/blank = no filter)
 *
 * @see SearchScope for valid entity type values
 */
data class UnifiedSearchRequest(
    val query: String,
    val entityTypes: List<SearchScope>,
    val searchMode: String? = null,
    val limit: Int? = null,
    val useReranking: Boolean? = null,
    val projectIds: List<String>? = null,
    val threadId: String? = null,
    val conversationIds: List<String>? = null,
    val roles: List<String>? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null
)

/**
 * Domain specification for unified search across knowledge graph, code, and conversations.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `unified_search`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Perform unified search across multiple data sources with configurable search modes.
 * **Default to SEMANTIC search** - it works best for natural language queries (conversation history, code docs, knowledge base).
 * Use HYBRID only for exact term matching (error codes, product IDs) - but note that scores may be compressed.
 *
 * ## Core Features
 *
 * **Multi-source search:**
 * - Knowledge graph entities (facts, concepts, people, technologies)
 * - Code symbols (classes, methods, functions, interfaces)
 * - Conversation history (past messages and context)
 *
 * **Configurable search strategy:**
 * - **SEMANTIC:** Vector similarity search for conceptual understanding (RECOMMENDED DEFAULT, best for natural language)
 * - **HYBRID:** Combined keyword + semantic (only for exact term matching like error codes, scores may be compressed)
 * - **KEYWORD:** BM25 fulltext search for exact term matching (rarely needed)
 * - **Graph traversal:** For connected entities (memory_objects only)
 * - **Cross-source reranking:** Unified relevance ranking across all sources
 *
 * **Flexible filtering:**
 * - Search specific entity types or all at once
 * - Filter by projects, conversations, threads
 * - Filter by message roles and date ranges
 * - Control result count with limit
 *
 * # Entity Types
 *
 * ## memory_objects
 *
 * Facts, concepts, people, technologies stored in knowledge graph.
 *
 * **Search methods:** Controlled by `searchMode` parameter
 * - SEMANTIC: Vector similarity search on entity embeddings (RECOMMENDED, default)
 * - HYBRID: Combined BM25 + Vector + Graph traversal (only for exact term matching)
 * - KEYWORD: BM25 fulltext search on entity names and summaries (rarely needed)
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
 * **Search methods:** Controlled by `searchMode` parameter
 * - SEMANTIC: Vector similarity search on KDoc embeddings (RECOMMENDED, default)
 * - HYBRID: Combined BM25 + Vector (only for exact term matching, no graph traversal)
 * - KEYWORD: BM25 fulltext search on symbol names and KDoc (rarely needed)
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
 * Historical conversation messages with multiple search modes.
 *
 * **Search methods:** Controlled by `searchMode` parameter
 * - SEMANTIC: Vector similarity search on message embeddings (RECOMMENDED, default - best for conversational queries)
 * - HYBRID: Combined BM25 + Vector (only for exact term matching like error codes, scores may be compressed)
 * - KEYWORD: BM25 fulltext search on message content (rarely needed)
 *
 * **Use when:**
 * - Recalling past discussions
 * - Finding context from previous conversations
 * - Answering "What did we discuss about X?"
 * - Finding exact terms or error messages in conversation history
 *
 * **Filtering:**
 * - By project (projectIds)
 * - By conversation (conversationIds)
 * - By thread (threadId)
 * - By message role (roles: USER, ASSISTANT, SYSTEM)
 * - By date range (dateFrom, dateTo)
 *
 * **Example results:**
 * - "We decided to use Neo4j for vector search because..."
 * - "The architecture follows Clean Architecture principles..."
 *
 * # Parameters
 *
 * **Optional parameters:** You can pass `null`, empty string `""`, or omit the parameter entirely.
 * All three approaches are equivalent - empty/blank strings are automatically treated as null (no filter).
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
 * **Examples:**
 * - `null`, `""` (empty), or omit - Search all threads (default)
 * - `"thread-123"` - Only messages from thread-123
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
 * Filter CODE_SPECS and CONVERSATION_MESSAGES results by project.
 *
 * **Valid values:**
 * - `null` or omit - Search current project only (default)
 * - `["project-id-1"]` - Search only specific project
 * - `["project-id-1", "project-id-2"]` - Search multiple projects
 *
 * **Default behavior (null):**
 * - Searches code specs from current project only
 * - Searches conversation messages from current project only
 * - Current project determined from tool context (current tab's project)
 * - Prevents cross-project pollution
 *
 * **Use cases:**
 * - Default (null): Search current project → `projectIds: null`
 * - Compare across projects → `projectIds: ["gromozeka-dev", "gromozeka-prod"]`
 *
 * **Important:** Memory objects (facts, concepts) are NOT filtered by project.
 * They remain global and searchable across all projects.
 *
 * ## searchMode: String? (optional, default: "SEMANTIC")
 *
 * Search mode for ALL entity types (memory_objects, code_specs, conversation_messages).
 * Controls search strategy across all sources.
 *
 * **Valid values:**
 * - `"KEYWORD"` - Fulltext search (BM25, fast, exact terms)
 * - `"SEMANTIC"` - Vector search (understands meaning, conceptual) - **RECOMMENDED DEFAULT**
 * - `"HYBRID"` - Combined keyword + semantic (for exact term matching, but scores may be compressed)
 *
 * **⚠️ IMPORTANT: Use SEMANTIC by default**
 *
 * **Why SEMANTIC is better for most queries:**
 * - Conversation history, code docs, and knowledge base contain rich natural language
 * - Semantic search understands intent and context (84% precision vs 78% for hybrid in benchmarks)
 * - No score compression issues (HYBRID combines incompatible score scales)
 * - Faster (one search instead of two parallel searches)
 * - Simpler and more maintainable
 *
 * **When to use HYBRID:**
 * - ONLY when you need exact keyword matching (error codes, product IDs, API endpoints)
 * - Example: "NullPointerException E4502" - need exact code match
 * - Example: "PostgreSQL VACUUM ANALYZE" - need exact SQL command
 * - ⚠️ Warning: HYBRID scores may be compressed (0.30-0.37 range) due to combining BM25 (unbounded) + Vector (0-1)
 *
 * **When to use KEYWORD:**
 * - Rarely needed - only for pure lexical matching without semantic understanding
 * - Example: searching for literal strings in code
 *
 * **Performance (per source):**
 * - SEMANTIC: 100-200ms (recommended)
 * - KEYWORD: 50-100ms (rarely needed)
 * - HYBRID: 150-300ms (only for exact term matching)
 *
 * **Examples:**
 * ```json
 * // Default: semantic search (recommended for most queries)
 * {"query": "how to implement authentication", "entityTypes": ["code_specs", "conversation_messages"], "searchMode": "SEMANTIC"}
 *
 * // Exact error code matching (use HYBRID)
 * {"query": "NullPointerException E4502", "entityTypes": ["conversation_messages"], "searchMode": "HYBRID"}
 *
 * // Conceptual search (use SEMANTIC)
 * {"query": "vector search implementation patterns", "entityTypes": ["memory_objects", "code_specs"], "searchMode": "SEMANTIC"}
 * ```
 *
 * ## conversationIds: List<String>? (optional)
 *
 * Filter conversation_messages by specific conversations. Only applies when searching conversation_messages.
 *
 * **Valid values:**
 * - `null` or omit - Search all conversations (default)
 * - `["conv-123"]` - Only conversation conv-123
 * - `["conv-123", "conv-456"]` - Multiple conversations
 *
 * **Use cases:**
 * - Search within specific discussion
 * - Compare related conversations
 * - Exclude unrelated conversations
 *
 * ## roles: List<String>? (optional)
 *
 * Filter conversation_messages by message roles. Only applies when searching conversation_messages.
 *
 * **Valid role values:**
 * - `"USER"` - Human user messages
 * - `"ASSISTANT"` - AI assistant responses
 * - `"SYSTEM"` - System notifications
 *
 * **Valid parameter values:**
 * - `null` or omit - All roles (default)
 * - `["USER"]` - Only user messages
 * - `["USER", "ASSISTANT"]` - User and assistant (exclude system)
 *
 * **Use cases:**
 * - Find what user asked about topic
 * - Review AI responses only
 * - Filter out system notifications
 *
 * ## dateFrom: String? (optional)
 *
 * Filter conversation_messages created after this timestamp (inclusive). Only applies when searching conversation_messages.
 *
 * **Format:** ISO 8601 timestamp (e.g., `"2024-01-15T10:30:00Z"`)
 *
 * **Examples:**
 * - `null`, `""` (empty), or omit - No lower bound (all past messages)
 * - `"2024-01-15T00:00:00Z"` - Messages from Jan 15, 2024 onwards
 *
 * **Use cases:**
 * - Search recent discussions only
 * - Find messages after specific event
 * - Limit search to recent timeframe
 *
 * ## dateTo: String? (optional)
 *
 * Filter conversation_messages created before this timestamp (inclusive). Only applies when searching conversation_messages.
 *
 * **Format:** ISO 8601 timestamp (e.g., `"2024-01-20T23:59:59Z"`)
 *
 * **Examples:**
 * - `null`, `""` (empty), or omit - No upper bound (includes present)
 * - `"2024-01-20T23:59:59Z"` - Messages until Jan 20, 2024
 *
 * **Use cases:**
 * - Search historical discussions
 * - Find messages before specific event
 * - Limit search to past timeframe
 *
 * **Combine with dateFrom:**
 * ```json
 * {
 *   "dateFrom": "2024-01-15T00:00:00Z",
 *   "dateTo": "2024-01-20T23:59:59Z"
 * }
 * ```
 * Searches messages within Jan 15-20, 2024 range.
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
 * ## Example 1: Find Code Implementation (Semantic Search)
 *
 * **Use case:** Agent needs to find where something is implemented
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "thread persistence repository",
 *     "entityTypes": ["code_specs"],
 *     "searchMode": "SEMANTIC",
 *     "limit": 5
 *   }
 * }
 * ```
 *
 * **Result:** Returns ThreadRepository, ConversationRepository, etc. using semantic understanding
 *
 * ## Example 2: Research Technology (Keyword Search)
 *
 * **Use case:** Agent needs to find exact mentions of technology
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "Spring AI",
 *     "entityTypes": ["memory_objects", "code_specs"],
 *     "searchMode": "KEYWORD"
 *   }
 * }
 * ```
 *
 * **Result:** Returns facts and code containing exact term "Spring AI"
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
 * ## Example 4: Comprehensive Search (Hybrid Mode)
 *
 * **Use case:** Agent needs all information about a topic
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "vector search Neo4j",
 *     "entityTypes": ["memory_objects", "code_specs", "conversation_messages"],
 *     "searchMode": "HYBRID",
 *     "limit": 15,
 *     "useReranking": true
 *   }
 * }
 * ```
 *
 * **Result:** Returns facts, code, and discussions using hybrid search - ranked by relevance across all sources
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
 * ## Example 7: Search Conversations with Keyword Mode
 *
 * **Use case:** Agent needs to find exact error message in conversation history
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "NullPointerException in ThreadRepository",
 *     "entityTypes": ["conversation_messages"],
 *     "searchMode": "KEYWORD",
 *     "limit": 10
 *   }
 * }
 * ```
 *
 * **Result:** Returns messages containing exact error text with BM25 ranking
 *
 * ## Example 8: Filter Conversations by Date and Role
 *
 * **Use case:** Agent reviews recent user questions about topic
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "authentication implementation",
 *     "entityTypes": ["conversation_messages"],
 *     "searchMode": "HYBRID",
 *     "roles": ["USER"],
 *     "dateFrom": "2024-01-15T00:00:00Z",
 *     "limit": 20
 *   }
 * }
 * ```
 *
 * **Result:** Returns only USER messages about authentication from Jan 15 onwards
 *
 * ## Example 9: Search Across All Sources with Conversation Filters
 *
 * **Use case:** Agent needs comprehensive view with filtered conversations
 *
 * ```json
 * {
 *   "tool": "unified_search",
 *   "parameters": {
 *     "query": "repository pattern",
 *     "entityTypes": ["memory_objects", "code_specs", "conversation_messages"],
 *     "searchMode": "HYBRID",
 *     "projectIds": ["current-project-id"],
 *     "roles": ["USER", "ASSISTANT"],
 *     "limit": 30,
 *     "useReranking": true
 *   }
 * }
 * ```
 *
 * **Result:** Returns facts, code, and conversations (USER+ASSISTANT only) from current project, ranked by relevance
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
            Unified search across knowledge graph, code, and conversation history with configurable search modes.

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

            **Search Modes (applies to ALL sources):**
            - KEYWORD: Fulltext search (BM25) - fast, exact terms
            - SEMANTIC: Vector search - understands meaning, conceptual
            - HYBRID: Combined keyword + semantic - best results (default)

            **Parameters:**
            - query: Search query - natural language ("how authentication works") or exact names ("ThreadRepository")
            - entityTypes: List of entity types to search (required)
            - searchMode: KEYWORD, SEMANTIC, or HYBRID (default: HYBRID, applies to all sources)
            - limit: Max results (default: 5)
            - projectIds: Filter by projects (code_specs and conversation_messages)
            - conversationIds: Filter by conversations (conversation_messages only)
            - threadId: Filter by thread (conversation_messages only)
            - roles: Filter by message roles - USER, ASSISTANT, SYSTEM (conversation_messages only)
            - dateFrom/dateTo: Filter by date range (conversation_messages only)
            - useReranking: Cross-source relevance ranking (default: true)

            **Examples:**
            ```json
            // Keyword search across all sources
            {"query": "NullPointerException", "entityTypes": ["memory_objects", "code_specs", "conversation_messages"], "searchMode": "KEYWORD"}

            // Semantic search for code
            {"query": "thread persistence", "entityTypes": ["code_specs"], "searchMode": "SEMANTIC"}

            // Hybrid search (default, best results)
            {"query": "authentication implementation", "entityTypes": ["code_specs", "conversation_messages"], "searchMode": "HYBRID"}
            
            // Search recent user messages
            {"query": "authentication", "entityTypes": ["conversation_messages"], "roles": ["USER"], "dateFrom": "2024-01-15T00:00:00Z"}
            ```

            **What is Reranking:**
            When useReranking=true (default), results are re-scored using ML model for unified relevance ranking across all sources.
        """.trimIndent()

    override val requestType: Class<UnifiedSearchRequest>
        get() = UnifiedSearchRequest::class.java

    override fun execute(request: UnifiedSearchRequest, context: ToolContext?): Map<String, Any>
}
