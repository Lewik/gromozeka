package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CommandTaskLifecycleEvent(
    val conversationId: Conversation.Id,
    val taskId: CommandTask.Id,
    val status: CommandTask.Status,
    val occurredAt: Instant,
)

fun interface CommandTaskLifecycleEventPublisher {
    suspend fun publish(event: CommandTaskLifecycleEvent)
}

interface CommandTaskLifecycleEventStream {
    val events: Flow<CommandTaskLifecycleEvent>
}
