package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.TokenUsageStatisticsTable
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class ExposedTokenUsageStatisticsRepository : TokenUsageStatisticsRepository {

    override suspend fun save(stats: TokenUsageStatistics): Unit = dbQuery {
        TokenUsageStatisticsTable.insert {
            it[id] = stats.id.value
            it[userId] = stats.userId?.value
            it[projectId] = stats.projectId?.value
            it[agentDefinitionId] = stats.agentDefinitionId?.value
            it[conversationId] = stats.conversationId?.value
            it[threadId] = stats.threadId?.value
            it[lastMessageId] = stats.lastMessageId?.value
            it[timestamp] = stats.timestamp
            it[runtimePurpose] = stats.runtimePurpose
            it[executionTarget] = stats.executionTarget
            it[connectionKind] = stats.connectionKind
            it[connectionId] = stats.connectionId
            it[modelConfigurationId] = stats.modelConfigurationId
            it[promptTokens] = stats.promptTokens
            it[completionTokens] = stats.completionTokens
            it[cacheCreationTokens] = stats.cacheCreationTokens
            it[cacheReadTokens] = stats.cacheReadTokens
            it[thinkingTokens] = stats.thinkingTokens
            it[provider] = stats.provider
            it[modelId] = stats.modelId
            it[contextInputTokens] = stats.contextInputTokens
            it[pricingCatalogVersion] = stats.price?.catalogVersion
            it[pricingEffectiveAt] = stats.price?.effectiveAt
            it[inputNanoUsdPerMillion] = stats.price?.inputNanoUsdPerMillion
            it[cacheCreationNanoUsdPerMillion] = stats.price?.cacheCreationNanoUsdPerMillion
            it[cacheReadNanoUsdPerMillion] = stats.price?.cacheReadNanoUsdPerMillion
            it[outputNanoUsdPerMillion] = stats.price?.outputNanoUsdPerMillion
            it[estimatedCostNanoUsd] = stats.price?.estimatedCostNanoUsd
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

        TokenUsageStatistics.ThreadTotals(
            totalPromptTokens = stats.sumOf { it.promptTokens },
            totalCompletionTokens = stats.sumOf { it.completionTokens },
            totalCacheReadTokens = stats.sumOf { it.cacheReadTokens },
            totalCacheCreationTokens = stats.sumOf { it.cacheCreationTokens },
            totalThinkingTokens = stats.sumOf { it.thinkingTokens },
            lastCallTokens = lastCall?.totalTokens,
            recentCalls = recentCalls,
            currentContextSize = lastCall?.contextInputTokens,
            reportedContextSize = lastCall?.contextInputTokens,
            contextStatus = if (lastCall?.contextInputTokens == null) {
                TokenUsageStatistics.ContextStatus.UNAVAILABLE
            } else {
                TokenUsageStatistics.ContextStatus.AVAILABLE
            },
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

    override suspend fun getReport(query: TokenUsageStatistics.ReportQuery): TokenUsageStatistics.Report = dbQuery {
        require(query.recentCallLimit in 1..200) { "Usage report recent call limit must be between 1 and 200" }
        val calls = TokenUsageStatisticsTable.selectAll()
            .orderBy(TokenUsageStatisticsTable.timestamp, SortOrder.DESC)
            .map { it.toTokenUsageStatistics() }
            .filter { call -> query.matches(call) }

        TokenUsageStatistics.Report(
            query = query,
            totals = calls.toTotals(),
            byProviderAndModel = calls.toBreakdown { "${it.provider} / ${it.modelId}" },
            byProject = calls.toBreakdown { it.projectId?.value ?: UNATTRIBUTED },
            byAgent = calls.toBreakdown { it.agentDefinitionId?.value ?: UNATTRIBUTED },
            byConversation = calls.toBreakdown { it.conversationId?.value ?: UNATTRIBUTED },
            byRuntimePurpose = calls.toBreakdown { it.runtimePurpose },
            recentCalls = calls.take(query.recentCallLimit),
        )
    }

    private fun ResultRow.toTokenUsageStatistics() = TokenUsageStatistics(
        id = TokenUsageStatistics.Id(this[TokenUsageStatisticsTable.id]),
        userId = this[TokenUsageStatisticsTable.userId]?.let(User::Id),
        projectId = this[TokenUsageStatisticsTable.projectId]?.let(Project::Id),
        agentDefinitionId = this[TokenUsageStatisticsTable.agentDefinitionId]?.let(AgentDefinition::Id),
        conversationId = this[TokenUsageStatisticsTable.conversationId]?.let(Conversation::Id),
        threadId = this[TokenUsageStatisticsTable.threadId]?.let(Conversation.Thread::Id),
        lastMessageId = this[TokenUsageStatisticsTable.lastMessageId]?.let(Conversation.Message::Id),
        timestamp = this[TokenUsageStatisticsTable.timestamp],
        runtimePurpose = this[TokenUsageStatisticsTable.runtimePurpose],
        executionTarget = this[TokenUsageStatisticsTable.executionTarget],
        connectionKind = this[TokenUsageStatisticsTable.connectionKind],
        connectionId = this[TokenUsageStatisticsTable.connectionId],
        modelConfigurationId = this[TokenUsageStatisticsTable.modelConfigurationId],
        promptTokens = this[TokenUsageStatisticsTable.promptTokens],
        completionTokens = this[TokenUsageStatisticsTable.completionTokens],
        cacheCreationTokens = this[TokenUsageStatisticsTable.cacheCreationTokens],
        cacheReadTokens = this[TokenUsageStatisticsTable.cacheReadTokens],
        thinkingTokens = this[TokenUsageStatisticsTable.thinkingTokens],
        provider = this[TokenUsageStatisticsTable.provider],
        modelId = this[TokenUsageStatisticsTable.modelId],
        contextInputTokens = this[TokenUsageStatisticsTable.contextInputTokens],
        price = this[TokenUsageStatisticsTable.pricingCatalogVersion]?.let { catalogVersion ->
            TokenUsageStatistics.PriceSnapshot(
                catalogVersion = catalogVersion,
                effectiveAt = requireNotNull(this[TokenUsageStatisticsTable.pricingEffectiveAt]),
                inputNanoUsdPerMillion = requireNotNull(this[TokenUsageStatisticsTable.inputNanoUsdPerMillion]),
                cacheCreationNanoUsdPerMillion = requireNotNull(this[TokenUsageStatisticsTable.cacheCreationNanoUsdPerMillion]),
                cacheReadNanoUsdPerMillion = requireNotNull(this[TokenUsageStatisticsTable.cacheReadNanoUsdPerMillion]),
                outputNanoUsdPerMillion = requireNotNull(this[TokenUsageStatisticsTable.outputNanoUsdPerMillion]),
                estimatedCostNanoUsd = requireNotNull(this[TokenUsageStatisticsTable.estimatedCostNanoUsd]),
            )
        },
    )

    private fun TokenUsageStatistics.ReportQuery.matches(call: TokenUsageStatistics): Boolean =
        (from?.let { call.timestamp >= it } != false) &&
            (to?.let { call.timestamp < it } != false) &&
            (provider == null || call.provider == provider) &&
            (modelId == null || call.modelId == modelId) &&
            (projectId == null || call.projectId == projectId) &&
            (agentDefinitionId == null || call.agentDefinitionId == agentDefinitionId) &&
            (conversationId == null || call.conversationId == conversationId) &&
            (runtimePurpose == null || call.runtimePurpose == runtimePurpose)

    private fun List<TokenUsageStatistics>.toBreakdown(
        key: (TokenUsageStatistics) -> String,
    ): List<TokenUsageStatistics.Breakdown> =
        groupBy(key)
            .map { (groupKey, calls) -> TokenUsageStatistics.Breakdown(groupKey, calls.toTotals()) }
            .sortedByDescending { it.totals.totalInputTokens + it.totals.totalOutputTokens }

    private fun List<TokenUsageStatistics>.toTotals(): TokenUsageStatistics.Totals =
        TokenUsageStatistics.Totals(
            callCount = size,
            promptTokens = sumOf { it.promptTokens.toLong() },
            completionTokens = sumOf { it.completionTokens.toLong() },
            cacheCreationTokens = sumOf { it.cacheCreationTokens.toLong() },
            cacheReadTokens = sumOf { it.cacheReadTokens.toLong() },
            thinkingTokens = sumOf { it.thinkingTokens.toLong() },
            estimatedCostNanoUsd = sumOf { it.price?.estimatedCostNanoUsd ?: 0L },
            pricedCallCount = count { it.price != null },
            unpricedCallCount = count { it.price == null },
        )

    private companion object {
        const val UNATTRIBUTED = "unattributed"
    }
}
