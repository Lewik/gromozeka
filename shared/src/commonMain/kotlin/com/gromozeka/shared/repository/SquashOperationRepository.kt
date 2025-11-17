package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation

/**
 * Repository for managing squash operation records.
 *
 * Handles persistence of immutable squash operation records that track
 * AI-powered message consolidation (summarization, distillation, restructuring).
 *
 * Squash operations provide:
 * - Audit trail: Know what messages were squashed and when
 * - Reproducibility: Can reproduce squash with same prompt/model
 * - Undo support: Can restore original messages if needed
 *
 * Records are immutable once created - squash operations cannot be modified.
 */
interface SquashOperationRepository {
    /**
     * Saves new squash operation record.
     *
     * Operation ID must be set before calling (use uuid7() for time-based ordering).
     * Squash operations are immutable - this method creates new records only, no updates.
     *
     * This is a transactional operation.
     *
     * @param operation squash operation to save (with all required fields)
     * @return saved operation (same as input)
     */
    suspend fun save(operation: Conversation.SquashOperation): Conversation.SquashOperation

    /**
     * Finds squash operation by unique identifier.
     *
     * @param id squash operation identifier
     * @return operation if found, null if operation doesn't exist
     */
    suspend fun findById(id: Conversation.SquashOperation.Id): Conversation.SquashOperation?

    /**
     * Finds squash operation that created specific message.
     *
     * Searches for operation where resultMessageId matches the input.
     * Each result message is created by at most one squash operation.
     *
     * @param messageId message to find squash operation for
     * @return squash operation if message was created by squash, null if message is not a squash result
     */
    suspend fun findByResultMessage(messageId: Conversation.Message.Id): Conversation.SquashOperation?

    /**
     * Finds all squash operations in a conversation.
     *
     * Returns operations ordered by creation time (oldest first).
     * Includes all squash operations regardless of whether result messages still exist.
     *
     * @param conversationId conversation to query
     * @return list of squash operations (empty list if conversation has no squash operations)
     */
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.SquashOperation>
}
