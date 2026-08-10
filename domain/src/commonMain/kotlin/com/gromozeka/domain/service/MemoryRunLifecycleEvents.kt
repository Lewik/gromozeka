package com.gromozeka.domain.service

import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
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

interface MemoryRunLifecycleEventStream {
    val events: Flow<MemoryRunLifecycleEvent>
}
