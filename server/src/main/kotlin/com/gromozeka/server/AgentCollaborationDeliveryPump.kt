package com.gromozeka.server

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import kotlinx.coroutines.*
import org.springframework.context.SmartLifecycle
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import klog.KLoggers

/** Durable outbox + wakeups; only pending delivery rows are swept after restart or a lost wakeup. */
@Service
@ConditionalOnProperty(name = ["gromozeka.collaboration.enabled"], havingValue = "true")
class AgentCollaborationDeliveryPump(private val collaboration: AgentCollaborationService,
    private val coordinator: ConversationRuntimeCoordinator) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private var scope: CoroutineScope? = null
    override fun isRunning(): Boolean = scope != null
    override fun start() {
        if (scope != null) return
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = current
        current.launch { coordinator.schedulingSignals.collect { collaboration.signal() } }
        current.launch {
            while (isActive) {
                try { collaboration.deliverPending() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log.warn { "Collaboration outbox deferred: ${error::class.simpleName}" } }
                withTimeoutOrNull(30_000) { collaboration.wakeups.receive() }
                delay(100) // Coalesce bursty runtime invalidations, never scan conversation history.
            }
        }
    }
    override fun stop() { scope?.cancel(); scope = null }
}
