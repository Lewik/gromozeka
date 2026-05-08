package com.gromozeka.infrastructure.db.persistence.mongo

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class MongoTokenUsageStatisticsRepository(
    database: MongoDatabase,
) : TokenUsageStatisticsRepository {
    private val statistics: MongoCollection<TokenUsageStatistics> = database.getCollection("token_usage_statistics")
    private val indexes = MongoIndexInitializer {
        statistics.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        statistics.createIndex(Indexes.ascending("threadId"))
        statistics.createIndex(Indexes.descending("timestamp"))
    }

    override suspend fun save(stats: TokenUsageStatistics) {
        indexes.ensure()
        statistics.insertNewByDomainId(stats.id.value, stats)
    }

    override suspend fun getThreadTotals(threadId: Conversation.Thread.Id): TokenUsageStatistics.ThreadTotals {
        indexes.ensure()
        val calls = statistics.find(Filters.eq("threadId", threadId.value)).toList()
        val lastCall = statistics.find(Filters.eq("threadId", threadId.value))
            .sort(Sorts.descending("timestamp"))
            .limit(1)
            .firstOrNull()
        val recentCalls = getRecentCalls(threadId)
        val lastCallContextSize = lastCall?.let { it.totalInputTokens + it.completionTokens }

        return TokenUsageStatistics.ThreadTotals(
            totalPromptTokens = calls.sumOf { it.promptTokens },
            totalCompletionTokens = calls.sumOf { it.completionTokens },
            totalCacheReadTokens = calls.sumOf { it.cacheReadTokens },
            totalCacheCreationTokens = calls.sumOf { it.cacheCreationTokens },
            totalThinkingTokens = calls.sumOf { it.thinkingTokens },
            lastCallTokens = lastCall?.totalTokens,
            recentCalls = recentCalls,
            currentContextSize = lastCallContextSize,
            provider = lastCall?.provider,
            modelId = lastCall?.modelId,
        )
    }

    override suspend fun getRecentCalls(
        threadId: Conversation.Thread.Id,
        limit: Int,
    ): List<TokenUsageStatistics> {
        indexes.ensure()
        return statistics.find(Filters.eq("threadId", threadId.value))
            .sort(Sorts.descending("timestamp"))
            .limit(limit)
            .toList()
    }
}
