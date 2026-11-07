package com.gromozeka.server

import com.gromozeka.remote.protocol.MAX_WORKER_GATEWAY_MESSAGE_BYTES
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpringWebSocketTransportTest {
    @Test
    fun `incoming text and binary preserve exact values and order`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val connection = SpringSocketConnection(fixture.session)
        val text = "  שלום\r\n🙂\t"
        val bytes = byteArrayOf(0, 1, -1, 10)
        assertTrue(connection.accept(ServerSocketMessage.Text(text)))
        assertTrue(connection.accept(ServerSocketMessage.Binary(bytes)))
        assertEquals(text, (connection.receive() as ServerSocketMessage.Text).text)
        assertContentEquals(bytes, (connection.receive() as ServerSocketMessage.Binary).bytes)
        connection.close()
        assertNull(connection.receive())
    }

    @Test
    fun `ingress buffers more than the former byte budget and rejects after disconnect`() = runBlocking<Unit> {
        val connection = SpringSocketConnection(SocketFixture().session)
        val messages = List(3) { ServerSocketMessage.Binary(ByteArray(12 * 1024 * 1024)) }
        messages.forEach { assertTrue(connection.accept(it)) }
        messages.forEach { assertSame(it, connection.receive()) }
        assertTrue(connection.accept(ServerSocketMessage.Text("discard on disconnect")))
        connection.disconnect()
        assertNull(connection.receive())
        assertFalse(connection.accept(ServerSocketMessage.Text("c")))
    }

    @Test
    fun `handler and transport failures log their causes without exposing connection credentials`() = runBlocking<Unit> {
        for (transportFailure in listOf(false, true)) {
            val fixture = SocketFixture()
            val started = CompletableDeferred<Unit>()
            val failure = IOException("DIAGNOSTIC_FAILURE")
            val handler = CoroutineWebSocketHandler { _, _ ->
                started.complete(Unit)
                if (transportFailure) awaitCancellation() else throw failure
            }
            SocketLogs(CoroutineWebSocketHandler::class.java).use { logs ->
                try {
                    handler.afterConnectionEstablished(fixture.session)
                    withTimeout(5_000) { started.await() }
                    if (transportFailure) handler.handleTransportError(fixture.session, failure)
                    val status = withTimeout(5_000) { fixture.closed.await() }
                    assertEquals(1011, status.code)
                    assertFalse(checkNotNull(status.reason).contains("DIAGNOSTIC_FAILURE"))
                    val event = logs.events.single { it.throwableProxy?.message == "DIAGNOSTIC_FAILURE" }
                    assertEquals(if (transportFailure) Level.WARN else Level.ERROR, event.level)
                    assertTrue(event.formattedMessage.contains("session=synthetic-socket path=/worker/ws"))
                    assertTrue(event.throwableProxy.stackTraceElementProxyArray.isNotEmpty())
                    assertTrue(logs.events.none { it.formattedMessage.contains("QUERY_SECRET") })
                } finally { handler.destroy() }
            }
        }
    }

    @Test
    fun `oversized incoming and outgoing messages are rejected`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val connection = SpringSocketConnection(fixture.session)
        val oversized = ServerSocketMessage.Binary(ByteArray(DEFAULT_WEBSOCKET_MESSAGE_BYTES + 1))
        assertFalse(connection.accept(oversized))
        assertFailsWith<IllegalArgumentException> { connection.send(oversized) }
        assertTrue(fixture.sent.isEmpty())
        connection.close()
    }

    @Test
    fun `parallel sends are serialized and preserve all frames`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        fixture.send = {
            val count = active.incrementAndGet()
            maximum.accumulateAndGet(count, ::maxOf)
            try { Thread.sleep(5) } finally { active.decrementAndGet() }
        }
        val connection = SpringSocketConnection(fixture.session)
        (1..12).map { value -> async(Dispatchers.Default) {
            connection.send(ServerSocketMessage.Text(value.toString()))
        } }.awaitAll()
        assertEquals(1, maximum.get())
        assertEquals((1..12).map(Int::toString).toSet(), fixture.sent.map { it.payload }.toSet())
        connection.close()
    }

    @Test
    fun `failed send closes transport and prevents following protocol output`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        fixture.send = { throw IOException("PRIVATE_TRANSPORT_DETAIL") }
        val connection = SpringSocketConnection(fixture.session)
        SocketLogs(SpringSocketConnection::class.java).use { logs ->
            assertFailsWith<IOException> { connection.send(ServerSocketMessage.Text("PRIVATE_MESSAGE_CONTENT")) }
            val event = logs.events.single { it.throwableProxy?.message == "PRIVATE_TRANSPORT_DETAIL" }
            assertEquals(Level.WARN, event.level)
            assertTrue(event.formattedMessage.contains("WebSocket send failed"))
            assertTrue(logs.events.none { it.formattedMessage.contains("PRIVATE_MESSAGE_CONTENT") })
        }
        assertEquals(1011, fixture.closed.await().code)
        assertFalse(checkNotNull(fixture.closed.await().reason).contains("PRIVATE"))
        assertFailsWith<IllegalStateException> { connection.send(ServerSocketMessage.Text("second")) }
        assertEquals(1, fixture.sent.size)
    }

    @Test
    fun `stalled send is interrupted and connection is closed`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val interrupted = AtomicBoolean()
        fixture.send = {
            try { Thread.sleep(30_000) } catch (error: InterruptedException) {
                interrupted.set(true)
                throw error
            }
        }
        val connection = SpringSocketConnection(fixture.session, sendTimeoutMillis = 100)
        assertFailsWith<TimeoutCancellationException> {
            connection.send(ServerSocketMessage.Text("bounded"))
        }
        assertTrue(interrupted.get())
        assertEquals(1011, fixture.closed.await().code)
    }

    @Test
    fun `peer disconnect cancels the session coroutine and runs its cleanup`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val handler = CoroutineWebSocketHandler { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        }
        SocketLogs(CoroutineWebSocketHandler::class.java).use { logs ->
            try {
                handler.afterConnectionEstablished(fixture.session)
                withTimeout(5_000) { started.await() }
                fixture.open.set(false)
                handler.afterConnectionClosed(fixture.session, CloseStatus.NORMAL)
                withTimeout(5_000) { cleaned.await() }
            } finally { handler.destroy() }
            assertTrue(logs.events.none { it.level.isGreaterOrEqual(Level.WARN) })
        }
    }

    @Test
    fun `spring callbacks deliver binary and text into the coroutine handler`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val received = CompletableDeferred<List<ServerSocketMessage?>>()
        val handler = CoroutineWebSocketHandler { connection, attributes ->
            assertEquals("trusted", attributes["actor"])
            received.complete(listOf(connection.receive(), connection.receive()))
        }
        try {
            handler.afterConnectionEstablished(fixture.session)
            handler.handleMessage(fixture.session, TextMessage("  text\r\n"))
            handler.handleMessage(fixture.session, BinaryMessage(byteArrayOf(0, -1)))
            val messages = withTimeout(5_000) { received.await() }
            assertEquals("  text\r\n", (messages[0] as ServerSocketMessage.Text).text)
            assertContentEquals(byteArrayOf(0, -1), (messages[1] as ServerSocketMessage.Binary).bytes)
            withTimeout(5_000) { fixture.closed.await() }
        } finally { handler.destroy() }
    }

    @Test
    fun `burst stays connected while consumer is delayed and drains in order`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val started = CompletableDeferred<Unit>()
        val consume = CompletableDeferred<Unit>()
        val received = CompletableDeferred<List<String>>()
        val messages = List(256) { "buffered-$it" }
        val handler = CoroutineWebSocketHandler { connection, _ ->
            started.complete(Unit)
            consume.await()
            received.complete(messages.map { (connection.receive() as ServerSocketMessage.Text).text })
        }
        try {
            handler.afterConnectionEstablished(fixture.session)
            withTimeout(5_000) { started.await() }
            messages.forEach { handler.handleMessage(fixture.session, TextMessage(it)) }
            assertFalse(fixture.closed.isCompleted)
            consume.complete(Unit)
            assertEquals(messages, withTimeout(5_000) { received.await() })
            assertEquals(1000, withTimeout(5_000) { fixture.closed.await() }.code)
        } finally { handler.destroy() }
    }

    @Test
    fun `close reason is bounded in UTF8 without splitting a surrogate pair`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val connection = SpringSocketConnection(fixture.session)
        connection.close(1008, "🙂".repeat(60))
        val reason = checkNotNull(fixture.closed.await().reason)
        assertEquals("🙂".repeat(30), reason)
        assertTrue(reason.encodeToByteArray().size <= 123)
    }

    @Test
    fun `worker binary budget accepts exactly 128 MiB and rejects one byte more in both directions`() = runBlocking<Unit> {
        assertEquals(128 * 1024 * 1024, MAX_WORKER_GATEWAY_MESSAGE_BYTES)
        val fixture = SocketFixture()
        val connection = SpringSocketConnection(fixture.session, maxBinaryMessageBytes = MAX_WORKER_GATEWAY_MESSAGE_BYTES)
        val boundary = ServerSocketMessage.Binary(ByteArray(MAX_WORKER_GATEWAY_MESSAGE_BYTES))
        assertTrue(connection.accept(boundary))
        assertSame(boundary, connection.receive())
        connection.send(boundary)
        assertEquals(MAX_WORKER_GATEWAY_MESSAGE_BYTES, fixture.sent.single().payloadLength)
        val oversized = ServerSocketMessage.Binary(ByteArray(MAX_WORKER_GATEWAY_MESSAGE_BYTES + 1))
        assertFalse(connection.accept(oversized))
        assertFailsWith<IllegalArgumentException> { connection.send(oversized) }
        assertEquals(1, fixture.sent.size)
        connection.close()
    }

    @Test
    fun `worker binary increase does not raise the UTF8 text budget`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val connection = SpringSocketConnection(fixture.session, maxBinaryMessageBytes = MAX_WORKER_GATEWAY_MESSAGE_BYTES)
        val oversizedText = ServerSocketMessage.Text("é".repeat(DEFAULT_WEBSOCKET_MESSAGE_BYTES / 2 + 1))
        assertTrue(oversizedText.text.length < DEFAULT_WEBSOCKET_MESSAGE_BYTES)
        assertFalse(connection.accept(oversizedText))
        assertFailsWith<IllegalArgumentException> { connection.send(oversizedText) }
        assertTrue(fixture.sent.isEmpty())
        connection.close()
    }

    @Test
    fun `handler configures session before receiving and keeps UI and worker binary budgets separate`() = runBlocking<Unit> {
        val bytes = ByteArray(DEFAULT_WEBSOCKET_MESSAGE_BYTES + 1).also { it[0] = 7; it[it.lastIndex] = 9 }
        for (limit in listOf(DEFAULT_WEBSOCKET_MESSAGE_BYTES, MAX_WORKER_GATEWAY_MESSAGE_BYTES)) {
            val fixture = SocketFixture()
            val received = CompletableDeferred<Unit>()
            val handler = CoroutineWebSocketHandler(maxBinaryMessageBytes = limit) { connection, _ ->
                val message = checkNotNull(connection.receive()) as ServerSocketMessage.Binary
                assertContentEquals(bytes, message.bytes)
                connection.send(message)
                received.complete(Unit)
            }
            try {
                handler.afterConnectionEstablished(fixture.session)
                verify(fixture.session).setBinaryMessageSizeLimit(limit)
                verify(fixture.session).setTextMessageSizeLimit(DEFAULT_WEBSOCKET_MESSAGE_BYTES)
                handler.handleMessage(fixture.session, BinaryMessage(bytes))
                val status = withTimeout(5_000) { fixture.closed.await() }
                if (limit == DEFAULT_WEBSOCKET_MESSAGE_BYTES) {
                    assertEquals(1009, status.code)
                    assertFalse(received.isCompleted)
                    assertTrue(fixture.sent.isEmpty())
                } else {
                    assertEquals(1000, status.code)
                    withTimeout(5_000) { received.await() }
                    assertEquals(bytes.size, fixture.sent.single().payloadLength)
                }
            } finally { handler.destroy() }
        }
    }

    @Test
    fun `production routes apply distinct binary limits`() {
        val handlers = mutableMapOf<String, WebSocketHandler>()
        val registry = mock(WebSocketHandlerRegistry::class.java)
        val registration = mock(WebSocketHandlerRegistration::class.java, RETURNS_SELF)
        doAnswer { invocation ->
            handlers[invocation.getArgument<String>(1)] = invocation.getArgument(0)
            registration
        }.`when`(registry).addHandler(any(WebSocketHandler::class.java), anyString())
        val configuration = GromozekaWebSocketConfiguration(mock(GromozekaRemoteServer::class.java), mock(WorkerGatewayService::class.java))
        try {
            configuration.registerWebSocketHandlers(registry)
            assertEquals(setOf("/ws", "/worker/ws"), handlers.keys)
            for ((path, expected) in listOf("/ws" to DEFAULT_WEBSOCKET_MESSAGE_BYTES, "/worker/ws" to MAX_WORKER_GATEWAY_MESSAGE_BYTES)) {
                val fixture = SocketFixture()
                `when`(fixture.session.attributes).thenReturn(mutableMapOf(
                    AUTHENTICATED_REMOTE_SESSION to mock(AuthenticatedRemoteSession::class.java),
                    AUTHENTICATED_WORKER_GATEWAY to mock(AuthenticatedWorkerGateway::class.java),
                ))
                handlers.getValue(path).afterConnectionEstablished(fixture.session)
                verify(fixture.session).setBinaryMessageSizeLimit(expected)
                verify(fixture.session).setTextMessageSizeLimit(DEFAULT_WEBSOCKET_MESSAGE_BYTES)
            }
        } finally { configuration.destroy() }
    }

    @Test
    fun `invalid binary budgets fail before opening a connection`() {
        assertFailsWith<IllegalArgumentException> {
            SpringSocketConnection(SocketFixture().session, maxBinaryMessageBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CoroutineWebSocketHandler(maxBinaryMessageBytes = -1) { _, _ -> }
        }
    }

    private class SocketLogs(type: Class<*>) : AutoCloseable {
        private val logger = LoggerFactory.getLogger(type) as Logger
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        private val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) { events += event }
        }
        init {
            appender.context = logger.loggerContext
            appender.start()
            logger.addAppender(appender)
        }
        override fun close() {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private class SocketFixture {
        val session = mock(WebSocketSession::class.java)
        val open = AtomicBoolean(true)
        val closed = CompletableDeferred<CloseStatus>()
        val sent = CopyOnWriteArrayList<WebSocketMessage<*>>()
        var send: (WebSocketMessage<*>) -> Unit = {}
        init {
            `when`(session.id).thenReturn("synthetic-socket")
            `when`(session.uri).thenReturn(URI("ws://localhost/worker/ws?token=QUERY_SECRET"))
            `when`(session.attributes).thenReturn(mutableMapOf<String, Any>("actor" to "trusted"))
            `when`(session.isOpen).thenAnswer { open.get() }
            doAnswer { invocation ->
                val message = invocation.getArgument<WebSocketMessage<*>>(0)
                sent += message
                send(message)
                null
            }.`when`(session).sendMessage(any())
            doAnswer { invocation ->
                open.set(false)
                closed.complete(invocation.getArgument(0))
                null
            }.`when`(session).close(any<CloseStatus>())
        }
    }
}
