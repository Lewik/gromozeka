package com.gromozeka.worker

import com.gromozeka.worker.runtime.WorkerGatewayRuntime
import com.gromozeka.worker.runtime.KtorWorkerGatewayTransport
import com.gromozeka.worker.runtime.WorkerRequestHandler
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.domain.service.WorkerAudioCaptureHandler
import com.gromozeka.domain.service.WorkerWorkspaceTextFileHandler
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerAudioCaptureGatewayCodec
import com.gromozeka.remote.protocol.WorkerWorkspaceTextFileGatewayCodec
import com.gromozeka.remote.protocol.AiRequestResponseGatewayCodec
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.net.URI
import kotlin.time.Duration.Companion.seconds

@ConfigurationProperties("gromozeka.worker-gateway")
data class WorkerGatewayProperties(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val credential: String = "",
    val caCertificatePath: String? = null,
    val reconnectDelaySeconds: Long = 5,
) {
    fun validate() {
        require(serverUrl.isNotBlank()) { "Worker Gateway Server URL is required" }
        require(credential.length in 40..128) { "Worker Gateway credential is invalid" }
        require(reconnectDelaySeconds in 1..60) {
            "Worker Gateway reconnect delay must be between 1 and 60 seconds"
        }
        workerGatewayWebSocketUrl(serverUrl)
    }
}

