package com.gromozeka.worker.runtime

import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.remote.protocol.WORKER_GATEWAY_PROTOCOL_VERSION
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

fun interface WorkerRequestHandler {
    suspend fun execute(request: WorkerGatewayMessage.Request): WorkerGatewayMessage.Response
}

class WorkerGatewayRuntime(
    private val transport: WorkerGatewayTransport,
    private val registration: () -> ConversationRuntimeWorkerRegistration,
    private val outbound: WorkerGatewayOutbound,
    private val handler: WorkerRequestHandler,
    private val prepare: suspend (WorkerGatewayMessage.Welcome) -> WorkerGatewayMessage.Ready,
    private val updateCatalog: suspend (WorkerGatewayMessage.AiCatalogUpdated) -> Unit,
    private val reconnectDelay: Duration = 5.seconds,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onFailure: (Throwable, Long) -> Unit = { _, _ -> },
) {
    private val requestConcurrency = Semaphore(64)

    init {
        require(reconnectDelay in 1.seconds..60.seconds) { "Worker reconnect delay must be between 1 and 60 seconds" }
    }

    suspend fun run(): Unit = coroutineScope {
        var failures = 0L
        var lastFailure: TimeSource.Monotonic.ValueTimeMark? = null
        while (isActive) {
            try {
                connect()
                failures = 0
                lastFailure = null
                onDisconnected()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failures++
                if (lastFailure == null || lastFailure.elapsedNow() >= 1.minutes) {
                    lastFailure = TimeSource.Monotonic.markNow()
                    onFailure(error, failures)
                }
            }
            delay(reconnectDelay)
        }
    }

    internal suspend fun connect() {
        val connection = transport.connect()
        try {
            val welcome = withTimeout(15.seconds) {
                connection.send(WorkerGatewayMessage.Hello(registration()))
                when (val message = connection.receive()) {
                    is WorkerGatewayMessage.Welcome -> message
                    is WorkerGatewayMessage.Failure -> error("Worker Gateway rejected connection: ${message.code}: ${message.message}")
                    else -> error("Worker Gateway did not return welcome")
                }
            }
            require(welcome.protocolVersion == WORKER_GATEWAY_PROTOCOL_VERSION) {
                "Server selected unsupported Worker Gateway protocol ${welcome.protocolVersion}"
            }
            require(welcome.heartbeatIntervalSeconds in 1..300) { "Invalid Worker Gateway heartbeat interval" }
            val ready = prepare(welcome)
            outbound.replaceBeforeReady(ready.tools)
            connection.send(ready)
            onConnected()
            serve(connection, welcome.heartbeatIntervalSeconds.seconds)
        } finally {
            withContext(NonCancellable) { connection.close() }
        }
    }

    private suspend fun serve(connection: WorkerGatewayConnection, heartbeatInterval: Duration) = coroutineScope {
        val outgoing = Channel<WorkerGatewayMessage>(256)
        val requestJobs = mutableMapOf<String, Job>()
        val requestMutex = Mutex()
        outbound.attach(outgoing)
        val writer = launch { for (message in outgoing) connection.send(message) }
        val heartbeat = launch {
            while (isActive) {
                delay(heartbeatInterval)
                outgoing.send(WorkerGatewayMessage.Heartbeat(Clock.System.now()))
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                when (val message = connection.receive() ?: break) {
                    is WorkerGatewayMessage.Request -> {
                        val job = launch(start = CoroutineStart.LAZY) {
                            try {
                                requestConcurrency.withPermit { outgoing.send(handler.execute(message)) }
                            } finally {
                                withContext(NonCancellable) { requestMutex.withLock { requestJobs.remove(message.id) } }
                            }
                        }
                        requestMutex.withLock {
                            check(message.id !in requestJobs) { "Worker Gateway Server reused request id ${message.id}" }
                            requestJobs[message.id] = job
                        }
                        job.start()
                    }
                    is WorkerGatewayMessage.CancelRequest -> requestMutex.withLock {
                        requestJobs[message.requestId]?.cancel(CancellationException("Request cancelled by Server"))
                    }
                    is WorkerGatewayMessage.Response -> check(outbound.accept(message)) {
                        "Worker Gateway Server returned a response for an unknown request"
                    }
                    is WorkerGatewayMessage.AiCatalogUpdated -> updateCatalog(message)
                    is WorkerGatewayMessage.Failure -> error("Worker Gateway failed: ${message.code}: ${message.message}")
                    else -> error("Worker Gateway Server sent an unexpected ${message::class.simpleName}")
                }
            }
        } finally {
            withContext(NonCancellable) {
                outbound.detach(outgoing)
                val activeRequests = requestMutex.withLock { requestJobs.values.toList() }
                activeRequests.forEach { it.cancel() }
                activeRequests.joinAll()
                outgoing.close()
                heartbeat.cancelAndJoin()
                writer.cancelAndJoin()
            }
        }
    }
}
