package com.gromozeka.server

import com.gromozeka.application.service.SlotApplicationService
import kotlinx.coroutines.*
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import klog.KLoggers

/** Database is authoritative. The conflated wakeup only reduces latency between durable sweeps. */
@Service
class SlotQueuePump(private val slots: SlotApplicationService) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private var scope: CoroutineScope? = null
    override fun isRunning() = scope != null
    override fun start() {
        if (scope != null) return
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = current
        current.launch {
            while (isActive) {
                try { slots.processPending(); slots.deliverPending() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log.warn { "Slot queue deferred: ${error::class.simpleName}" } }
                withTimeoutOrNull(3_000) { slots.wakeups.receive() }
                delay(50)
            }
        }
    }
    override fun stop() { scope?.cancel(); scope = null }
}
