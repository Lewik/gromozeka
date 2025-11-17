package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation

/**
 * Repository for managing squash operation records.
 *
 * Squash operation represents AI-powered message consolidation - multiple messages
 * transformed into single summarized/restructured message. Immutable audit trail
 * for reproducibility and provenance tracking.
 *
 * @see Conversation.SquashOperation for domain model
 */
interface SquashOperationRepository {

    /**
     * Saves squash operation record.
     *
     * Operation ID must be set before calling (use uuid7() for time-based ordering).
     * Squash operations are immutable - calling save with existing ID throws exception.
     * This is a transactional operation.
     *
     * @param operation squash operation to save with all fields populated
     * @return saved operation (unchanged, for fluent API)
     * @throws IllegalArgumentException if operation.id is blank
     * @throws IllegalStateException if operation with this ID already exists (immutability violation)
     */
    suspend fun save(operation: Conversation.SquashOperation): Conversation.SquashOperation

    /**
     * Finds squash operation by unique identifier.
     *
     * @param id operation identifier
     * @return operation if found, null if doesn't exist
     */
    suspend fun findById(id: Conversation.SquashOperation.Id): Conversation.SquashOperation?

    /**
     * Finds squash operation that produced specified message.
     *
     * Returns the operation where resultMessageId equals specified message.
     * Used for provenance tracking - "how was this message created?"
     *
     * @param messageId result message to find operation for
     * @return operation if message is squash result, null if message wasn't produced by squash
     */
    suspend fun findByResultMessage(messageId: Conversation.Message.Id): Conversation.SquashOperation?

    /**
     * Finds all squash operations in conversation, ordered by creation time (newest first).
     *
     * Returns empty list if conversation has no squash operations or conversation doesn't exist.
     *
     * @param conversationId conversation to query
     * @return squash operations in descending createdAt order
     */
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.SquashOperation>
}
