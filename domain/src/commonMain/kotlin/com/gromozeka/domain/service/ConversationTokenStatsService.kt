package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics

interface ConversationTokenStatsService {
    suspend fun getTokenStats(conversationId: Conversation.Id): TokenUsageStatistics.ThreadTotals?
}
