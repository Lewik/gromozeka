package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import kotlinx.datetime.Instant

/**
 * [SPECIFICATION] Search across conversation message history.
 *
 * Provides unified interface for finding messages across all conversations using
 * multiple search strategies (keyword, semantic, hybrid). Results include full context
 * (message, thread, conversation) and relevance scoring.
 *
 * ## Implementation Strategy
 *
 * **Infrastructure layer implements this using:**
 * - Neo4j fulltext index for KEYWORD search (BM25 ranking)
 * - Neo4j vector index for SEMANTIC search (cosine similarity)
 * - Weighted score combination for HYBRID search
 *
 * **Vectorization:**
 * - Only USER and ASSISTANT text messages vectorized
 * - Tool calls, tool results, thinking blocks NOT vectorized
 * - Uses OpenAI text-embedding-3-large (3072 dimensions)
 *
 * **Storage:**
 * - Messages stored as ConversationMessage nodes in Neo4j
 * - Properties: id, content, threadId, role, embedding, createdAt
 * - Indexes: vector index (HNSW), fulltext index (BM25)
 *
 * ## Search Modes
 *
 * **KEYWORD:**
 * - Fulltext search via Neo4j BM25 index
 * - Fast (50-100ms), precise word matching
 * - Good for: exact terms, code snippets, error messages
 *
 * **SEMANTIC:**
 * - Vector similarity via Neo4j HNSW index
 * - Slower (100-200ms), understands meaning
 * - Good for: natural language, conceptual queries
 *
 * **HYBRID:**
 * - Combines KEYWORD + SEMANTIC with weighted scoring
 * - Slowest (150-300ms), best overall results
 * - Good for: general-purpose search, exploratory queries
 *
 * ## Filtering
 *
 * All filters are optional and combinable:
 * - **projectIds** - filter by projects (null = all projects)
 * - **conversationIds** - filter by conversations (null = all conversations)
 * - **threadIds** - filter by threads (null = all threads)
 * - **roles** - filter by message roles (null = all roles)
 * - **dateFrom/dateTo** - filter by creation timestamp (null = no bounds)
 *
 * ## Result Structure
 *
 * Each result includes:
 * - **message** - full Message entity with all content
 * - **thread** - Thread entity containing message
 * - **conversation** - Conversation entity containing thread
 * - **score** - relevance score (0.0 to 1.0, higher = more relevant)
 * - **highlights** - text snippets showing where query matched (KEYWORD mode only)
 * - **matchType** - how result was found (KEYWORD, SEMANTIC, or HYBRID)
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Search for messages about "authentication" in last week
 * val results = conversationSearchService.search(
 *     SearchCriteria(
 *         query = "authentication implementation",
 *         mode = SearchMode.HYBRID,
 *         dateFrom = Clock.System.now() - 7.days,
 *         roles = listOf(Role.USER, Role.ASSISTANT),
 *         limit = 20
 *     )
 * )
 *
 * // Browse recent messages in specific project
 * val recent = conversationSearchService.search(
 *     SearchCriteria(
 *         query = "",
 *         mode = SearchMode.KEYWORD,
 *         projectIds = listOf(projectId),
 *         limit = 50
 *     )
 * )
 * ```
 *
 * @see SearchCriteria for search parameters
 * @see SearchResult for result structure
 */
interface ConversationSearchService {

    /**
     * Searches for messages matching criteria.
     *
     * Supports three search modes:
     * - KEYWORD: Fulltext matching via Neo4j BM25 index (fast, precise)
     * - SEMANTIC: Vector similarity via Neo4j HNSW index (understands meaning, slower)
     * - HYBRID: Combines both with weighted scoring (best results, slowest)
     *
     * Results ordered by relevance score (descending).
     * Empty query with KEYWORD mode returns recent messages (ordered by createdAt DESC).
     *
     * **Performance:**
     * - KEYWORD: 50-100ms
     * - SEMANTIC: 100-200ms
     * - HYBRID: 150-300ms
     *
     * **Transactionality:** Read-only, no side effects
     *
     * @param criteria search parameters (query, filters, pagination)
     * @return paginated search results with metadata
     */
    suspend fun search(criteria: SearchCriteria): SearchResultPage

