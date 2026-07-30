package com.gromozeka.server

import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
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
import io.ktor.server.response.respondText
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
class WorkerGatewayAuthenticationService(
    private val enrollmentRepository: WorkerEnrollmentRepository,
) {
    suspend fun authenticate(authorization: String?): WorkerResource? {
        val credential = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.length in 40..128 }
            ?: return null
        return enrollmentRepository.authenticateGatewayCredential(sha256(credential))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}

@Service
class WorkerGatewaySessionRegistry {
    private val sessions = ConcurrentHashMap<ConversationRuntimeWorkerId, WorkerGatewaySession>()

    fun attach(session: WorkerGatewaySession): Boolean =
        sessions.putIfAbsent(session.identity.workerId, session) == null

    fun detach(session: WorkerGatewaySession): Boolean =
        sessions.remove(session.identity.workerId, session)

    fun find(workerId: ConversationRuntimeWorkerId): WorkerGatewaySession? =
        sessions[workerId]

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
    private val inFlight = Semaphore(MAX_IN_FLIGHT_REQUESTS)

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
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
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
                pending.remove(request.id, response)
            }
        }

    suspend fun send(message: WorkerGatewayMessage) {
        outgoing.send(message)
    }

    fun outgoingMessages(): Channel<WorkerGatewayMessage> = outgoing

    fun accept(response: WorkerGatewayMessage.Response): Boolean =
        pending[response.requestId]?.complete(response) == true

    fun close(cause: Throwable) {
        outgoing.close(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private companion object {
        const val OUTGOING_BUFFER_SIZE = 256
        const val MAX_IN_FLIGHT_REQUESTS = 64
        const val MAX_GATEWAY_PAYLOAD_BYTES = 64 * 1024 * 1024
    }
}

@Service
class WorkerGatewayService(
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
    private val sessionRegistry: WorkerGatewaySessionRegistry,
) {
    private val log = KLoggers.logger(this)

    suspend fun handle(
        socket: DefaultWebSocketServerSession,
        authenticatedWorker: WorkerResource,
    ) {
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

        val registration = hello.registration.copy(
            lastHeartbeatAt = Clock.System.now(),
            stoppedAt = null,
        )
        if (registration.identity.workerId != authenticatedWorker.id) {
            return socket.fail("WORKER_ID_MISMATCH", "Worker credential does not match the declared Worker")
        }

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
                gatewaySession.send(
                    WorkerGatewayMessage.Welcome(
                        heartbeatIntervalSeconds = HEARTBEAT_INTERVAL.seconds,
                    )
                )
                try {
                    for (frame in socket.incoming) {
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
                                if (!gatewaySession.accept(message)) {
                                    return@coroutineScope socket.fail(
                                        "UNKNOWN_RESPONSE",
                                        "Worker returned a response for an unknown or expired request",
                                    )
                                }
                            }

                            is WorkerGatewayMessage.Hello ->
                                return@coroutineScope socket.fail(
                                    "DUPLICATE_HELLO",
                                    "Worker Gateway hello was already accepted",
                                )

                            is WorkerGatewayMessage.Welcome,
                            is WorkerGatewayMessage.Request,
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
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(10)
        val HEARTBEAT_STALE_AFTER: Duration = Duration.ofSeconds(30)
    }
}

internal val authenticatedWorkerGatewayKey = AttributeKey<WorkerResource>("AuthenticatedWorkerGateway")

internal fun workerGatewayAuthentication(
    authenticationService: WorkerGatewayAuthenticationService,
) = createRouteScopedPlugin("WorkerGatewayAuthentication") {
    onCall { call ->
        val worker = authenticationService.authenticate(call.request.header(HttpHeaders.Authorization))
        if (worker == null) {
            call.respondText(
                """{"message":"Worker authentication required"}""",
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.Unauthorized,
            )
            return@onCall
        }
        call.attributes.put(authenticatedWorkerGatewayKey, worker)
    }
}
