package com.gromozeka.worker

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
import com.gromozeka.remote.protocol.WORKER_GATEWAY_PROTOCOL_VERSION
import com.gromozeka.remote.protocol.WorkerGatewayCodec
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
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
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
                var consecutiveFailures = 0L
                var lastFailureLogAtNanos: Long? = null
                while (isActive) {
                    try {
                        connect()
                        consecutiveFailures = 0
                        lastFailureLogAtNanos = null
                        if (isActive) {
                            log.warn {
                                "Worker Gateway disconnected; reconnecting: " +
                                    "worker=${identity.workerId.value}"
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        consecutiveFailures += 1
                        val now = System.nanoTime()
                        if (
                            lastFailureLogAtNanos == null ||
                            now - lastFailureLogAtNanos >= FAILURE_LOG_INTERVAL.inWholeNanoseconds
                        ) {
                            lastFailureLogAtNanos = now
                            if (error.isExpectedConnectionFailure()) {
                                log.warn {
                                    "Worker Gateway is unavailable " +
                                        "(attempt $consecutiveFailures): " +
                                        "worker=${identity.workerId.value} " +
                                        "error=${error::class.simpleName}: ${error.message}"
                                }
                            } else {
                                log.warn(error) {
                                    "Worker Gateway connection failed " +
                                        "(attempt $consecutiveFailures): " +
                                        "worker=${identity.workerId.value} error=${error.message}"
                                }
                            }
                        }
                    }
                    if (isActive) {
                        delay(properties.reconnectDelaySeconds.seconds)
                    }
                }
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

    private suspend fun connect() {
        val socket = client.webSocketSession {
            url(gatewayUrl)
            header(HttpHeaders.Authorization, "Bearer ${properties.credential}")
        }
        try {
            val connectedAt = Clock.System.now()
            socket.sendMessage(
                WorkerGatewayMessage.Hello(
                    ConversationRuntimeWorkerRegistration(
                        identity = identity,
                        capabilities = capabilities,
                        tools = outbound.currentTools(),
                        environmentProfile = environmentProfile,
                        version = currentWorkerVersion(),
                        startedAt = startedAt,
                        lastHeartbeatAt = connectedAt,
                    )
                )
            )
            val welcome = withTimeout(HANDSHAKE_TIMEOUT) {
                socket.receiveMessage()
            }
            when (welcome) {
                is WorkerGatewayMessage.Welcome -> {
                    require(welcome.protocolVersion == WORKER_GATEWAY_PROTOCOL_VERSION) {
                        "Server selected unsupported Worker Gateway protocol ${welcome.protocolVersion}"
                    }
                    aiConfigurationProvider.synchronize(welcome.aiCatalogSnapshot)
                    val refreshAvailable = mcpConfigurationService.synchronize(welcome.mcpServers)
                    val runtimeTools = workerToolCatalog.snapshot()
                    outbound.replaceBeforeReady(runtimeTools)
                    socket.sendMessage(
                        WorkerGatewayMessage.Ready(
                            tools = runtimeTools,
                            refreshAvailableMcpServers = refreshAvailable,
                        )
                    )
                }

                is WorkerGatewayMessage.Failure ->
                    error("Worker Gateway rejected connection: ${welcome.code}: ${welcome.message}")

                else -> error("Worker Gateway did not return welcome")
            }
            log.info {
                "Worker Gateway connected: worker=${identity.workerId.value} url=$gatewayUrl"
            }
            coroutineScope {
                val outgoing = Channel<WorkerGatewayMessage>(OUTGOING_BUFFER_SIZE)
                outbound.attach(outgoing)
                val writer = launch {
                    for (message in outgoing) {
                        socket.sendMessage(message)
                    }
                }
                val heartbeatJob = launch {
                    while (isActive && socket.isActive) {
                        delay(welcome.heartbeatIntervalSeconds.seconds)
                        outgoing.send(WorkerGatewayMessage.Heartbeat(Clock.System.now()))
                    }
                }
                val requestJobs = ConcurrentHashMap<String, Job>()
                try {
                    for (frame in socket.incoming) {
                        val message = frame.decodeMessage()
                            ?: error("Worker Gateway Server sent a non-binary frame")
                        when (message) {
                            is WorkerGatewayMessage.Request -> {
                                val requestJob = launch(start = CoroutineStart.LAZY) {
                                    requestConcurrency.withPermit {
                                        outgoing.send(operationHandler.execute(identity, message))
                                    }
                                }
                                check(requestJobs.putIfAbsent(message.id, requestJob) == null) {
                                    "Worker Gateway Server reused request id ${message.id}"
                                }
                                requestJob.invokeOnCompletion { requestJobs.remove(message.id, requestJob) }
                                requestJob.start()
                            }

                            is WorkerGatewayMessage.CancelRequest ->
                                requestJobs[message.requestId]?.cancel(
                                    CancellationException(
                                        "Worker Gateway request ${message.requestId} was cancelled by Server"
                                    )
                                )

                            is WorkerGatewayMessage.Response -> {
                                if (!outbound.accept(message)) {
                                    error("Worker Gateway Server returned a response for an unknown request")
                                }
                            }

                            is WorkerGatewayMessage.AiCatalogUpdated -> {
                                aiConfigurationProvider.synchronize(message.snapshot)
                                outbound.updateAdvertisedTools(workerToolCatalog.snapshot())
                            }

                            is WorkerGatewayMessage.Failure ->
                                error("Worker Gateway failed: ${message.code}: ${message.message}")

                            else -> error(
                                "Worker Gateway Server sent an unexpected ${message::class.simpleName}"
                            )
                        }
                    }
                } finally {
                    outbound.detach(outgoing)
                    val activeRequests = requestJobs.values.toList()
                    activeRequests.forEach { it.cancel() }
                    activeRequests.joinAll()
                    outgoing.close()
                    heartbeatJob.cancelAndJoin()
                    writer.cancelAndJoin()
                }
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendMessage(
        message: WorkerGatewayMessage,
    ) {
        send(Frame.Binary(true, WorkerGatewayCodec.encode(message)))
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveMessage():
        WorkerGatewayMessage {
        val frame = incoming.receive()
        return frame.decodeMessage()
            ?: error("Worker Gateway Server sent a non-binary frame")
    }

    private fun Frame.decodeMessage(): WorkerGatewayMessage? =
        (this as? Frame.Binary)?.let { WorkerGatewayCodec.decode(it.readBytes()) }

    private fun Throwable.isExpectedConnectionFailure(): Boolean =
        generateSequence(this, Throwable::cause).any {
            it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException ||
                it is java.net.SocketTimeoutException ||
                it is java.net.UnknownHostException ||
                it is java.nio.channels.UnresolvedAddressException
        }

    private companion object {
        val HANDSHAKE_TIMEOUT = 15.seconds
        val FAILURE_LOG_INTERVAL = 1.minutes
        const val OUTGOING_BUFFER_SIZE = 256
        val requestConcurrency = Semaphore(64)
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
                    require(controlRequest.target == identity) {
                        "Worker control request targets another Worker session"
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
                WorkerGatewayOperation.AGENT_SKILL_PACKAGE ->
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