    /**
     * Search criteria for conversation history.
     *
     * Flexible filtering across multiple dimensions. All filters are optional and combinable.
     *
     * @property query search query text (required for SEMANTIC/HYBRID, optional for KEYWORD browsing)
     * @property mode search strategy (KEYWORD, SEMANTIC, or HYBRID)
     * @property projectIds filter by specific projects (null = all projects)
     * @property conversationIds filter by specific conversations (null = all conversations)
     * @property threadIds filter by specific threads (null = all threads)
     * @property roles filter by message roles (null = all roles)
     * @property dateFrom filter messages created after this timestamp (inclusive, null = no lower bound)
     * @property dateTo filter messages created before this timestamp (inclusive, null = no upper bound)
     * @property limit maximum results per page (default 20, max 100)
     * @property offset skip first N results for pagination (default 0)
     */
    data class SearchCriteria(
        val query: String = "",
        val mode: SearchMode = SearchMode.HYBRID,

        val projectIds: List<Project.Id>? = null,
        val conversationIds: List<Conversation.Id>? = null,
        val threadIds: List<Conversation.Thread.Id>? = null,
        val roles: List<Conversation.Message.Role>? = null,

        val dateFrom: Instant? = null,
        val dateTo: Instant? = null,

        val limit: Int = 20,
        val offset: Int = 0,
    ) {
        init {
            require(limit in 1..100) { "Limit must be between 1 and 100, got $limit" }
            require(offset >= 0) { "Offset must be non-negative, got $offset" }
            require(query.isNotBlank() || mode == SearchMode.KEYWORD) {
                "Query required for SEMANTIC/HYBRID search modes"
            }
        }
    }

    /**
     * Search strategy mode.
     *
     * Different modes provide trade-offs between speed, precision, and recall.
     */
    enum class SearchMode {
        /**
         * Keyword/exact text matching via SQL.
         *
         * Fast, precise, but requires exact words/phrases.
         * Good for: finding specific terms, code snippets, error messages.
         */
        KEYWORD,

        /**
         * Semantic similarity via vector embeddings.
         *
         * Understands meaning, finds conceptually related content.
         * Good for: natural language queries, finding related discussions.
         */
        SEMANTIC,

        /**
         * Combines keyword and semantic search with weighted scoring.
         *
         * Best overall results but slower. Balances precision and recall.
         * Good for: general-purpose search, exploratory queries.
         */
        HYBRID
    }

    /**
     * Paginated search results.
     *
     * @property results matching messages with context and scoring
     * @property totalCount total number of matches (optional, may be expensive to compute)
     * @property hasMore true if more results available beyond current page
     */
    data class SearchResultPage(
        val results: List<SearchResult>,
        val totalCount: Int? = null,
        val hasMore: Boolean,
    )

    /**
     * Single search result with full context.
     *
     * Contains found message, its containing thread/conversation, and match metadata.
     *
     * @property message found message
     * @property thread thread containing this message
     * @property conversation conversation containing this thread
     * @property score relevance score (0.0 to 1.0, higher = more relevant)
     * @property highlights text snippets showing where query matched
     * @property matchType how this result was found (KEYWORD, SEMANTIC, or HYBRID)
     */
    data class SearchResult(
        val message: Conversation.Message,
        val thread: Conversation.Thread,
        val conversation: Conversation,
        val score: Double,
        val highlights: List<Highlight>,
        val matchType: SearchMode,
    )

    /**
     * Highlighted text snippet showing query match.
     *
     * @property field which message field matched (content, role, etc.)
     * @property snippet text excerpt with match highlighted (e.g., "...found <mark>kotlin coroutines</mark> example...")
     * @property matchStart character offset where match starts in original content
     * @property matchEnd character offset where match ends in original content
     */
    data class Highlight(
        val field: String,
        val snippet: String,
        val matchStart: Int,
        val matchEnd: Int,
    )
}
