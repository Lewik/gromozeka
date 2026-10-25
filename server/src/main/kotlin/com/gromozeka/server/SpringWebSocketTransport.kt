package com.gromozeka.server

import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.DisposableBean
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The two existing binary/JSON protocols share a connection, not a web framework. */
internal sealed interface ServerSocketMessage {
    val byteSize: Int
    data class Text(val text: String) : ServerSocketMessage {
        override val byteSize: Int = text.encodeToByteArray().size
    }
    data class Binary(val bytes: ByteArray) : ServerSocketMessage {
        override val byteSize: Int get() = bytes.size
    }
}

internal interface ServerSocketConnection {
    suspend fun receive(): ServerSocketMessage?
    suspend fun send(message: ServerSocketMessage)
    suspend fun close(code: Int = 1000, reason: String = "")
}

internal const val MAX_WEBSOCKET_MESSAGE_BYTES = 16 * 1024 * 1024

/**
 * Servlet callbacks never block waiting for a coroutine consumer. Incoming messages
 * remain queued until consumed, preserving the previous Ktor transport behavior.
 * Only socket-owned coroutines are cancelled on disconnect; durable Runtime work is
 * still owned by its existing application services.
 */
internal class SpringSocketConnection(
    private val session: WebSocketSession,
    private val sendTimeoutMillis: Long = 30_000,
) : ServerSocketConnection {
    private val log = KLoggers.logger(this)
    private val closed = AtomicBoolean()
    private val closeStatus = AtomicReference<CloseStatus?>()
    private val sendMutex = Mutex()
    private val incoming = Channel<ServerSocketMessage>(Channel.UNLIMITED)

    init {
        require(sendTimeoutMillis > 0)
    }

    fun accept(message: ServerSocketMessage): Boolean {
        if (closed.get() || message.byteSize > MAX_WEBSOCKET_MESSAGE_BYTES) return false
        return incoming.trySend(message).isSuccess
    }

    override suspend fun receive(): ServerSocketMessage? = incoming.receiveCatching().getOrNull()

    override suspend fun send(message: ServerSocketMessage) {
        require(message.byteSize <= MAX_WEBSOCKET_MESSAGE_BYTES) { "WebSocket message is too large" }
        sendMutex.withLock {
            check(!closed.get() && session.isOpen) { "WebSocket connection is closed" }
            try {
                withTimeout(sendTimeoutMillis) {
                    runInterruptible(Dispatchers.IO) {
                        session.sendMessage(when (message) {
                            is ServerSocketMessage.Text -> TextMessage(message.text)
                            is ServerSocketMessage.Binary -> BinaryMessage(message.bytes)
                        })
                    }
                }
            } catch (error: Exception) {
                if (error !is CancellationException || error is TimeoutCancellationException) {
                    log.warn(error) { "WebSocket send failed: ${session.diagnosticContext} code=1011" }
                }
                // A partial/uncertain send must not be followed by more protocol output.
                close(1011, "WebSocket send failed")
                throw error
            }
        }
    }

    fun requestClose(code: Int, reason: String) {
        closeStatus.compareAndSet(null, CloseStatus(code, boundedCloseReason(reason)))
        disconnect()
    }

    override suspend fun close(code: Int, reason: String) {
        requestClose(code, reason)
        withContext(NonCancellable + Dispatchers.IO) {
            val status = checkNotNull(closeStatus.get())
            runCatching { if (session.isOpen) session.close(status) }.onFailure { error ->
                log.warn(error) { "WebSocket close failed: ${session.diagnosticContext} code=${status.code}" }
            }
        }
    }

    fun disconnect() {
        if (closed.compareAndSet(false, true)) {
            incoming.cancel(CancellationException("WebSocket disconnected"))
        }
    }

    private fun boundedCloseReason(reason: String): String {
        var value = reason
        while (value.encodeToByteArray().size > 123) {
            val units = if (value.length >= 2 && value.last().isLowSurrogate() &&
                value[value.lastIndex - 1].isHighSurrogate()) 2 else 1
            value = value.dropLast(units)
        }
        return value
    }
}

/** One supervised coroutine per accepted connection; lifecycle ends with the Spring bean. */
internal class CoroutineWebSocketHandler(
    private val serve: suspend (ServerSocketConnection, Map<String, Any>) -> Unit,
) : AbstractWebSocketHandler(), DisposableBean {
    private val log = KLoggers.logger(this)
    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.Default)
    private val connections = ConcurrentHashMap<String, Connection>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val socket = SpringSocketConnection(session)
        lateinit var connection: Connection
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var closeCode = 1000
            try {
                serve(socket, session.attributes.toMap())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                closeCode = 1011
                log.error(error) { "WebSocket session failed: ${session.diagnosticContext} code=$closeCode" }
            } finally {
                connections.remove(session.id, connection)
                socket.close(closeCode, if (closeCode == 1000) "" else "WebSocket session failed")
            }
        }
        connection = Connection(socket, job)
        check(connections.putIfAbsent(session.id, connection) == null) { "Duplicate WebSocket connection" }
        job.start()
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        accept(session, ServerSocketMessage.Text(message.payload))
    }

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        val buffer = message.payload.asReadOnlyBuffer()
        if (buffer.remaining() > MAX_WEBSOCKET_MESSAGE_BYTES) {
            reject(session, 1009, "WebSocket message is too large")
            return
        }
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        accept(session, ServerSocketMessage.Binary(bytes))
    }

    private fun accept(session: WebSocketSession, message: ServerSocketMessage) {
        if (message.byteSize > MAX_WEBSOCKET_MESSAGE_BYTES) {
            reject(session, 1009, "WebSocket message is too large")
            return
        }
        val connection = connections[session.id] ?: return
        if (!connection.socket.accept(message)) {
            log.debug { "WebSocket message ignored after disconnect: ${session.diagnosticContext}" }
        }
    }

    private fun reject(session: WebSocketSession, code: Int, reason: String) {
        log.warn { "WebSocket connection rejected: ${session.diagnosticContext} code=$code reason=$reason" }
        connections[session.id]?.let { connection ->
            connection.socket.requestClose(code, reason)
            connection.job.cancel()
            scope.launch { connection.socket.close(code, reason) }
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn(exception) { "WebSocket transport failed: ${session.diagnosticContext} code=1011" }
        reject(session, 1011, "WebSocket transport failed")
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        if (status.code == 1000 || status.code == 1001) {
            log.debug { "WebSocket closed: ${session.diagnosticContext} code=${status.code}" }
        } else {
            log.warn { "WebSocket closed abnormally: ${session.diagnosticContext} code=${status.code}" }
        }
        connections.remove(session.id)?.let { connection ->
            connection.socket.disconnect()
            connection.job.cancel()
        }
    }

    override fun destroy() = runBlocking {
        connections.values.forEach { it.socket.close(1001, "Server stopping") }
        lifecycle.cancel()
        withTimeout(10_000) { lifecycle.join() }
        connections.clear()
    }

    private data class Connection(val socket: SpringSocketConnection, val job: Job)
}

private val WebSocketSession.diagnosticContext: String
    get() = "session=$id path=${uri?.path}"
