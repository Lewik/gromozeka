package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

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
    @Serializable
    @JvmInline
    value class Id(val value: String)

    val totalTokens: Int
        get() = promptTokens + completionTokens

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
        val totalTokens: Int
            get() = totalPromptTokens + totalCompletionTokens
    }
}
