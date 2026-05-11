package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.remote.protocol.GetMemoryTasksRequest
import com.gromozeka.remote.protocol.MemoryTasksResponse

class RemoteMemoryTaskService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun getTasks(
        conversationId: Conversation.Id,
        includeClosed: Boolean = false,
    ): MemoryTasksResponse =
        client.requestTyped<GetMemoryTasksRequest, MemoryTasksResponse>(
            GetMemoryTasksRequest(
                conversationId = conversationId,
                includeClosed = includeClosed,
            )
        )
}
