package com.gromozeka.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicLong
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
 * Servlet callbacks never block waiting for a coroutine consumer. A bounded ingress
 * queue closes an overloaded connection rather than accumulating unbounded messages.
 * Only socket-owned coroutines are cancelled on disconnect; durable Runtime work is
 * still owned by its existing application services.
 */
internal class SpringSocketConnection(
    private val session: WebSocketSession,
    queueCapacity: Int = 32,
    private val maxQueuedBytes: Long = 32L * 1024 * 1024,
    private val sendTimeoutMillis: Long = 30_000,
) : ServerSocketConnection {
    private val queuedBytes = AtomicLong()
    private val closed = AtomicBoolean()
    private val closeStatus = AtomicReference<CloseStatus?>()
    private val sendMutex = Mutex()
    private val incoming = Channel<ServerSocketMessage>(queueCapacity, onUndeliveredElement = {
        queuedBytes.addAndGet(-it.byteSize.toLong())
    })

    init {
        require(queueCapacity > 0 && maxQueuedBytes > 0 && sendTimeoutMillis > 0)
    }

    fun accept(message: ServerSocketMessage): Boolean {
        if (closed.get() || message.byteSize > MAX_WEBSOCKET_MESSAGE_BYTES) return false
        while (true) {
            val before = queuedBytes.get()
            val after = before + message.byteSize
            if (after > maxQueuedBytes) return false
            if (queuedBytes.compareAndSet(before, after)) break
        }
        if (incoming.trySend(message).isSuccess) return true
        queuedBytes.addAndGet(-message.byteSize.toLong())
        return false
    }

    override suspend fun receive(): ServerSocketMessage? {
        val message = incoming.receiveCatching().getOrNull() ?: return null
        queuedBytes.addAndGet(-message.byteSize.toLong())
        return message
    }

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
            runCatching { if (session.isOpen) session.close(checkNotNull(closeStatus.get())) }
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
            } catch (_: Exception) {
                closeCode = 1011
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
        val connection = connections[session.id] ?: return
        if (!connection.socket.accept(message)) {
            reject(session, if (message.byteSize > MAX_WEBSOCKET_MESSAGE_BYTES) 1009 else 1013,
                "WebSocket receive limit exceeded")
        }
    }

    private fun reject(session: WebSocketSession, code: Int, reason: String) {
        connections[session.id]?.let { connection ->
            connection.socket.requestClose(code, reason)
            connection.job.cancel()
            scope.launch { connection.socket.close(code, reason) }
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        reject(session, 1011, "WebSocket transport failed")
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
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
