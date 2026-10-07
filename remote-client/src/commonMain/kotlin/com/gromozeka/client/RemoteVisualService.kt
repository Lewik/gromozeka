package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.visual.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RemoteVisualService(private val client: GromozekaWsClient) : VisualService {
    override fun observe(conversationId: Conversation.Id): Flow<List<Visual>> =
        client.observeState(VisualsStateQuery(conversationId)).map { snapshot ->
            (snapshot.value as VisualsStatePayload).visuals
        }

    override suspend fun list(conversationId: Conversation.Id): List<Visual> =
        client.requestTyped<ListVisualsRequest, VisualsResponse>(ListVisualsRequest(conversationId)).visuals

    override suspend fun create(conversationId: Conversation.Id, request: VisualCreate): Visual =
        client.requestTyped<CreateVisualRequest, VisualResponse>(CreateVisualRequest(conversationId, request)).visual

    override suspend fun update(conversationId: Conversation.Id, visualId: String, request: VisualUpdate): Visual =
        client.requestTyped<UpdateVisualRequest, VisualResponse>(UpdateVisualRequest(conversationId, visualId, request)).visual

    override suspend fun close(conversationId: Conversation.Id, visualId: String) {
        client.requestTyped<CloseVisualRequest, OperationResultResponse>(CloseVisualRequest(conversationId, visualId))
    }

    override suspend fun act(conversationId: Conversation.Id, action: VisualAction): VisualActionResult =
        client.requestTyped<VisualActionRequest, VisualActionResponse>(VisualActionRequest(conversationId, action)).result
}
