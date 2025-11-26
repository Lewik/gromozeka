package com.gromozeka.domain.service

/**
 * Service for vector-based memory operations.
 *
 * Provides semantic search and storage for conversation messages using vector embeddings.
 * Infrastructure layer implements this using Qdrant or other vector databases.
 *
 * Vector memory enables semantic similarity search across conversation history.
 */
interface VectorMemoryService {
    /**
     * Stores thread messages in vector database.
     *
     * Loads messages from thread, filters relevant content, generates embeddings, and stores.
     * Only USER and ASSISTANT messages without tool calls are stored.
     * This is a transactional operation.
     *
     * @param threadId thread identifier to remember
     */
    suspend fun rememberThread(threadId: String)

    /**
     * Searches for semantically similar messages (recall from memory).
     *
     * Uses vector similarity to find relevant messages across conversations.
     * Results ordered by relevance score (descending).
     *
     * @param query search query text
     * @param threadId optional filter by specific thread
     * @param limit maximum number of results (default 5)
     * @return list of memory entries with relevance scores
     */
    suspend fun recall(
        query: String,
        threadId: String? = null,
        limit: Int = 5
    ): List<Memory>

    /**
     * Removes message from vector memory.
     *
     * Deletes message embeddings from vector database.
     * This is a transactional operation.
     *
     * @param messageId message identifier to forget
     */
    suspend fun forgetMessage(messageId: String)

    /**
     * Memory entry from vector search.
     *
     * @property content message text content
     * @property messageId original message identifier
     * @property threadId thread containing this message
     * @property score similarity score (0.0 to 1.0, higher is more similar)
     */
    data class Memory(
        val content: String,
        val messageId: String,
        val threadId: String,
        val score: Double
    )
}
