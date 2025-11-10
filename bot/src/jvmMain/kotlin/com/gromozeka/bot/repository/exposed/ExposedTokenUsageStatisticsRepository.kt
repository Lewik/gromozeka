package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.TokenUsageStatisticsTable
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.TokenUsageStatistics
import com.gromozeka.shared.repository.TokenUsageStatisticsRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant

class ExposedTokenUsageStatisticsRepository : TokenUsageStatisticsRepository {

    override suspend fun save(stats: TokenUsageStatistics): Unit = dbQuery {
        TokenUsageStatisticsTable.insert {
            it[id] = stats.id.value
            it[threadId] = stats.threadId.value
            it[turnNumber] = stats.turnNumber
            it[timestamp] = stats.timestamp
            it[promptTokens] = stats.promptTokens
            it[completionTokens] = stats.completionTokens
            it[cacheCreationTokens] = stats.cacheCreationTokens
            it[cacheReadTokens] = stats.cacheReadTokens
            it[thinkingTokens] = stats.thinkingTokens
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
            .orderBy(TokenUsageStatisticsTable.turnNumber, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toTokenUsageStatistics()

        val recentCalls = getRecentCalls(threadId, 10)

        TokenUsageStatistics.ThreadTotals(
            totalPromptTokens = stats.sumOf { it.promptTokens },
            totalCompletionTokens = stats.sumOf { it.completionTokens },
            totalCacheReadTokens = stats.sumOf { it.cacheReadTokens },
            totalThinkingTokens = stats.sumOf { it.thinkingTokens },
            lastCallTokens = lastCall?.totalTokens,
            recentCalls = recentCalls,
            currentContextSize = lastCall?.promptTokens,
            modelId = lastCall?.modelId
        )
    }

    override suspend fun getRecentCalls(
        threadId: Conversation.Thread.Id,
        limit: Int
    ): List<TokenUsageStatistics> = dbQuery {
        TokenUsageStatisticsTable.selectAll()
            .where { TokenUsageStatisticsTable.threadId eq threadId.value }
            .orderBy(TokenUsageStatisticsTable.turnNumber, SortOrder.DESC)
            .limit(limit)
            .map { it.toTokenUsageStatistics() }
    }

    private fun ResultRow.toTokenUsageStatistics() = TokenUsageStatistics(
        id = TokenUsageStatistics.Id(this[TokenUsageStatisticsTable.id]),
        threadId = Conversation.Thread.Id(this[TokenUsageStatisticsTable.threadId]),
        turnNumber = this[TokenUsageStatisticsTable.turnNumber],
        timestamp = this[TokenUsageStatisticsTable.timestamp],
        promptTokens = this[TokenUsageStatisticsTable.promptTokens],
        completionTokens = this[TokenUsageStatisticsTable.completionTokens],
        cacheCreationTokens = this[TokenUsageStatisticsTable.cacheCreationTokens],
        cacheReadTokens = this[TokenUsageStatisticsTable.cacheReadTokens],
        thinkingTokens = this[TokenUsageStatisticsTable.thinkingTokens],
        modelId = this[TokenUsageStatisticsTable.modelId]
    )
}
