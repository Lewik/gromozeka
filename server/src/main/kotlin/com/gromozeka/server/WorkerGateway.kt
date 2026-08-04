package com.gromozeka.server

import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.WorkerConnectionRevocationService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.McpServerRevision
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WORKER_GATEWAY_PROTOCOL_VERSION
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.shared.uuid.uuid7
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

@Service
class WorkerGatewayAuthenticationService(
    private val enrollmentRepository: WorkerEnrollmentRepository,
) {
    internal suspend fun authenticate(authorization: String?): AuthenticatedWorkerGateway? {
        val credential = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.length in 40..128 }
            ?: return null
        val credentialHash = sha256(credential)
        return enrollmentRepository.authenticateGatewayCredential(credentialHash)
            ?.let { AuthenticatedWorkerGateway(it, credentialHash) }
    }

    internal suspend fun isActive(principal: AuthenticatedWorkerGateway): Boolean =
        enrollmentRepository.authenticateGatewayCredential(principal.credentialHash)?.id == principal.worker.id

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}

internal class AuthenticatedWorkerGateway(
    val worker: WorkerResource,
    internal val credentialHash: String,
)

@Service
class WorkerGatewaySessionRegistry : WorkerConnectionRevocationService {
    private val sessions = ConcurrentHashMap<ConversationRuntimeWorkerId, WorkerGatewaySession>()

    fun attach(session: WorkerGatewaySession): Boolean =
        sessions.putIfAbsent(session.identity.workerId, session) == null

    fun detach(session: WorkerGatewaySession): Boolean =
        sessions.remove(session.identity.workerId, session)

    fun find(workerId: ConversationRuntimeWorkerId): WorkerGatewaySession? =
        sessions[workerId]

    override fun disconnectRevokedWorker(workerId: ConversationRuntimeWorkerId) {
        sessions[workerId]?.requestDisconnect("Worker access was revoked")
    }

    suspend fun execute(
        target: ConversationRuntimeWorkerIdentity,
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        timeout: Duration,
    ): ByteArray {
        val session = sessions[target.workerId]
            ?: error("Worker Gateway is offline: ${target.workerId.value}")
        require(session.identity == target) {
            "Worker Gateway session changed before request dispatch: expected=$target actual=${session.identity}"
        }
        return session.execute(operation, payload, timeout)
    }
}

