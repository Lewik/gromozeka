package com.gromozeka.application.service

import com.gromozeka.domain.service.plan.PlanEvent
import com.gromozeka.domain.service.plan.PlanEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Service

/**
 * Implementation of PlanEventBus using Kotlin SharedFlow.
 * 
 * SharedFlow allows multiple subscribers and replays events
 * to new subscribers (replay=1 keeps last event).
 */
@Service
class PlanEventBusImpl : PlanEventBus {
    
    private val _events = MutableSharedFlow<PlanEvent>(
        replay = 1,  // Keep last event for late subscribers
        extraBufferCapacity = 64  // Buffer for burst events
    )
    
    override val events: Flow<PlanEvent> = _events.asSharedFlow()
    
    override suspend fun emit(event: PlanEvent) {
        _events.emit(event)
    }
}
