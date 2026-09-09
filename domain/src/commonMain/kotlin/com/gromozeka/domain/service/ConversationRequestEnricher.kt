package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeRequest

interface ConversationRequestEnricher {
    suspend fun enrich(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        turnId: ConversationRuntimeTurnId,
        model: AiModelSpec,
        request: AiRuntimeRequest,
    ): AiRuntimeRequest
}
