package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import kotlinx.coroutines.flow.Flow

interface ConversationRuntimeTaskRunner {
    fun runRuntimeTask(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
    ): Flow<Conversation.Message>
}
