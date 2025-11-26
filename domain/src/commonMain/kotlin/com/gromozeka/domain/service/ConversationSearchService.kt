package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Instant

/**
 * Service for searching across conversation history.
 *
 * Provides unified interface for finding messages across all conversations using
 * multiple search strategies (keyword, semantic, hybrid). Results include full context
 * (message, thread, conversation) and relevance scoring.
 *
 * Infrastructure layer implements this using SQL full-text search and vector similarity.
 *
 * @see SearchCriteria for search parameters
 * @see SearchResult for result structure
 */
interface ConversationSearchService {

    /**
     * Searches for messages matching criteria.
     *
     * Supports three search modes:
     * - KEYWORD: Exact/partial text matching via SQL (fast, precise)
     * - SEMANTIC: Vector similarity search (understands meaning, slower)
     * - HYBRID: Combines both with weighted scoring (best results, slowest)
     *
     * Results ordered by relevance score (descending). Empty query returns recent messages.
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
     * @property query search query text (required for KEYWORD/SEMANTIC, optional for browsing)
     * @property mode search strategy (KEYWORD, SEMANTIC, or HYBRID)
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
