package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType

interface MessageSquashService {
    suspend fun squash(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
    ): Conversation
}
