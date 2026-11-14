package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AiRequestActivityService
import com.gromozeka.domain.service.AiRequestActivitySnapshot
import com.gromozeka.remote.protocol.AiRequestActivityStatePayload
import com.gromozeka.remote.protocol.AiRequestActivityStateQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RemoteAiRequestActivityService(private val client: GromozekaWsClient) : AiRequestActivityService {
    override fun observe(conversationId: Conversation.Id): Flow<AiRequestActivitySnapshot> =
        client.observeState(AiRequestActivityStateQuery(conversationId)).map { snapshot ->
            val payload = snapshot.value as? AiRequestActivityStatePayload
                ?: error("Unexpected AI request activity payload: ${snapshot.value::class.simpleName}")
            payload.snapshot
        }
}
