package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.QuickTextActionResult
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationSearchService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.AiUsageReportService
import com.gromozeka.remote.protocol.AiUsageReportResponse
import com.gromozeka.remote.protocol.GetAiUsageReportRequest
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.QuickTextActionService
import com.gromozeka.remote.protocol.ConversationSearchPageResponse
import com.gromozeka.remote.protocol.GetTokenStatsRequest
import com.gromozeka.remote.protocol.ListQuickTextActionsRequest
import com.gromozeka.remote.protocol.QuickTextActionResultResponse
import com.gromozeka.remote.protocol.QuickTextActionsResponse
import com.gromozeka.remote.protocol.RunQuickTextActionRequest
import com.gromozeka.remote.protocol.SearchConversationsRequest
import com.gromozeka.remote.protocol.SquashMessagesWithAiRequest
import com.gromozeka.remote.protocol.TextResponse
import com.gromozeka.remote.protocol.TokenStatsResponse
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

internal class RemoteConversationSearchService(
    private val client: GromozekaWsClient,
) : ConversationSearchService {
    override suspend fun search(request: ConversationSearchRequest): ConversationSearchPage =
        client.requestTyped<SearchConversationsRequest, ConversationSearchPageResponse>(
            SearchConversationsRequest(request)
        ).page
}

internal class RemoteConversationTokenStatsService(
    private val client: GromozekaWsClient,
) : ConversationTokenStatsService {
    override suspend fun getTokenStats(conversationId: Conversation.Id): TokenUsageStatistics.ThreadTotals? =
        client.requestTyped<GetTokenStatsRequest, TokenStatsResponse>(GetTokenStatsRequest(conversationId)).tokenStats
}

internal class RemoteAiUsageReportService(
    private val client: GromozekaWsClient,
) : AiUsageReportService {
    override suspend fun getReport(query: TokenUsageStatistics.ReportQuery): TokenUsageStatistics.Report =
        client.requestTyped<GetAiUsageReportRequest, AiUsageReportResponse>(
            GetAiUsageReportRequest(query)
        ).report
}

internal class RemoteMessageSquashGenerationService(
    private val client: GromozekaWsClient,
) : MessageSquashGenerationService {
    override suspend fun squashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        runtimeSelection: AiRuntimeSelection,
    ): String = client.requestTyped<SquashMessagesWithAiRequest, TextResponse>(
        SquashMessagesWithAiRequest(conversationId, selectedIds, squashType, runtimeSelection)
    ).text
}

internal class RemoteQuickTextActionService(
    private val client: GromozekaWsClient,
) : QuickTextActionService {
    override suspend fun listActions(): List<QuickTextAction> =
        client.requestTyped<ListQuickTextActionsRequest, QuickTextActionsResponse>(
            ListQuickTextActionsRequest
        ).actions

    override fun observeActions(): Flow<List<QuickTextAction>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.QUICK_TEXT_ACTIONS, load = ::listActions)

    override suspend fun runAction(
        actionId: QuickTextAction.Id,
        text: String,
    ): QuickTextActionResult =
        client.requestTyped<RunQuickTextActionRequest, QuickTextActionResultResponse>(
            RunQuickTextActionRequest(actionId, text)
        ).result
}
