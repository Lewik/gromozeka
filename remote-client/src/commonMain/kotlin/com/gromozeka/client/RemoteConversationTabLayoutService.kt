package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.service.ConversationTabLayoutService
import com.gromozeka.remote.protocol.CloseConversationTabRequest
import com.gromozeka.remote.protocol.ConversationTabLayoutResponse
import com.gromozeka.remote.protocol.GetConversationTabLayoutRequest
import com.gromozeka.remote.protocol.OpenConversationTabRequest
import kotlinx.coroutines.flow.Flow

internal class RemoteConversationTabLayoutService(
    private val client: GromozekaWsClient,
) : ConversationTabLayoutService {
    override suspend fun snapshot(): ConversationTabLayout =
        client.requestTyped<GetConversationTabLayoutRequest, ConversationTabLayoutResponse>(
            GetConversationTabLayoutRequest,
        ).layout

    override suspend fun open(conversationId: Conversation.Id): ConversationTabLayout =
        client.requestTyped<OpenConversationTabRequest, ConversationTabLayoutResponse>(
            OpenConversationTabRequest(conversationId),
        ).layout

    override suspend fun close(conversationId: Conversation.Id): ConversationTabLayout =
        client.requestTyped<CloseConversationTabRequest, ConversationTabLayoutResponse>(
            CloseConversationTabRequest(conversationId),
        ).layout

    override fun observe(): Flow<ConversationTabLayout> = client.observeConversationTabLayout()
}
