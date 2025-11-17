package com.gromozeka.bot.domain.repository

import com.gromozeka.bot.domain.model.Conversation
import com.gromozeka.bot.domain.model.TokenUsageStatistics

/**
 * Repository for managing token usage statistics.
 *
 * Tracks LLM token consumption per API call for cost monitoring and optimization.
 * Statistics are append-only (never updated or deleted).
 *
 * @see TokenUsageStatistics for domain model
 */
interface TokenUsageStatisticsRepository {

    /**
     * Saves token usage statistics for single API call.
     *
     * Statistics are immutable - always creates new record, never updates.
     * This is a transactional operation.
     *
     * @param stats statistics to save with all fields populated
     * @throws IllegalArgumentException if required fields are missing
     */
    suspend fun save(stats: TokenUsageStatistics)

    /**
     * Gets aggregated token totals for thread.
     *
     * Sums input/output/total tokens across all API calls in thread.
     * Returns zero totals if thread has no statistics.
     *
     * @param threadId thread to aggregate statistics for
     * @return aggregated totals (never null, may have zero values)
     */
    suspend fun getThreadTotals(threadId: Conversation.Thread.Id): TokenUsageStatistics.ThreadTotals

    /**
     * Gets recent API call statistics for thread, ordered by timestamp (newest first).
     *
     * Returns empty list if thread has no statistics.
     *
     * @param threadId thread to query
     * @param limit maximum number of records to return (default: 10)
     * @return recent statistics up to specified limit
     */
    suspend fun getRecentCalls(threadId: Conversation.Thread.Id, limit: Int = 10): List<TokenUsageStatistics>
}
