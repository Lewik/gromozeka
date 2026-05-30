package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.remote.protocol.GetMemoryActionItemsRequest
import com.gromozeka.remote.protocol.MemoryActionItemsResponse

class RemoteMemoryActionItemService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun getActionItems(
        conversationId: Conversation.Id,
        includeClosed: Boolean = false,
    ): MemoryActionItemsResponse =
        client.requestTyped<GetMemoryActionItemsRequest, MemoryActionItemsResponse>(
            GetMemoryActionItemsRequest(
                conversationId = conversationId,
                includeClosed = includeClosed,
            )
        )
}
