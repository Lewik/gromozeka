package com.gromozeka.server

import klog.KLoggers
import kotlinx.coroutines.*
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service

@Service
class WorkerRequestMaintenance(private val requests: WorkerRequestService) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private var scope: CoroutineScope? = null

    override fun isRunning() = scope != null

    override fun start() {
        if (scope != null) return
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("worker-request-maintenance"))
        scope = current
        current.launch {
            while (isActive) {
                try {
                    requests.maintain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.error { "Worker request maintenance failed (${error::class.simpleName}); retained requests will be retried" }
                }
                delay(1_000)
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
    }
}
