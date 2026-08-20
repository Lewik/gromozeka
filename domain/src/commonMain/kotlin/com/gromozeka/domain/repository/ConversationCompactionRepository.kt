package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation

interface ConversationCompactionRepository {
    suspend fun commitIfCurrent(
        expectedThreadId: Conversation.Thread.Id,
        compactionMessage: Conversation.Message,
        newThread: Conversation.Thread,
        newLinks: List<ThreadMessageLink>,
    ): Boolean
}
