package com.gromozeka.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ComputerUseDisplay
import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservation
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
internal enum class DesktopHelperOperation { TARGETS, CAPTURE, EXECUTE, CANCEL }

@Serializable
internal data class DesktopHelperRequest(
    val id: String,
    val operation: DesktopHelperOperation,
    val identity: ConversationRuntimeWorkerIdentity? = null,
    val displayId: ComputerUseDisplayId? = null,
    val maxLongEdge: Int = 2048,
    val observation: ComputerUseObservationReference? = null,
    val actions: List<ComputerUseAction> = emptyList(),
)

@Serializable
internal data class DesktopHelperResponse(
    val id: String,
    val displays: List<ComputerUseDisplay>? = null,
    val observation: ComputerUseObservationReference? = null,
    val png: String? = null,
    val error: String? = null,
    val mutationMayHaveStarted: Boolean = false,
)

internal interface DesktopHelperChannel : AutoCloseable {
    fun send(message: String)
    fun receive(interruptionCheck: () -> Unit = {}): String
}

internal class DesktopHelperConnection(
    private val channel: DesktopHelperChannel,
    val generation: String = UUID.randomUUID().toString(),
    private val validateSession: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()

    fun call(
        request: DesktopHelperRequest,
        interruptionCheck: () -> Unit = {},
    ): DesktopHelperResponse = synchronized(lock) {
        validateSession()
        interruptionCheck()
        var sent = false
        var cancellation: Throwable? = null
        var cancellationAt = 0L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        try {
            sent = true
            channel.send(DesktopHelperJson.encodeToString(request))
            val response = DesktopHelperJson.decodeFromString<DesktopHelperResponse>(channel.receive {
                validateSession()
                if (cancellation == null) {
                    try {
                        interruptionCheck()
                        check(System.nanoTime() < deadline) { "Desktop helper request timed out" }
                    } catch (error: Exception) {
                        cancellation = error
                        cancellationAt = System.nanoTime()
                        channel.send(DesktopHelperJson.encodeToString(
                            DesktopHelperRequest(request.id, DesktopHelperOperation.CANCEL),
                        ))
                    }
                } else if (System.nanoTime() - cancellationAt > TimeUnit.SECONDS.toNanos(5)) {
                    throw checkNotNull(cancellation)
                }
            })
            check(response.id == request.id) { "Unexpected desktop helper response" }
            cancellation?.let { throw it }
            validateSession()
            response.error?.let { message ->
                if (request.operation == DesktopHelperOperation.EXECUTE) {
                    throw ComputerUseBackendExecutionException(
                        response.mutationMayHaveStarted, IllegalStateException(message),
                    )
                }
                error(message)
            }
            response
        } catch (error: Exception) {
            close()
            if (error is CancellationException || error is ComputerUseBackendExecutionException) throw error
            if (request.operation == DesktopHelperOperation.EXECUTE) {
                throw ComputerUseBackendExecutionException(sent, error)
            }
            throw error
        }
    }

    override fun close() = channel.close()
}

internal interface DesktopSessionProvider : AutoCloseable {
    fun current(): DesktopHelperConnection
}

