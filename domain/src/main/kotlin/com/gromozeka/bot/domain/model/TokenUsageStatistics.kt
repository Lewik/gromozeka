package com.gromozeka.bot.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Token usage statistics for single LLM API call.
 *
 * Tracks token consumption per conversation turn for cost estimation
 * and context window monitoring. Supports prompt caching and extended thinking.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique statistics record identifier (UUIDv7)
 * @property threadId conversation thread this call belongs to
 * @property turnNumber sequential turn number in thread (user + assistant = 1 turn)
 * @property timestamp when API call was made
 * @property promptTokens tokens in prompt (user messages + system prompt + context)
 * @property completionTokens tokens in assistant response
 * @property cacheCreationTokens tokens written to prompt cache (for repeated context)
 * @property cacheReadTokens tokens read from prompt cache (cost reduction)
 * @property thinkingTokens extended thinking tokens (Claude extended thinking mode)
 * @property modelId LLM model identifier (e.g., "claude-3-5-sonnet-20241022")
 */
@Serializable
data class TokenUsageStatistics(
    val id: Id,
    val threadId: Conversation.Thread.Id,
    val turnNumber: Int,
    val timestamp: Instant,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheCreationTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val modelId: String
) {
    /**
     * Unique statistics record identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Total tokens consumed (prompt + completion, excluding cache operations).
     */
    val totalTokens: Int
        get() = promptTokens + completionTokens

    /**
     * Aggregated token statistics for entire thread.
     *
     * Used for displaying total conversation cost and context usage.
     *
     * @property totalPromptTokens sum of all prompt tokens across all turns
     * @property totalCompletionTokens sum of all completion tokens across all turns
     * @property totalCacheReadTokens sum of all cache read tokens (cost savings)
     * @property totalThinkingTokens sum of all extended thinking tokens
     * @property lastCallTokens token count from most recent API call (null if no calls yet)
     * @property recentCalls list of recent statistics records for history display
     * @property currentContextSize estimated current context window usage in tokens
     * @property modelId model used in most recent call (null if no calls yet)
     */
    @Serializable
    data class ThreadTotals(
        val totalPromptTokens: Int,
        val totalCompletionTokens: Int,
        val totalCacheReadTokens: Int,
        val totalThinkingTokens: Int,
        val lastCallTokens: Int?,
        val recentCalls: List<TokenUsageStatistics>,
        val currentContextSize: Int? = null,
        val modelId: String? = null
    ) {
        /**
         * Total tokens consumed across all turns (prompt + completion).
         */
        val totalTokens: Int
            get() = totalPromptTokens + totalCompletionTokens
    }
}
