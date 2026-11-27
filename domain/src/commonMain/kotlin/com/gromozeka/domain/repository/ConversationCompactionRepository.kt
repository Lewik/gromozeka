package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation

interface ConversationCompactionRepository {
    suspend fun commitIfCurrent(
        expectedThreadId: Conversation.Thread.Id,
        compactionMessages: List<Conversation.Message>,
        newThread: Conversation.Thread,
        insertionAnchorId: Conversation.Message.Id,
        preparedCompactionId: String? = null,
    ): Boolean
}
