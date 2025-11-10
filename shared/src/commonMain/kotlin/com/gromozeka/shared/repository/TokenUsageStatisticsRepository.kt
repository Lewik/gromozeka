package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.TokenUsageStatistics

interface TokenUsageStatisticsRepository {
    suspend fun save(stats: TokenUsageStatistics)
    suspend fun getThreadTotals(threadId: Conversation.Thread.Id): TokenUsageStatistics.ThreadTotals
    suspend fun getRecentCalls(threadId: Conversation.Thread.Id, limit: Int = 10): List<TokenUsageStatistics>
}
