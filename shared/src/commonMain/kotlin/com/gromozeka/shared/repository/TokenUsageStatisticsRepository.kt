package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.TokenUsageStatistics

/**
 * Repository for managing AI token usage statistics.
 *
 * Handles persistence and aggregation of token consumption data for
 * cost estimation, performance monitoring, and usage analytics.
 *
 * Each record tracks token usage for a single AI turn (request/response cycle).
 * Statistics include prompt tokens, completion tokens, cache usage, and thinking tokens.
 */
interface TokenUsageStatisticsRepository {
    /**
     * Saves token usage statistics for a turn.
     *
     * Records token consumption for single AI request/response cycle.
     * Statistics ID must be set before calling (use uuid7() for time-based ordering).
     *
     * This is a transactional operation.
     *
     * @param stats statistics to save (with all required fields)
     */
    suspend fun save(stats: TokenUsageStatistics)

    /**
     * Retrieves aggregated token statistics for entire thread.
     *
     * Calculates totals across all turns in the thread:
     * - Total prompt/completion/cache/thinking tokens
     * - Most recent turn statistics
     * - Recent calls for trend analysis
     *
     * Useful for cost estimation and context window monitoring.
     *
     * @param threadId thread to aggregate statistics for
     * @return aggregated statistics (with zero totals if thread has no statistics)
     */
    suspend fun getThreadTotals(threadId: Conversation.Thread.Id): TokenUsageStatistics.ThreadTotals

    /**
     * Retrieves most recent turn statistics for thread.
     *
     * Returns statistics ordered by timestamp (most recent first).
     * Used for trend analysis and recent usage monitoring.
     *
     * @param threadId thread to query
     * @param limit maximum number of statistics records to return (default: 10)
     * @return recent statistics (may be shorter than limit if thread has fewer turns)
     */
    suspend fun getRecentCalls(threadId: Conversation.Thread.Id, limit: Int = 10): List<TokenUsageStatistics>
}
