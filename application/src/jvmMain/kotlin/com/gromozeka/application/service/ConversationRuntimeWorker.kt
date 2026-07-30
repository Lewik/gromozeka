package com.gromozeka.application.service

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.WorkerToolCatalogPublisher
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.supportedBy
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class ConversationRuntimeWorker(
    private val runtimeWorkerRegistry: ConversationRuntimeWorkerRegistry,
    private val workspaceService: WorkspaceDomainService,
    private val aiConfigurationService: AiConfigurationService,
    private val aiToolProvider: AiToolProvider,
    runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
    runtimeWorkerIdentity: ConversationRuntimeWorkerIdentity,
    @Value("\${gromozeka.runtime.worker.version:dev}") private val workerVersion: String,
    @Value("\${gromozeka.runtime.worker.heartbeat-interval-millis:5000}")
    private val heartbeatIntervalMillis: Long = ConversationRuntimeTiming.workerHeartbeatIntervalMillis,
    @Qualifier("applicationScope") private val parentScope: CoroutineScope,
) : SmartLifecycle, WorkerToolCatalogPublisher {
    private val log = KLoggers.logger(this)
    private val startedAt = Clock.System.now()
    private val runtimeWorker = runtimeWorkerIdentity
    private val runtimeWorkerCapabilities = runtimeWorkerDescriptor.capabilities
    private val environmentProfile = runtimeWorkerDescriptor.environmentProfile
    @Volatile
    private var runtimeTools = runtimeWorkerDescriptor.tools
    private val lifecycleLock = Any()
    private val termination = CompletableDeferred<Throwable?>()

    @Volatile
    private var running = false
    private var runtimeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastToolRefreshWarningAtNanos: Long? = null

    init {
        require(runtimeWorker.workerId == runtimeWorkerDescriptor.id) {
            "Runtime Worker identity and descriptor must use the same Worker id"
        }
    }

    val identity: ConversationRuntimeWorkerIdentity
        get() = runtimeWorker

    override val capabilities: Set<ConversationRuntimeCapability>
        get() = runtimeWorkerCapabilities

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) {
                return
            }
            check(!termination.isCompleted) {
                "Conversation runtime worker cannot restart after termination: $runtimeWorker"
            }
            require(heartbeatIntervalMillis > 0) {
                "Conversation runtime worker heartbeat interval must be positive"
            }
            val workspaceCount = runBlocking {
                val now = Clock.System.now()
                val registered = runtimeWorkerRegistry.register(
                    registration = ConversationRuntimeWorkerRegistration(
                        identity = runtimeWorker,
                        capabilities = runtimeWorkerCapabilities,
                        tools = runtimeTools,
                        environmentProfile = environmentProfile,
                        version = workerVersion,
                        startedAt = startedAt,
                        lastHeartbeatAt = now,
                    ),
                    staleBefore = now - ConversationRuntimeTiming.workerRegistrationStaleAfter,
                )
                check(registered) {
                    "Conversation runtime worker id is already owned by a live session: ${runtimeWorker.workerId.value}"
                }
                workspaceService.findMountsByWorker(runtimeWorker.workerId.value).size
            }

            val parentJob = parentScope.coroutineContext[Job]
            val workerJob = SupervisorJob(parentJob)
            val workerScope = CoroutineScope(parentScope.coroutineContext + workerJob)
            runtimeJob = workerJob
            heartbeatJob = workerScope.launch {
                runHeartbeatLoop(workerJob)
            }
            running = true
            log.info {
                "Conversation runtime worker started: identity=$runtimeWorker " +
                    "capabilities=${runtimeWorkerCapabilities.joinToString()} " +
                    "workspaces=$workspaceCount " +
                    "tools=${runtimeTools.size}"
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            if (!running && runtimeJob == null) {
                return
            }
            running = false
            runBlocking {
                heartbeatJob?.cancelAndJoin()
                runtimeJob?.cancelAndJoin()
                runCatching {
                    runtimeWorkerRegistry.unregister(runtimeWorker, Clock.System.now())
                }.onFailure { error ->
                    log.warn(error) { "Failed to unregister conversation runtime worker: identity=$runtimeWorker" }
                }
            }
            termination.complete(null)
            heartbeatJob = null
            runtimeJob = null
            log.info { "Conversation runtime worker stopped: identity=$runtimeWorker" }
        }
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 100

    suspend fun awaitTermination(): Throwable? = termination.await()

    override suspend fun updateAdvertisedTools(tools: List<AiToolDescriptor>) {
        require(tools.all { runtimeWorkerCapabilities.containsAll(it.metadata.requiredRuntimeCapabilities) }) {
            "Worker must declare every capability required by its advertised tools"
        }
        require(tools.map { it.definition.name }.distinct().size == tools.size) {
            "Worker advertised tool names must be unique"
        }
        if (running) {
            if (!runtimeWorkerRegistry.updateTools(runtimeWorker, tools, Clock.System.now())) {
                throw WorkerRegistrationLostException(
                    "Conversation runtime worker lost registration while updating tools: $runtimeWorker"
                )
            }
        }
        runtimeTools = tools
    }

    private suspend fun runHeartbeatLoop(workerJob: Job) {
        var controlPlaneUnavailable = false
        while (workerJob.isActive) {
            delay(heartbeatIntervalMillis)
            try {
                refreshAdvertisedTools()
                lastToolRefreshWarningAtNanos = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: WorkerRegistrationLostException) {
                terminateAfterRegistrationLoss(workerJob, error)
                return
            } catch (error: Throwable) {
                warnToolRefreshFailure(error)
            }

            val heartbeatAccepted = try {
                runtimeWorkerRegistry.heartbeat(runtimeWorker, Clock.System.now())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!controlPlaneUnavailable) {
                    log.warn(error) {
                        "Conversation runtime worker control plane is unavailable; " +
                            "local processes continue and no execution is retried: identity=$runtimeWorker"
                    }
                }
                controlPlaneUnavailable = true
                continue
            }
            if (!heartbeatAccepted) {
                terminateAfterRegistrationLoss(
                    workerJob,
                    WorkerRegistrationLostException(
                        "Conversation runtime worker session lost registration: $runtimeWorker"
                    ),
                )
                return
            }
            if (controlPlaneUnavailable) {
                controlPlaneUnavailable = false
                log.info { "Conversation runtime worker control plane connection recovered: identity=$runtimeWorker" }
            }
        }
    }

    private suspend fun refreshAdvertisedTools() {
        aiConfigurationService.refreshIfChanged()
        val refreshedTools = if (
            ConversationRuntimeCapability.TOOL_EXECUTION in runtimeWorkerCapabilities
        ) {
            aiToolProvider.getTools()
        } else {
            emptyList()
        }
            .supportedBy(runtimeWorkerCapabilities)
            .filter { it.metadata.executionScope != AiToolExecutionScope.CONVERSATION_RUNTIME }
            .map { AiToolDescriptor(it.definition, it.metadata) }
            .sortedBy { it.definition.name }
        if (refreshedTools != runtimeTools) {
            updateAdvertisedTools(refreshedTools)
            log.info {
                "Conversation runtime worker tools refreshed: identity=$runtimeWorker tools=${runtimeTools.size}"
            }
        }
    }

    private fun terminateAfterRegistrationLoss(workerJob: Job, error: Throwable) {
        log.error(error) { "Conversation runtime worker registration failed: identity=$runtimeWorker" }
        running = false
        termination.complete(error)
        workerJob.cancel(
            CancellationException("Conversation runtime worker registration failed: $runtimeWorker").apply {
                initCause(error)
            }
        )
    }

    private fun warnToolRefreshFailure(error: Throwable) {
        val now = System.nanoTime()
        val lastWarningAt = lastToolRefreshWarningAtNanos
        if (lastWarningAt == null || now - lastWarningAt >= TOOL_REFRESH_WARNING_INTERVAL_NANOS) {
            lastToolRefreshWarningAtNanos = now
            log.warn(error) {
                "Conversation runtime worker keeps its previous tool catalog after refresh failure: " +
                    "identity=$runtimeWorker"
            }
        }
    }

    private companion object {
        const val TOOL_REFRESH_WARNING_INTERVAL_NANOS = 30_000_000_000L
    }

    private class WorkerRegistrationLostException(message: String) : IllegalStateException(message)
}
