package com.gromozeka.application.service

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.AiModelSpecRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationTokenStatsService
import org.springframework.stereotype.Service

@Service
class ConversationTokenStatsApplicationService(
    private val conversationDomainService: ConversationDomainService,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val aiModelSpecRepository: AiModelSpecRepository,
) : ConversationTokenStatsService {
    override suspend fun getTokenStats(conversationId: Conversation.Id): TokenUsageStatistics.ThreadTotals? =
        conversationDomainService.findById(conversationId)
            ?.let { tokenUsageStatisticsRepository.getThreadTotals(it.currentThread) }
            ?.withConfiguredContextWindow()

    private suspend fun TokenUsageStatistics.ThreadTotals.withConfiguredContextWindow(): TokenUsageStatistics.ThreadTotals {
        val aiProvider = provider?.let { providerName ->
            AiProvider.entries.firstOrNull { it.name == providerName }
        }
        val model = modelId
        val contextWindow = if (aiProvider != null && model != null) {
            aiModelSpecRepository.find(aiProvider, model)?.contextWindowTokens
        } else {
            null
        }

        val reportedContextSize = this.reportedContextSize
        val contextStatus = when {
            reportedContextSize == null -> TokenUsageStatistics.ContextStatus.UNAVAILABLE
            contextWindow != null && reportedContextSize > contextWindow ->
                TokenUsageStatistics.ContextStatus.OUT_OF_RANGE
            else -> TokenUsageStatistics.ContextStatus.AVAILABLE
        }
        return copy(
            currentContextSize = reportedContextSize.takeIf {
                contextStatus == TokenUsageStatistics.ContextStatus.AVAILABLE
            },
            reportedContextSize = reportedContextSize,
            contextStatus = contextStatus,
            contextWindowTokens = contextWindow,
        )
    }
}
