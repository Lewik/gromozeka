package com.gromozeka.server

import klog.KLoggers
import kotlinx.coroutines.*
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service

@Service
internal class CommandScriptGrantMaintenance(private val grants: CommandScriptGrantService) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private var scope: CoroutineScope? = null

    override fun isRunning(): Boolean = scope != null

    override fun start() {
        if (scope != null) return
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("command-sdk-grants"))
        scope = current
        current.launch {
            while (isActive) {
                try {
                    grants.maintain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.error { "Command SDK grant maintenance failed (${error::class.simpleName}); state retained" }
                }
                delay(60_000)
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
    }
}
