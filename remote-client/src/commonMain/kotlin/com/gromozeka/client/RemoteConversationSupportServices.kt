package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.remote.protocol.ConversationProjectItemsResponse
import com.gromozeka.remote.protocol.GetTokenStatsRequest
import com.gromozeka.remote.protocol.SearchConversationsRequest
import com.gromozeka.remote.protocol.SquashMessagesWithAiRequest
import com.gromozeka.remote.protocol.TextResponse
import com.gromozeka.remote.protocol.TokenStatsResponse

internal class RemoteConversationNameSearchService(
    private val client: GromozekaWsClient,
) : ConversationNameSearchService {
    override suspend fun searchConversations(query: String): List<Pair<Conversation, Project>> =
        client.requestTyped<SearchConversationsRequest, ConversationProjectItemsResponse>(SearchConversationsRequest(query))
            .items
            .map { it.conversation to it.project }
}

internal class RemoteConversationTokenStatsService(
    private val client: GromozekaWsClient,
) : ConversationTokenStatsService {
    override suspend fun getTokenStats(conversationId: Conversation.Id): TokenUsageStatistics.ThreadTotals? =
        client.requestTyped<GetTokenStatsRequest, TokenStatsResponse>(GetTokenStatsRequest(conversationId)).tokenStats
}

internal class RemoteMessageSquashGenerationService(
    private val client: GromozekaWsClient,
) : MessageSquashGenerationService {
    override suspend fun squashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        runtimeSelection: AiRuntimeSelection,
        projectPath: String?,
    ): String = client.requestTyped<SquashMessagesWithAiRequest, TextResponse>(
        SquashMessagesWithAiRequest(conversationId, selectedIds, squashType, runtimeSelection, projectPath)
    ).text
}
