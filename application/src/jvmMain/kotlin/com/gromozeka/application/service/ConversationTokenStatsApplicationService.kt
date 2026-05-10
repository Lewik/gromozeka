package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationTokenStatsService
import org.springframework.stereotype.Service

@Service
class ConversationTokenStatsApplicationService(
    private val conversationDomainService: ConversationDomainService,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
) : ConversationTokenStatsService {
    override suspend fun getTokenStats(conversationId: Conversation.Id): TokenUsageStatistics.ThreadTotals? =
        conversationDomainService.findById(conversationId)
            ?.let { tokenUsageStatisticsRepository.getThreadTotals(it.currentThread) }
}