internal class WindowsDesktopComputerUseBackend(
    private val sessions: DesktopSessionProvider,
) : ComputerUseBackend, AutoCloseable {
    override val available: Boolean = true
    override val unavailableReason: String? = null

    override fun targets(): List<ComputerUseDisplay> {
        val connection = sessions.current()
        return checkNotNull(connection.call(request(DesktopHelperOperation.TARGETS)).displays).map {
            it.copy(id = connection.expose(it.id))
        }
    }

    override fun capture(
        identity: ConversationRuntimeWorkerIdentity,
        displayId: ComputerUseDisplayId,
        maxLongEdge: Int,
    ): ComputerUseObservation {
        val connection = sessions.current()
        val response = connection.call(request(DesktopHelperOperation.CAPTURE).copy(
            identity = identity,
            displayId = connection.resolve(displayId),
            maxLongEdge = maxLongEdge,
        ))
        val observation = checkNotNull(response.observation)
        check(observation.workerId == identity.workerId && observation.workerSessionId == identity.sessionId)
        check(observation.displayId == connection.resolve(displayId))
        return ComputerUseObservation(
            observation.copy(displayId = connection.expose(observation.displayId)),
            Base64.getDecoder().decode(checkNotNull(response.png)),
        )
    }

    override fun execute(
        observation: ComputerUseObservationReference,
        actions: List<ComputerUseAction>,
        interruptionCheck: () -> Unit,
    ) {
        val connection = sessions.current()
        val nativeDisplay = connection.resolve(observation.displayId)
        connection.call(request(DesktopHelperOperation.EXECUTE).copy(
            observation = observation.copy(displayId = nativeDisplay),
            actions = actions,
        ), interruptionCheck)
    }

    override fun close() = sessions.close()

    private fun request(operation: DesktopHelperOperation) =
        DesktopHelperRequest(UUID.randomUUID().toString(), operation)

    private fun DesktopHelperConnection.expose(id: ComputerUseDisplayId) =
        ComputerUseDisplayId("$generation/${id.value}")

    private fun DesktopHelperConnection.resolve(id: ComputerUseDisplayId): ComputerUseDisplayId {
        require(id.value.startsWith("$generation/")) {
            "Windows desktop session changed or its helper restarted; list displays and capture a fresh observation"
        }
        return ComputerUseDisplayId(id.value.removePrefix("$generation/"))
    }
}

internal fun serveDesktopHelper(
    channel: DesktopHelperChannel,
    backend: ComputerUseBackend,
    validateDesktop: () -> Unit = {},
) {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "desktop-helper-action").apply { isDaemon = true } }
    val active = AtomicReference<Pair<String, AtomicBoolean>?>()
    try {
        while (true) {
            val request = DesktopHelperJson.decodeFromString<DesktopHelperRequest>(channel.receive())
            require(request.id.isNotBlank())
            if (request.operation == DesktopHelperOperation.CANCEL) {
                active.get()?.takeIf { it.first == request.id }?.second?.set(true)
                continue
            }
            val cancelled = AtomicBoolean()
            check(active.compareAndSet(null, request.id to cancelled)) { "Concurrent desktop helper request" }
            executor.execute {
                val check = {
                    if (cancelled.get()) throw CancellationException("Desktop action cancelled")
                    validateDesktop()
                }
                val response = try {
                    check()
                    when (request.operation) {
                        DesktopHelperOperation.TARGETS -> DesktopHelperResponse(request.id, displays = backend.targets())
                        DesktopHelperOperation.CAPTURE -> {
                            val observed = backend.capture(
                                requireNotNull(request.identity), requireNotNull(request.displayId), request.maxLongEdge,
                            )
                            check()
                            DesktopHelperResponse(
                                request.id, observation = observed.reference,
                                png = Base64.getEncoder().encodeToString(observed.png),
                            )
                        }
                        DesktopHelperOperation.EXECUTE -> {
                            require(request.actions.size in 1..32)
                            backend.execute(requireNotNull(request.observation), request.actions, check)
                            DesktopHelperResponse(request.id)
                        }
                        DesktopHelperOperation.CANCEL -> error("Unexpected cancellation")
                    }
                } catch (error: Exception) {
                    DesktopHelperResponse(
                        request.id, error = error.message ?: error.javaClass.simpleName,
                        mutationMayHaveStarted = if (error is ComputerUseBackendExecutionException) {
                            error.mutationStarted
                        } else request.operation == DesktopHelperOperation.EXECUTE,
                    )
                }
                active.set(null)
                runCatching { channel.send(DesktopHelperJson.encodeToString(response)) }
                    .onFailure { channel.close() }
            }
        }
    } finally {
        active.get()?.second?.set(true)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        channel.close()
    }
}

internal val DesktopHelperJson = Json { encodeDefaults = true }
