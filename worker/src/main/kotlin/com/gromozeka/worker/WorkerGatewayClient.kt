package com.gromozeka.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.remote.protocol.WORKER_GATEWAY_PROTOCOL_VERSION
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
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
        install(WebSockets)
    }
    private val lifecycleLock = Any()
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
            running = true
            connectionJob = scope.launch {
                while (isActive) {
                    try {
                        connect()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn(error) {
                            "Worker Gateway connection failed: worker=${identity.workerId.value} " +
                                "error=${error.message}"
                        }
                    }
                    if (isActive) {
                        delay(properties.reconnectDelaySeconds.seconds)
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
                try {
                    for (frame in socket.incoming) {
                        val message = frame.decodeMessage()
                            ?: error("Worker Gateway Server sent a non-binary frame")
                        when (message) {
                            is WorkerGatewayMessage.Request -> launch {
                                requestConcurrency.withPermit {
                                    outgoing.send(operationHandler.execute(identity, message))
                                }
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

    private companion object {
        val HANDSHAKE_TIMEOUT = 15.seconds
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
            }
            WorkerGatewayMessage.Response(
                requestId = request.id,
                status = WorkerGatewayMessage.Response.Status.SUCCEEDED,
                payload = payload,
            )
        }.getOrElse { error ->
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

private val workerGatewayLocalHosts = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
