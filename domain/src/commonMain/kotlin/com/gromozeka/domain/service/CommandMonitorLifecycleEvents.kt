package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CommandMonitorLifecycleEvent(
    val conversationId: Conversation.Id,
    val monitorId: CommandMonitor.Id,
    val kind: Kind,
    val occurredAt: Instant,
) {
    @Serializable
    enum class Kind {
        EVENTS_AVAILABLE,
        TERMINAL,
    }
}

fun interface CommandMonitorLifecycleEventPublisher {
    suspend fun publish(event: CommandMonitorLifecycleEvent)
}

interface CommandMonitorLifecycleEventStream {
    val events: Flow<CommandMonitorLifecycleEvent>
}
