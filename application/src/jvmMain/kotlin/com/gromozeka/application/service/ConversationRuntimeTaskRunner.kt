package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome

interface ConversationRuntimeTaskRunner {
    suspend fun runRuntimeTask(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        emitMessage: suspend (Conversation.Message) -> Unit,
    ): ConversationRuntimeTaskOutcome
}
