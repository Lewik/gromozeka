package com.gromozeka.worker.runtime

import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.remote.protocol.WORKER_GATEWAY_PROTOCOL_VERSION
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val journal: WorkerRequestJournal,
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
    init {
        require(reconnectDelay in 1.seconds..60.seconds) { "Worker reconnect delay must be between 1 and 60 seconds" }
    }

    suspend fun run(): Unit = coroutineScope {
        val executor = WorkerRequestExecutor(journal, this, handler) { outbound.tryPublish(it) }
        executor.initialize()
        try {
            var failures = 0L
            var lastFailure: TimeSource.Monotonic.ValueTimeMark? = null
            while (isActive) {
                try {
                    connect(executor)
                    failures = 0
                    lastFailure = null
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
        } finally {
            withContext(NonCancellable) { executor.stop() }
        }
    }

    private suspend fun connect(executor: WorkerRequestExecutor) {
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
            serve(connection, welcome.heartbeatIntervalSeconds.seconds, executor)
        } finally {
            try {
                withContext(NonCancellable) { connection.close() }
            } finally {
                onDisconnected()
            }
        }
    }

    private suspend fun serve(connection: WorkerGatewayConnection, heartbeatInterval: Duration, executor: WorkerRequestExecutor) = coroutineScope {
        val outgoing = Channel<WorkerGatewayMessage>(256)
        outbound.attach(outgoing)
        val writer = launch { for (message in outgoing) connection.send(message) }
        val heartbeat = launch {
            executor.pendingResponses().forEach { outgoing.send(it) }
            while (isActive) {
                delay(heartbeatInterval)
                outgoing.send(WorkerGatewayMessage.Heartbeat(Clock.System.now()))
                executor.pendingResponses().forEach { outgoing.send(it) }
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                when (val message = connection.receive() ?: break) {
                    is WorkerGatewayMessage.Request -> executor.accept(message)
                    is WorkerGatewayMessage.CancelRequest -> executor.cancel(message.requestId)
                    is WorkerGatewayMessage.ResponseAcknowledged -> executor.acknowledge(message.requestId)
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
                outgoing.close()
                heartbeat.cancelAndJoin()
                writer.cancelAndJoin()
            }
        }
    }
}
