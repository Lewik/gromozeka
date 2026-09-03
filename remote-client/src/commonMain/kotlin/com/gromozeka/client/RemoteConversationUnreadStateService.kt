package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationUnreadState
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ConversationUnreadStateService
import com.gromozeka.remote.protocol.ConversationUnreadStateResponse
import com.gromozeka.remote.protocol.GetConversationUnreadStateRequest
import com.gromozeka.remote.protocol.MarkConversationReadRequest
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

internal class RemoteConversationUnreadStateService(
    private val client: GromozekaWsClient,
    private val currentUserId: User.Id,
) : ConversationUnreadStateService {
    override suspend fun snapshot(): ConversationUnreadState =
        client.requestTyped<GetConversationUnreadStateRequest, ConversationUnreadStateResponse>(
            GetConversationUnreadStateRequest,
        ).state

    override suspend fun markRead(conversationId: Conversation.Id): ConversationUnreadState =
        client.requestTyped<MarkConversationReadRequest, ConversationUnreadStateResponse>(
            MarkConversationReadRequest(conversationId),
        ).state

    override fun observe(): Flow<ConversationUnreadState> =
        client.observeDeclarativeState(
            RemoteDeclarativeStateResource.CONVERSATION_UNREAD_STATE,
            currentUserId.value,
            load = ::snapshot,
        )
}
