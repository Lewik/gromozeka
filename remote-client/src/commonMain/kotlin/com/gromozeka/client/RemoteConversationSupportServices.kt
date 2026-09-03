package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.QuickTextActionResult
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.service.ConversationHistoryService
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.ConversationSearchService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.AiUsageReportService
import com.gromozeka.remote.protocol.AiUsageReportResponse
import com.gromozeka.remote.protocol.GetAiUsageReportRequest
import com.gromozeka.domain.service.QuickTextActionService
import com.gromozeka.remote.protocol.ConversationSearchPageResponse
import com.gromozeka.remote.protocol.GetTokenStatsRequest
import com.gromozeka.remote.protocol.ListQuickTextActionsRequest
import com.gromozeka.remote.protocol.QuickTextActionResultResponse
import com.gromozeka.remote.protocol.QuickTextActionsResponse
import com.gromozeka.remote.protocol.RunQuickTextActionRequest
import com.gromozeka.remote.protocol.SearchConversationsRequest
import com.gromozeka.remote.protocol.CompactMessagesRequest
import com.gromozeka.remote.protocol.DeleteMessagesRequest
import com.gromozeka.remote.protocol.EditMessageRequest
import com.gromozeka.remote.protocol.ConversationResponse
import com.gromozeka.remote.protocol.TokenStatsResponse
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow
import com.gromozeka.shared.uuid.uuid7

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

internal class RemoteConversationHistoryService(
    private val client: GromozekaWsClient,
) : ConversationHistoryService {
    override suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>,
    ): Conversation? = client.requestTyped<EditMessageRequest, ConversationResponse>(
        EditMessageRequest(
            taskId = newHistoryTaskId(),
            conversationId = conversationId,
            messageId = messageId,
            newContent = newContent,
        )
    ).conversation

    override suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
    ): Conversation? = client.requestTyped<DeleteMessagesRequest, ConversationResponse>(
        DeleteMessagesRequest(
            taskId = newHistoryTaskId(),
            conversationId = conversationId,
            messageIds = messageIds,
        )
    ).conversation

    override suspend fun compactMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
    ): Conversation = client.requestTyped<CompactMessagesRequest, ConversationResponse>(
        CompactMessagesRequest(
            taskId = newHistoryTaskId(),
            conversationId = conversationId,
            messageIds = messageIds,
            strategy = strategy,
        )
    ).conversation ?: error("Server returned null conversation after compaction")

    private fun newHistoryTaskId(): ConversationRuntimeTask.Id = ConversationRuntimeTask.Id(uuid7())
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
