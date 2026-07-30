package com.gromozeka.application.service

import com.gromozeka.domain.service.CommandMonitorLifecycleEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEventPublisher
import com.gromozeka.domain.service.CommandMonitorLifecycleEventStream
import com.gromozeka.domain.service.CommandTaskLifecycleEvent
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.CommandTaskLifecycleEventStream
import com.gromozeka.domain.service.MemoryRunLifecycleEvent
import com.gromozeka.domain.service.MemoryRunLifecycleEventPublisher
import com.gromozeka.domain.service.MemoryRunLifecycleEventStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class LocalMemoryRunLifecycleEventBus :
    MemoryRunLifecycleEventPublisher,
    MemoryRunLifecycleEventStream {
    private val channel = Channel<MemoryRunLifecycleEvent>(Channel.UNLIMITED)

    override val events: Flow<MemoryRunLifecycleEvent> = channel.receiveAsFlow()

    override suspend fun publish(event: MemoryRunLifecycleEvent) {
        channel.send(event)
    }
}

@Service
@Primary
class LocalCommandTaskLifecycleEventBus :
    CommandTaskLifecycleEventPublisher,
    CommandTaskLifecycleEventStream {
    private val channel = Channel<CommandTaskLifecycleEvent>(Channel.UNLIMITED)

    override val events: Flow<CommandTaskLifecycleEvent> = channel.receiveAsFlow()

    override suspend fun publish(event: CommandTaskLifecycleEvent) {
        channel.send(event)
    }
}

@Service
@Primary
class LocalCommandMonitorLifecycleEventBus :
    CommandMonitorLifecycleEventPublisher,
    CommandMonitorLifecycleEventStream {
    private val channel = Channel<CommandMonitorLifecycleEvent>(Channel.UNLIMITED)

    override val events: Flow<CommandMonitorLifecycleEvent> = channel.receiveAsFlow()

    override suspend fun publish(event: CommandMonitorLifecycleEvent) {
        channel.send(event)
    }
}
