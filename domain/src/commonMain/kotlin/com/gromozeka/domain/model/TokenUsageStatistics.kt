package com.gromozeka.domain.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Token usage statistics for single LLM API call.
 *
 * Tracks token consumption per API call for cost estimation and context window monitoring.
 * Supports prompt caching and extended thinking.
 *
 * Uses immutable thread + message snapshot to reliably track which messages were included
 * in the API call. Since threads are immutable (edits create new threads), the combination
 * of threadId + lastMessageId uniquely identifies the conversation state at API call time.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique statistics record identifier (UUIDv7)
 * @property threadId conversation thread this call belongs to
 * @property lastMessageId last message in thread when API call was made (identifies snapshot)
 * @property timestamp when API call was made
 * @property promptTokens tokens in prompt (user messages + system prompt + context)
 * @property completionTokens tokens in assistant response
 * @property cacheCreationTokens tokens written to prompt cache (for repeated context)
 * @property cacheReadTokens tokens read from prompt cache (cost reduction)
 * @property thinkingTokens extended thinking tokens (Claude extended thinking mode)
 * @property provider AI provider name (e.g., "OPENAI", "ANTHROPIC", "GOOGLE", "OLLAMA")
 * @property modelId LLM model identifier (e.g., "claude-3-5-sonnet-20241022")
 */
@Serializable
data class TokenUsageStatistics(
    val id: Id,
    val threadId: Conversation.Thread.Id? = null,
    val lastMessageId: Conversation.Message.Id? = null,
    val timestamp: Instant,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheCreationTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val provider: String,
    val modelId: String,
    val userId: User.Id? = null,
    val projectId: Project.Id? = null,
    val agentDefinitionId: AgentDefinition.Id? = null,
    val conversationId: Conversation.Id? = null,
    val runtimePurpose: String = "UNSPECIFIED",
    val executionTarget: String = "SERVER",
    val connectionKind: String = provider,
    val connectionId: String = "unknown",
    val modelConfigurationId: String = modelId,
    val contextInputTokens: Int? = null,
    val price: PriceSnapshot? = null,
) {
    /**
     * Unique statistics record identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    data class PriceSnapshot(
        val catalogVersion: String,
        val effectiveAt: Instant,
        val inputNanoUsdPerMillion: Long,
        val cacheCreationNanoUsdPerMillion: Long,
        val cacheReadNanoUsdPerMillion: Long,
        val outputNanoUsdPerMillion: Long,
        val estimatedCostNanoUsd: Long,
    )

    val totalInputTokens: Int
        get() = promptTokens + cacheCreationTokens + cacheReadTokens

    val totalOutputTokens: Int
        get() = completionTokens + thinkingTokens

    /**
     * Total tokens consumed by the provider call.
     */
    val totalTokens: Int
        get() = totalInputTokens + totalOutputTokens

    /**
     * Aggregated token statistics for entire thread.
     *
     * Used for displaying total conversation cost and context usage.
     *
     * @property totalPromptTokens sum of all prompt tokens across all turns
     * @property totalCompletionTokens sum of all completion tokens across all turns
     * @property totalCacheReadTokens sum of all cache read tokens (cost savings)
     * @property totalCacheCreationTokens sum of all cache creation tokens
     * @property totalThinkingTokens sum of all extended thinking tokens
     * @property lastCallTokens token count from most recent API call (null if no calls yet)
     * @property recentCalls list of recent statistics records for history display
     * @property currentContextSize estimated current context window usage in tokens
     * @property contextWindowTokens known context window for latest provider/model, if configured
     * @property provider AI provider used in most recent call (null if no calls yet)
     * @property modelId model used in most recent call (null if no calls yet)
     */
    @Serializable
    data class ThreadTotals(
        val totalPromptTokens: Int,
        val totalCompletionTokens: Int,
        val totalCacheReadTokens: Int,
        val totalCacheCreationTokens: Int,
        val totalThinkingTokens: Int,
        val lastCallTokens: Int?,
        val recentCalls: List<TokenUsageStatistics>,
        val currentContextSize: Int? = null,
        val reportedContextSize: Int? = null,
        val contextStatus: ContextStatus = ContextStatus.UNAVAILABLE,
        val contextWindowTokens: Int? = null,
        val provider: String? = null,
        val modelId: String? = null
    ) {
        /**
         * Total tokens consumed across all turns.
         */
        val totalTokens: Int
            get() = totalPromptTokens + totalCompletionTokens +
                totalCacheCreationTokens + totalCacheReadTokens + totalThinkingTokens
    }

    @Serializable
    enum class ContextStatus {
        AVAILABLE,
        UNAVAILABLE,
        OUT_OF_RANGE,
    }

    @Serializable
    data class ReportQuery(
        val from: Instant? = null,
        val to: Instant? = null,
        val provider: String? = null,
        val modelId: String? = null,
        val projectId: Project.Id? = null,
        val agentDefinitionId: AgentDefinition.Id? = null,
        val conversationId: Conversation.Id? = null,
        val runtimePurpose: String? = null,
        val recentCallLimit: Int = 50,
    )

    @Serializable
    data class Totals(
        val callCount: Int = 0,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val cacheCreationTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val thinkingTokens: Long = 0,
        val estimatedCostNanoUsd: Long = 0,
        val pricedCallCount: Int = 0,
        val unpricedCallCount: Int = 0,
    ) {
        val totalInputTokens: Long
            get() = promptTokens + cacheCreationTokens + cacheReadTokens

        val totalOutputTokens: Long
            get() = completionTokens + thinkingTokens
    }

    @Serializable
    data class Breakdown(
        val key: String,
        val totals: Totals,
    )

    @Serializable
    data class Report(
        val query: ReportQuery,
        val totals: Totals,
        val byProviderAndModel: List<Breakdown>,
        val byProject: List<Breakdown>,
        val byAgent: List<Breakdown>,
        val byConversation: List<Breakdown>,
        val byRuntimePurpose: List<Breakdown>,
        val recentCalls: List<TokenUsageStatistics>,
    )
}
