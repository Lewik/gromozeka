package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import kotlinx.datetime.Instant

/**
 * [SPECIFICATION] Repository for searching conversation messages in Neo4j.
 *
 * Low-level repository for message search operations using Neo4j indexes.
 * Infrastructure layer implements this using Neo4j vector and fulltext indexes.
 *
 * ## Storage Schema
 *
 * **ConversationMessage nodes:**
 * ```cypher
 * (:ConversationMessage {
 *   id: String,                    // Message.Id
 *   conversationId: String,        // Conversation.Id
 *   threadId: String,              // Thread.Id
 *   projectId: String,             // Project.Id (denormalized for filtering)
 *   role: String,                  // "USER" | "ASSISTANT" | "SYSTEM"
 *   content: String,               // Extracted text content
 *   embedding: List<Float>,        // Vector embedding (3072 dimensions)
 *   createdAt: DateTime            // Message creation timestamp
 * })
 * ```
 *
 * **Indexes:**
 * - `conversation_message_vector` - HNSW vector index on `embedding` (cosine similarity)
 * - `conversation_message_fulltext` - Fulltext index on `content` (BM25 ranking)
 *
 * ## Search Methods
 *
 * **Keyword search:**
 * - Uses Neo4j fulltext index (BM25 ranking)
 * - Fast exact/partial text matching
 * - Supports phrase queries, wildcards, boolean operators
 *
 * **Semantic search:**
 * - Uses Neo4j vector index (HNSW + cosine similarity)
 * - Understands conceptual meaning
 * - Requires query embedding as input
 *
 * **Hybrid search:**
 * - Combines keyword + semantic results
 * - Weighted score combination (configurable weights)
 * - Best overall results
 *
 * ## Filtering
 *
 * All search methods support filtering by:
 * - projectIds - filter by projects
 * - conversationIds - filter by conversations
 * - threadIds - filter by threads
 * - roles - filter by message roles
 * - dateFrom/dateTo - filter by creation timestamp
 *
 * Filters applied via Cypher WHERE clauses (indexed, efficient).
 *
 * @see com.gromozeka.infrastructure.db.memory.graph.ConversationMessageSearchRepositoryImpl
 */
interface ConversationMessageSearchRepository {

    /**
     * Keyword search using Neo4j fulltext index (BM25).
     *
     * Fast exact/partial text matching. Supports:
     * - Phrase queries: `"Clean Architecture"`
     * - Boolean operators: `kotlin AND coroutines`
     * - Wildcards: `auth*`
     *
     * **Performance:** 50-100ms
     *
     * @param query search query text (fulltext query syntax)
     * @param filters optional filters (projects, conversations, threads, roles, dates)
     * @param limit maximum results (default 20)
     * @param offset skip first N results (default 0)
     * @return list of search results with BM25 scores
     */
    suspend fun keywordSearch(
        query: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 20,
        offset: Int = 0
    ): List<MessageSearchResult>

    /**
     * Semantic search using Neo4j vector index (HNSW + cosine similarity).
     *
     * Finds conceptually similar messages using vector embeddings.
     * Query must be pre-embedded using same embedding model (text-embedding-3-large).
     *
     * **Performance:** 100-200ms
     *
     * @param queryEmbedding pre-computed query embedding (3072 dimensions)
     * @param filters optional filters (projects, conversations, threads, roles, dates)
     * @param limit maximum results (default 20)
     * @param offset skip first N results (default 0)
     * @return list of search results with cosine similarity scores
     */
    suspend fun semanticSearch(
        queryEmbedding: List<Float>,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 20,
        offset: Int = 0
    ): List<MessageSearchResult>

    /**
     * Hybrid search combining keyword + semantic search.
     *
     * Executes both keyword and semantic search, then combines results with weighted scoring.
     *
     * **Performance:** 150-300ms
     *
     * @param query search query text (for keyword search)
     * @param queryEmbedding pre-computed query embedding (for semantic search)
     * @param filters optional filters (projects, conversations, threads, roles, dates)
     * @param keywordWeight weight for keyword score (default 0.3)
     * @param semanticWeight weight for semantic score (default 0.7)
     * @param limit maximum results (default 20)
     * @param offset skip first N results (default 0)
     * @return list of search results with combined scores
     */
    suspend fun hybridSearch(
        query: String,
        queryEmbedding: List<Float>,
        filters: SearchFilters = SearchFilters(),
        keywordWeight: Double = 0.3,
        semanticWeight: Double = 0.7,
        limit: Int = 20,
        offset: Int = 0
    ): List<MessageSearchResult>

