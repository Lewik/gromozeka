package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.TokenUsageStatisticsTable
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class ExposedTokenUsageStatisticsRepository : TokenUsageStatisticsRepository {

    override suspend fun save(stats: TokenUsageStatistics): Unit = dbQuery {
        TokenUsageStatisticsTable.insert {
            it[id] = stats.id.value
            it[threadId] = stats.threadId.value
            it[lastMessageId] = stats.lastMessageId.value
            it[timestamp] = stats.timestamp.toKotlin()
            it[promptTokens] = stats.promptTokens
            it[completionTokens] = stats.completionTokens
            it[cacheCreationTokens] = stats.cacheCreationTokens
            it[cacheReadTokens] = stats.cacheReadTokens
            it[thinkingTokens] = stats.thinkingTokens
            it[provider] = stats.provider
            it[modelId] = stats.modelId
        }
        Unit
    }

    override suspend fun getThreadTotals(threadId: Conversation.Thread.Id): TokenUsageStatistics.ThreadTotals = dbQuery {
        val stats = TokenUsageStatisticsTable.selectAll()
            .where { TokenUsageStatisticsTable.threadId eq threadId.value }
            .map { it.toTokenUsageStatistics() }

        val lastCall = TokenUsageStatisticsTable.selectAll()
            .where { TokenUsageStatisticsTable.threadId eq threadId.value }
            .orderBy(TokenUsageStatisticsTable.timestamp, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toTokenUsageStatistics()

        val recentCalls = getRecentCalls(threadId, 10)

        // Calculate current context size: all tokens visible to model in last call
        // = new prompt tokens + cached tokens (creation + read) + completion tokens
        val lastCallContextSize = lastCall?.let { call ->
            call.promptTokens + call.cacheCreationTokens + call.cacheReadTokens + call.completionTokens
        }

        TokenUsageStatistics.ThreadTotals(
            totalPromptTokens = stats.sumOf { it.promptTokens },
            totalCompletionTokens = stats.sumOf { it.completionTokens },
            totalCacheReadTokens = stats.sumOf { it.cacheReadTokens },
            totalCacheCreationTokens = stats.sumOf { it.cacheCreationTokens },
            totalThinkingTokens = stats.sumOf { it.thinkingTokens },
            lastCallTokens = lastCall?.totalTokens,
            recentCalls = recentCalls,
            currentContextSize = lastCallContextSize,
            provider = lastCall?.provider,
            modelId = lastCall?.modelId
        )
    }

    override suspend fun getRecentCalls(
        threadId: Conversation.Thread.Id,
        limit: Int
    ): List<TokenUsageStatistics> = dbQuery {
        TokenUsageStatisticsTable.selectAll()
            .where { TokenUsageStatisticsTable.threadId eq threadId.value }
            .orderBy(TokenUsageStatisticsTable.timestamp, SortOrder.DESC)
            .limit(limit)
            .map { it.toTokenUsageStatistics() }
    }

    private fun ResultRow.toTokenUsageStatistics() = TokenUsageStatistics(
        id = TokenUsageStatistics.Id(this[TokenUsageStatisticsTable.id]),
        threadId = Conversation.Thread.Id(this[TokenUsageStatisticsTable.threadId]),
        lastMessageId = Conversation.Message.Id(this[TokenUsageStatisticsTable.lastMessageId]),
        timestamp = this[TokenUsageStatisticsTable.timestamp].toKotlinx(),
        promptTokens = this[TokenUsageStatisticsTable.promptTokens],
        completionTokens = this[TokenUsageStatisticsTable.completionTokens],
        cacheCreationTokens = this[TokenUsageStatisticsTable.cacheCreationTokens],
        cacheReadTokens = this[TokenUsageStatisticsTable.cacheReadTokens],
        thinkingTokens = this[TokenUsageStatisticsTable.thinkingTokens],
        provider = this[TokenUsageStatisticsTable.provider],
        modelId = this[TokenUsageStatisticsTable.modelId]
    )
}
