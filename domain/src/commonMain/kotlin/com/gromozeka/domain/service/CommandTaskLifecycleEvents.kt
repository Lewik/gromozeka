package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
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

interface CommandTaskLifecycleEventDelivery {
    val event: CommandTaskLifecycleEvent

    suspend fun acknowledge()
    suspend fun redeliver()
    suspend fun reject()
}

interface CommandTaskLifecycleEventConsumer {
    val deliveries: Flow<CommandTaskLifecycleEventDelivery>
}