    /**
     * Save or update conversation message in Neo4j.
     *
     * Creates or updates ConversationMessage node with all properties and embedding.
     * Denormalizes projectId from conversation for efficient filtering.
     *
     * **Transactionality:** Single write transaction per message
     *
     * @param message message node to save
     */
    suspend fun saveMessage(message: ConversationMessageNode)

    /**
     * Delete conversation message from Neo4j.
     *
     * Removes ConversationMessage node and all its relationships.
     *
     * **Transactionality:** Single write transaction
     *
     * @param messageId message identifier to delete
     */
    suspend fun deleteMessage(messageId: Conversation.Message.Id)

    /**
     * Initialize Neo4j indexes for conversation messages.
     *
     * Creates:
     * - Vector index (HNSW) on embedding property
     * - Fulltext index (BM25) on content property
     *
     * Idempotent - safe to call multiple times (IF NOT EXISTS).
     *
     * **Should be called:** On application startup (via initializer)
     */
    suspend fun initializeIndexes()

    /**
     * Search filters for conversation messages.
     *
     * All filters are optional and combinable.
     * Null values mean "no filter" (include all).
     *
     * @property projectIds filter by specific projects (null = all projects)
     * @property conversationIds filter by specific conversations (null = all conversations)
     * @property threadIds filter by specific threads (null = all threads)
     * @property roles filter by message roles (null = all roles)
     * @property dateFrom filter messages created after this timestamp (inclusive, null = no lower bound)
     * @property dateTo filter messages created before this timestamp (inclusive, null = no upper bound)
     */
    data class SearchFilters(
        val projectIds: List<Project.Id>? = null,
        val conversationIds: List<Conversation.Id>? = null,
        val threadIds: List<Conversation.Thread.Id>? = null,
        val roles: List<Conversation.Message.Role>? = null,
        val dateFrom: Instant? = null,
        val dateTo: Instant? = null,
    )

    /**
     * Conversation message node for Neo4j storage.
     *
     * Denormalized representation optimized for search.
     * projectId denormalized from conversation for efficient filtering.
     *
     * @property id message identifier (Message.Id)
     * @property conversationId conversation identifier (Conversation.Id)
     * @property threadId thread identifier (Thread.Id)
     * @property projectId project identifier (Project.Id, denormalized)
     * @property role message role (USER, ASSISTANT, SYSTEM)
     * @property content extracted text content (from UserMessage/AssistantMessage)
     * @property embedding vector embedding (3072 dimensions, OpenAI text-embedding-3-large)
     * @property createdAt message creation timestamp
     */
    data class ConversationMessageNode(
        val id: Conversation.Message.Id,
        val conversationId: Conversation.Id,
        val threadId: Conversation.Thread.Id,
        val projectId: Project.Id,
        val role: Conversation.Message.Role,
        val content: String,
        val embedding: List<Float>,
        val createdAt: Instant,
    )

    /**
     * Message search result.
     *
     * Contains message identifiers, content, and relevance score.
     * Application layer enriches this with full Message/Thread/Conversation entities.
     *
     * @property id message identifier
     * @property conversationId conversation identifier
     * @property threadId thread identifier
     * @property projectId project identifier
     * @property role message role
     * @property content message text content
     * @property score relevance score (0.0 to 1.0, higher = more relevant)
     * @property createdAt message creation timestamp
     * @property highlights text snippets with match highlights (keyword search only, empty for semantic)
     */
    data class MessageSearchResult(
        val id: Conversation.Message.Id,
        val conversationId: Conversation.Id,
        val threadId: Conversation.Thread.Id,
        val projectId: Project.Id,
        val role: Conversation.Message.Role,
        val content: String,
        val score: Double,
        val createdAt: Instant,
        val highlights: List<Highlight> = emptyList(),
    )

    /**
     * Highlighted text snippet showing query match.
     *
     * Only populated for keyword search (fulltext index provides highlights).
     * Semantic search returns empty highlights list.
     *
     * @property snippet text excerpt with match highlighted (e.g., "...found <mark>kotlin</mark> example...")
     * @property matchStart character offset where match starts in original content
     * @property matchEnd character offset where match ends in original content
     */
    data class Highlight(
        val snippet: String,
        val matchStart: Int,
        val matchEnd: Int,
    )
}