class WorkerGatewaySession(
    val identity: ConversationRuntimeWorkerIdentity,
) {
    private val outgoing = Channel<WorkerGatewayMessage>(OUTGOING_BUFFER_SIZE)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<WorkerGatewayMessage.Response>>()
    private val recentlyClosedRequestIds = LinkedHashSet<String>()
    private val recentlyClosedRequestIdsLock = Any()
    private val inFlight = Semaphore(MAX_IN_FLIGHT_REQUESTS)
    private val requestedDisconnect = CompletableDeferred<String>()

    suspend fun execute(
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        timeout: Duration,
    ): ByteArray =
        inFlight.withPermit {
            require(payload.size <= MAX_GATEWAY_PAYLOAD_BYTES) {
                "Worker Gateway request exceeds the configured limit: ${payload.size} > $MAX_GATEWAY_PAYLOAD_BYTES bytes"
            }
            val request = WorkerGatewayMessage.Request(
                id = uuid7(),
                operation = operation,
                payload = payload,
            )
            val response = CompletableDeferred<WorkerGatewayMessage.Response>()
            check(pending.putIfAbsent(request.id, response) == null) {
                "Duplicate Worker Gateway request id: ${request.id}"
            }
            try {
                outgoing.send(request)
                val result = try {
                    withTimeout(timeout.toMillis()) {
                        response.await()
                    }
                } catch (error: CancellationException) {
                    requestCancellation(request.id)
                    if (error !is TimeoutCancellationException) throw error
                    throw IllegalStateException(
                        "Worker Gateway request ${request.id} timed out; the outcome is unknown and " +
                            "Gromozeka will not retry it automatically",
                        error,
                    )
                } catch (error: Throwable) {
                    requestCancellation(request.id)
                    throw IllegalStateException(
                        "Worker Gateway request ${request.id} failed before a response was received; " +
                            "the outcome is unknown and Gromozeka will not retry it automatically",
                        error,
                    )
                }
                if (result.status == WorkerGatewayMessage.Response.Status.FAILED) {
                    error("Worker operation failed [${result.errorCode}]: ${result.errorMessage}")
                }
                requireNotNull(result.payload)
            } finally {
                rememberClosed(request.id)
                pending.remove(request.id, response)
            }
        }

    private fun requestCancellation(requestId: String) {
        outgoing.trySend(WorkerGatewayMessage.CancelRequest(requestId))
    }

    suspend fun send(message: WorkerGatewayMessage) {
        outgoing.send(message)
    }

    fun outgoingMessages(): Channel<WorkerGatewayMessage> = outgoing

    internal fun accept(response: WorkerGatewayMessage.Response): WorkerGatewayResponseAcceptance {
        pending[response.requestId]?.let { pendingResponse ->
            return if (pendingResponse.complete(response)) {
                WorkerGatewayResponseAcceptance.ACCEPTED
            } else {
                WorkerGatewayResponseAcceptance.LATE
            }
        }
        return synchronized(recentlyClosedRequestIdsLock) {
            if (response.requestId in recentlyClosedRequestIds) {
                WorkerGatewayResponseAcceptance.LATE
            } else {
                WorkerGatewayResponseAcceptance.UNKNOWN
            }
        }
    }

    fun requestDisconnect(reason: String) {
        requestedDisconnect.complete(reason)
    }

    suspend fun awaitRequestedDisconnect(): String = requestedDisconnect.await()

    fun close(cause: Throwable) {
        outgoing.close(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private fun rememberClosed(requestId: String) = synchronized(recentlyClosedRequestIdsLock) {
        recentlyClosedRequestIds += requestId
        while (recentlyClosedRequestIds.size > MAX_RECENTLY_CLOSED_REQUESTS) {
            recentlyClosedRequestIds.iterator().also { iterator ->
                iterator.next()
                iterator.remove()
            }
        }
    }

    private companion object {
        const val OUTGOING_BUFFER_SIZE = 256
        const val MAX_IN_FLIGHT_REQUESTS = 64
        const val MAX_RECENTLY_CLOSED_REQUESTS = 1_024
        const val MAX_GATEWAY_PAYLOAD_BYTES = 64 * 1024 * 1024
    }
}

internal enum class WorkerGatewayResponseAcceptance {
    ACCEPTED,
    LATE,
    UNKNOWN,
}

@Service
class WorkerGatewayService(
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
    private val sessionRegistry: WorkerGatewaySessionRegistry,
    private val mcpServerRepository: McpServerRepository,
    serverRequestHandlers: List<WorkerGatewayServerRequestHandler>,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val authenticationService: WorkerGatewayAuthenticationService,
) {
    private val log = KLoggers.logger(this)
    private val serverRequestHandlersByOperation =
        serverRequestHandlers.associateBy(WorkerGatewayServerRequestHandler::operation).also {
            require(it.size == serverRequestHandlers.size) {
                "Worker Gateway Server operations must have exactly one handler"
            }
        }

    internal suspend fun handle(
        socket: DefaultWebSocketServerSession,
        authenticatedWorker: AuthenticatedWorkerGateway,
    ) {
        val worker = authenticatedWorker.worker
        val hello = try {
            withTimeout(HELLO_TIMEOUT.toMillis()) {
                socket.receiveMessage()
            } as? WorkerGatewayMessage.Hello
                ?: return socket.fail("EXPECTED_HELLO", "The first Worker Gateway message must be hello")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return socket.fail("INVALID_HELLO", "Worker Gateway hello was not received")
        }
        if (hello.protocolVersion != WORKER_GATEWAY_PROTOCOL_VERSION) {
            return socket.fail(
                "UNSUPPORTED_PROTOCOL",
                "Worker Gateway protocol ${hello.protocolVersion} is unsupported",
            )
        }

        val initialRegistration = hello.registration.copy(
            lastHeartbeatAt = Clock.System.now(),
            stoppedAt = null,
        )
        if (initialRegistration.identity.workerId != worker.id) {
            return socket.fail("WORKER_ID_MISMATCH", "Worker credential does not match the declared Worker")
        }
        if (!authenticationService.isActive(authenticatedWorker)) {
            return socket.fail("WORKER_AUTHENTICATION_REVOKED", "Worker credential is no longer active")
        }

        val initialAiCatalog = aiConfigurationProvider.snapshot
        socket.sendMessage(
            WorkerGatewayMessage.Welcome(
                heartbeatIntervalSeconds = HEARTBEAT_INTERVAL.seconds,
                aiCatalogSnapshot = initialAiCatalog,
                mcpServers = mcpServerRepository.listByWorker(worker.id),
            )
        )
        val ready = try {
            withTimeout(READY_TIMEOUT.toMillis()) {
                socket.receiveMessage()
            } as? WorkerGatewayMessage.Ready
                ?: return socket.fail("EXPECTED_READY", "Worker Gateway must synchronize before becoming ready")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return socket.fail("INVALID_READY", "Worker Gateway readiness was not received")
        }
        ready.refreshAvailableMcpServers.forEach { reference ->
            markMcpRefreshAvailable(worker.id, reference)
        }
        val registration = initialRegistration.copy(tools = ready.tools)
        val gatewaySession = WorkerGatewaySession(registration.identity)
        if (!sessionRegistry.attach(gatewaySession)) {
            return socket.fail("WORKER_ALREADY_CONNECTED", "Worker already has an active Gateway session")
        }

        var registered = false
        try {
            val accepted = workerRegistry.register(
                registration = registration,
                staleBefore = Clock.System.now().minus(HEARTBEAT_STALE_AFTER),
            )
            if (!accepted) {
                return socket.fail(
                    "WORKER_SESSION_CONFLICT",
                    "Worker is already owned by another active runtime session",
                )
            }
            registered = true
            log.info {
                "Worker Gateway connected: worker=${registration.identity.workerId.value} " +
                    "session=${registration.identity.sessionId.value}"
            }
            coroutineScope {
                val writer = launch {
                    for (message in gatewaySession.outgoingMessages()) {
                        socket.sendMessage(message)
                    }
                }
                val authenticationMonitor = launch {
                    while (isActive) {
                        delay(AUTHENTICATION_RECHECK_INTERVAL.toMillis())
                        if (!authenticationService.isActive(authenticatedWorker)) {
                            socket.close(
                                CloseReason(
                                    CloseReason.Codes.VIOLATED_POLICY,
                                    "Worker credential is no longer active",
                                )
                            )
                            break
                        }
                    }
                }
                val requestedDisconnectMonitor = launch {
                    val reason = gatewaySession.awaitRequestedDisconnect()
                    socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
                }
                val aiCatalogUpdates = launch {
                    aiConfigurationProvider.snapshotFlow
                        .filterNotNull()
                        .filter { it.revision > initialAiCatalog.revision }
                        .collect {
                            gatewaySession.send(WorkerGatewayMessage.AiCatalogUpdated(it))
                        }
                }
                try {
                    for (frame in socket.incoming) {
                        if (!authenticationService.isActive(authenticatedWorker)) {
                            return@coroutineScope socket.fail(
                                "WORKER_AUTHENTICATION_REVOKED",
                                "Worker credential is no longer active",
                            )
                        }
                        val message = frame.decodeMessage()
                            ?: return@coroutineScope socket.fail(
                                "INVALID_FRAME",
                                "Worker Gateway accepts binary protocol frames only",
                            )
                        when (message) {
                            is WorkerGatewayMessage.Heartbeat -> {
                                val heartbeatAccepted = workerRegistry.heartbeat(
                                    identity = registration.identity,
                                    at = Clock.System.now(),
                                )
                                if (!heartbeatAccepted) {
                                    return@coroutineScope socket.fail(
                                        "WORKER_SESSION_LOST",
                                        "Worker runtime session is no longer current",
                                    )
                                }
                            }

                            is WorkerGatewayMessage.Response -> {
                                when (gatewaySession.accept(message)) {
                                    WorkerGatewayResponseAcceptance.ACCEPTED -> Unit
                                    WorkerGatewayResponseAcceptance.LATE -> log.info {
                                        "Ignoring late Worker response: worker=${registration.identity.workerId.value} " +
                                            "request=${message.requestId}"
                                    }
                                    WorkerGatewayResponseAcceptance.UNKNOWN -> {
                                        return@coroutineScope socket.fail(
                                            "UNKNOWN_RESPONSE",
                                            "Worker returned a response for an unknown request",
                                        )
                                    }
                                }
                            }

                            is WorkerGatewayMessage.Request -> launch {
                                workerRequestConcurrency.withPermit {
                                    gatewaySession.send(
                                        executeWorkerRequest(registration.identity, message)
                                    )
                                }
                            }

                            is WorkerGatewayMessage.CancelRequest ->
                                return@coroutineScope socket.fail(
                                    "UNEXPECTED_MESSAGE",
                                    "Worker cannot cancel a Server-owned Gateway request",
                                )

                            is WorkerGatewayMessage.ToolCatalogUpdated -> {
                                val updated = workerRegistry.updateTools(
                                    identity = registration.identity,
                                    tools = message.tools,
                                    at = Clock.System.now(),
                                )
                                if (!updated) {
                                    return@coroutineScope socket.fail(
                                        "WORKER_SESSION_LOST",
                                        "Worker runtime session is no longer current",
                                    )
                                }
                            }

                            is WorkerGatewayMessage.McpServerRefreshAvailable -> {
                                markMcpRefreshAvailable(
                                    workerId = registration.identity.workerId,
                                    reference = McpServerRevision(
                                        serverId = message.serverId,
                                        revision = message.expectedRevision,
                                    ),
                                )
                            }

                            is WorkerGatewayMessage.Hello ->
                                return@coroutineScope socket.fail(
                                    "DUPLICATE_HELLO",
                                    "Worker Gateway hello was already accepted",
                                )

                            is WorkerGatewayMessage.Welcome,
                            is WorkerGatewayMessage.AiCatalogUpdated,
                            is WorkerGatewayMessage.Ready,
                            is WorkerGatewayMessage.Failure ->
                                return@coroutineScope socket.fail(
                                    "UNEXPECTED_MESSAGE",
                                    "Worker sent a Server-only Gateway message",
                                )
                        }
                    }
                } finally {
                    gatewaySession.close(
                        IllegalStateException(
                            "Worker Gateway disconnected: worker=${registration.identity.workerId.value}"
                        )
                    )
                    authenticationMonitor.cancelAndJoin()
                    requestedDisconnectMonitor.cancelAndJoin()
                    aiCatalogUpdates.cancelAndJoin()
                    writer.cancelAndJoin()
                }
            }
        } finally {
            sessionRegistry.detach(gatewaySession)
            if (registered) {
                workerRegistry.unregister(registration.identity, Clock.System.now())
            }
            log.info {
                "Worker Gateway disconnected: worker=${registration.identity.workerId.value} " +
                    "session=${registration.identity.sessionId.value}"
            }
        }
    }

    private suspend fun executeWorkerRequest(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): WorkerGatewayMessage.Response =
        runCatching {
            val handler = serverRequestHandlersByOperation[request.operation]
                ?: error("Worker cannot invoke unsupported Server operation ${request.operation}")
            WorkerGatewayMessage.Response(
                requestId = request.id,
                status = WorkerGatewayMessage.Response.Status.SUCCEEDED,
                payload = handler.execute(identity, request),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            WorkerGatewayMessage.Response(
                requestId = request.id,
                status = WorkerGatewayMessage.Response.Status.FAILED,
                errorCode = error::class.simpleName ?: "WorkerRequestFailure",
                errorMessage = error.message ?: "Worker request failed",
            )
        }

    private suspend fun markMcpRefreshAvailable(
        workerId: ConversationRuntimeWorkerId,
        reference: McpServerRevision,
    ) {
        val server = mcpServerRepository.find(reference.serverId)
            ?: error("Worker reported an unknown MCP server: ${reference.serverId.value}")
        require(server.config.workerId == workerId) {
            "Worker ${workerId.value} cannot update MCP server ${reference.serverId.value} " +
                "assigned to ${server.config.workerId.value}"
        }
        if (server.revision == reference.revision) {
            mcpServerRepository.markRefreshAvailable(reference.serverId, reference.revision)
        }
    }

    private suspend fun DefaultWebSocketServerSession.receiveMessage(): WorkerGatewayMessage {
        val frame = incoming.receive()
        return frame.decodeMessage()
            ?: error("Worker Gateway accepts binary protocol frames only")
    }

    private suspend fun DefaultWebSocketServerSession.sendMessage(message: WorkerGatewayMessage) {
        send(Frame.Binary(true, WorkerGatewayCodec.encode(message)))
    }

    private suspend fun DefaultWebSocketServerSession.fail(
        code: String,
        message: String,
    ) {
        runCatching {
            sendMessage(WorkerGatewayMessage.Failure(code, message))
        }
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, code))
    }

    private fun Frame.decodeMessage(): WorkerGatewayMessage? =
        (this as? Frame.Binary)?.let { WorkerGatewayCodec.decode(it.readBytes()) }

    private fun Instant.minus(duration: Duration): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() - duration.toMillis())

    private companion object {
        val HELLO_TIMEOUT: Duration = Duration.ofSeconds(15)
        val READY_TIMEOUT: Duration = Duration.ofMinutes(5)
        val AUTHENTICATION_RECHECK_INTERVAL: Duration = Duration.ofSeconds(10)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(10)
        val HEARTBEAT_STALE_AFTER: Duration = Duration.ofSeconds(30)
        val workerRequestConcurrency = Semaphore(64)
    }
}

internal val authenticatedWorkerGatewayKey =
    AttributeKey<AuthenticatedWorkerGateway>("AuthenticatedWorkerGateway")

internal fun workerGatewayAuthentication(
    authenticationService: WorkerGatewayAuthenticationService,
) = createRouteScopedPlugin("WorkerGatewayAuthentication") {
    onCall { call ->
        val worker = authenticationService.authenticate(call.request.header(HttpHeaders.Authorization))
        if (worker == null) {
            throw HttpAuthenticationException(
                status = HttpStatusCode.Unauthorized,
                publicMessage = "Worker authentication required",
                challenge = """Bearer realm="gromozeka-worker"""",
            )
        }
        call.attributes.put(authenticatedWorkerGatewayKey, worker)
    }
}
