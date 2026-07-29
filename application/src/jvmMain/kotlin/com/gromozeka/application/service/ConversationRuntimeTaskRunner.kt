package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlinx.coroutines.flow.Flow

interface ConversationRuntimeTaskRunner {
    fun runRuntimeTask(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
    ): Flow<Conversation.Message>
}
