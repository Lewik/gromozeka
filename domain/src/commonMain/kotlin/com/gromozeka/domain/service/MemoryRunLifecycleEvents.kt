package com.gromozeka.domain.service

import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class MemoryRunLifecycleEvent(
    val runId: MemoryRun.Id,
    val status: MemoryRun.Status,
    val occurredAt: Instant,
)

fun interface MemoryRunLifecycleEventPublisher {
    suspend fun publish(event: MemoryRunLifecycleEvent)
}

interface MemoryRunLifecycleEventDelivery {
    val event: MemoryRunLifecycleEvent

    suspend fun acknowledge()
    suspend fun redeliver()
    suspend fun reject()
}

interface MemoryRunLifecycleEventConsumer {
    val deliveries: Flow<MemoryRunLifecycleEventDelivery>
}