@Service
@EnableConfigurationProperties(WorkerGatewayProperties::class)
@ConditionalOnProperty(
    name = ["gromozeka.worker-gateway.enabled"],
    havingValue = "true",
)
class WorkerGatewayClient(
    private val properties: WorkerGatewayProperties,
    private val identity: ConversationRuntimeWorkerIdentity,
    descriptor: ConversationRuntimeWorkerDescriptor,
    private val operationHandler: WorkerGatewayOperationHandler,
    private val aiConfigurationProvider: WorkerAiConfigurationProvider,
    private val mcpConfigurationService: McpConfigurationService,
    private val workerToolCatalog: WorkerToolCatalog,
    private val outbound: WorkerGatewayOutbound,
    private val requestJournal: JvmWorkerRequestJournal,
    @Qualifier("applicationScope") private val scope: CoroutineScope,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val gatewayUrl: String
    private val startedAt = Clock.System.now()
    private val capabilities = descriptor.capabilities
    private val environmentProfile = descriptor.environmentProfile
    private val client = HttpClient(CIO) {
        properties.caCertificatePath
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { path ->
                engine {
                    https {
                        trustManager = workerTrustManager(path)
                    }
                }
            }
        install(WebSockets) {
            maxFrameSize = MAX_WORKER_GATEWAY_FRAME_BYTES
        }
    }
    private val lifecycleLock = Any()
    private val termination = CompletableDeferred<Throwable?>()
    private var connectionJob: Job? = null

    @Volatile
    private var running = false

    init {
        properties.validate()
        gatewayUrl = workerGatewayWebSocketUrl(properties.serverUrl)
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            check(!termination.isCompleted) { "Worker Gateway cannot restart after termination" }
            running = true
            connectionJob = scope.launch {
                runtime.run()
            }.also { job ->
                job.invokeOnCompletion { error ->
                    if (running) {
                        running = false
                        termination.complete(
                            error ?: IllegalStateException("Worker Gateway connection loop stopped")
                        )
                    }
                }
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            if (!running && connectionJob == null) return
            running = false
            runBlocking {
                connectionJob?.cancelAndJoin()
            }
            connectionJob = null
            client.close()
            termination.complete(null)
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

    private val runtime by lazy {
        WorkerGatewayRuntime(
            journal = requestJournal,
            transport = KtorWorkerGatewayTransport(client, gatewayUrl, properties.credential),
            registration = {
                ConversationRuntimeWorkerRegistration(
                    identity = identity,
                    capabilities = capabilities,
                    tools = outbound.currentTools(),
                    environmentProfile = environmentProfile,
                    version = currentWorkerVersion(),
                    startedAt = startedAt,
                    lastHeartbeatAt = Clock.System.now(),
                )
            },
            outbound = outbound,
            handler = WorkerRequestHandler { operationHandler.execute(identity, it) },
            prepare = { welcome ->
                aiConfigurationProvider.synchronize(welcome.aiCatalogSnapshot)
                val refreshAvailable = mcpConfigurationService.synchronize(welcome.mcpServers)
                WorkerGatewayMessage.Ready(workerToolCatalog.snapshot(), refreshAvailable)
            },
            updateCatalog = { message ->
                aiConfigurationProvider.synchronize(message.snapshot)
                outbound.updateAdvertisedTools(workerToolCatalog.snapshot())
            },
            reconnectDelay = properties.reconnectDelaySeconds.seconds,
            onConnected = {
                log.info { "Worker Gateway connected: worker=${identity.workerId.value} url=$gatewayUrl" }
            },
            onDisconnected = {
                log.warn { "Worker Gateway disconnected; reconnecting: worker=${identity.workerId.value}" }
            },
            onFailure = { error, attempts ->
                if (error.isExpectedConnectionFailure()) {
                    log.warn {
                        "Worker Gateway is unavailable (attempt $attempts): " +
                            "worker=${identity.workerId.value} error=${error::class.simpleName}: ${error.message}"
                    }
                } else {
                    log.warn(error) { "Worker Gateway connection failed (attempt $attempts): worker=${identity.workerId.value}" }
                }
            },
        )
    }

    private fun Throwable.isExpectedConnectionFailure(): Boolean =
        generateSequence(this, Throwable::cause).any {
            it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException ||
                it is java.net.SocketTimeoutException ||
                it is java.net.UnknownHostException ||
                it is java.nio.channels.UnresolvedAddressException
        }

}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.worker-gateway.enabled"],
    havingValue = "true",
)
class WorkerGatewayOperationHandler(
    private val workerControlHandler: WorkerControlHandler,
    private val aiRequestResponseHandler: AiRequestResponseExecutionHandler,
    private val workerAudioCaptureHandler: WorkerAudioCaptureHandler,
    private val workerWorkspaceTextFileHandler: WorkerWorkspaceTextFileHandler,
    private val parallelToolExecutor: ParallelToolExecutor,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    suspend fun execute(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): WorkerGatewayMessage.Response =
        runCatching {
            val payload = when (request.operation) {
                WorkerGatewayOperation.WORKER_CONTROL -> {
                    val controlRequest = json.decodeFromString<WorkerControlRequest>(
                        request.payload.decodeToString()
                    )
                    require(controlRequest.target.workerId == identity.workerId) {
                        "Worker control request targets another Worker"
                    }
                    json.encodeToString<WorkerControlResult>(
                        workerControlHandler.handle(controlRequest)
                    ).encodeToByteArray()
                }

                WorkerGatewayOperation.AI_REQUEST_RESPONSE ->
                    AiRequestResponseGatewayCodec.execute(request.payload, aiRequestResponseHandler)

                WorkerGatewayOperation.AUDIO_CAPTURE ->
                    WorkerAudioCaptureGatewayCodec.execute(request.payload, identity, workerAudioCaptureHandler)

                WorkerGatewayOperation.WORKSPACE_TEXT_FILE ->
                    WorkerWorkspaceTextFileGatewayCodec.execute(
                        request.payload,
                        identity,
                        workerWorkspaceTextFileHandler,
                    )

                WorkerGatewayOperation.TOOL_EXECUTION -> {
                    val toolRequest = json.decodeFromString<WorkerToolExecutionRequest>(
                        request.payload.decodeToString()
                    )
                    require(toolRequest.executionTarget.workerId == identity.workerId) {
                        "Tool execution request targets another Worker"
                    }
                    val result = parallelToolExecutor.executeParallel(
                        toolCalls = toolRequest.toolCalls,
                        toolContext = ToolExecutionContext(toolRequest.toolContext),
                        runtimeTaskId = null,
                        executor = ConversationRuntimeExecutorIdentity.Worker(identity),
                        expectedTarget = toolRequest.executionTarget,
                        resolvedSecretsByToolCallId = toolRequest.resolvedSecretsByToolCallId,
                    )
                    json.encodeToString(
                        WorkerToolExecutionResponse(
                            results = result.results,
                            returnDirect = result.returnDirect,
                        )
                    ).encodeToByteArray()
                }

                WorkerGatewayOperation.COMMAND_RUNTIME_STATE,
                WorkerGatewayOperation.WORKSPACE_STATE,
                WorkerGatewayOperation.AGENT_SKILL_PACKAGE,
                WorkerGatewayOperation.AGENT_SKILL_IMPORT ->
                    error("Server cannot invoke the Worker-owned Server state operation")
            }
            WorkerGatewayMessage.Response(
                requestId = request.id,
                status = WorkerGatewayMessage.Response.Status.SUCCEEDED,
                payload = payload,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            WorkerGatewayMessage.Response(
                requestId = request.id,
                status = WorkerGatewayMessage.Response.Status.FAILED,
                errorCode = error::class.simpleName ?: "WorkerOperationFailure",
                errorMessage = error.message ?: "Worker operation failed",
            )
        }
}

internal fun workerGatewayWebSocketUrl(serverUrl: String): String {
    val raw = URI(serverUrl.trim().let { if ("://" in it) it else "https://$it" })
    val scheme = when (raw.scheme?.lowercase()) {
        "https", "wss" -> "wss"
        "http", "ws" -> "ws"
        else -> error("Worker Gateway Server address must use HTTPS, WSS, HTTP, or WS")
    }
    val host = raw.host ?: error("Worker Gateway Server address must include a host")
    require(scheme == "wss" || host in workerGatewayLocalHosts) {
        "Remote Worker Gateway requires WSS"
    }
    require(raw.userInfo == null && raw.query == null && raw.fragment == null) {
        "Worker Gateway Server address must not contain credentials, a query, or a fragment"
    }
    require(raw.path.isNullOrEmpty() || raw.path == "/") {
        "Worker Gateway Server address must not contain a path"
    }
    return URI(
        scheme,
        null,
        host,
        raw.port,
        "/worker/ws",
        null,
        null,
    ).toString()
}

private const val MAX_WORKER_GATEWAY_FRAME_BYTES = 128L * 1024 * 1024

private val workerGatewayLocalHosts = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
