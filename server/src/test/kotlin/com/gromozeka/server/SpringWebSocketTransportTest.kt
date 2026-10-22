package com.gromozeka.server

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
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `ingress byte budget is released on consumption and rejects after disconnect`() = runBlocking<Unit> {
        val connection = SpringSocketConnection(SocketFixture().session, maxQueuedBytes = 4)
        assertTrue(connection.accept(ServerSocketMessage.Binary(ByteArray(4))))
        assertFalse(connection.accept(ServerSocketMessage.Text("a")))
        connection.receive()
        assertTrue(connection.accept(ServerSocketMessage.Text("🙂")))
        assertFalse(connection.accept(ServerSocketMessage.Text("b")))
        connection.disconnect()
        assertNull(connection.receive())
        assertFalse(connection.accept(ServerSocketMessage.Text("c")))
    }

    @Test
    fun `ingress frame count is bounded even for empty frames`() = runBlocking<Unit> {
        val connection = SpringSocketConnection(SocketFixture().session, queueCapacity = 1)
        assertTrue(connection.accept(ServerSocketMessage.Text("")))
        assertFalse(connection.accept(ServerSocketMessage.Text("")))
        connection.receive()
        assertTrue(connection.accept(ServerSocketMessage.Text("")))
        connection.disconnect()
    }

    @Test
    fun `oversized incoming and outgoing messages are rejected`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val connection = SpringSocketConnection(fixture.session)
        val oversized = ServerSocketMessage.Binary(ByteArray(MAX_WEBSOCKET_MESSAGE_BYTES + 1))
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
        assertFailsWith<IOException> { connection.send(ServerSocketMessage.Text("first")) }
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
        try {
            handler.afterConnectionEstablished(fixture.session)
            withTimeout(5_000) { started.await() }
            fixture.open.set(false)
            handler.afterConnectionClosed(fixture.session, CloseStatus.NORMAL)
            withTimeout(5_000) { cleaned.await() }
        } finally { handler.destroy() }
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
    fun `overload closes with the requested code rather than normal coroutine cleanup`() = runBlocking<Unit> {
        val fixture = SocketFixture()
        val started = CompletableDeferred<Unit>()
        val handler = CoroutineWebSocketHandler { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        try {
            handler.afterConnectionEstablished(fixture.session)
            withTimeout(5_000) { started.await() }
            repeat(33) { handler.handleMessage(fixture.session, TextMessage("buffered")) }
            assertEquals(1013, withTimeout(5_000) { fixture.closed.await() }.code)
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

    private class SocketFixture {
        val session = mock(WebSocketSession::class.java)
        val open = AtomicBoolean(true)
        val closed = CompletableDeferred<CloseStatus>()
        val sent = CopyOnWriteArrayList<WebSocketMessage<*>>()
        var send: (WebSocketMessage<*>) -> Unit = {}
        init {
            `when`(session.id).thenReturn("synthetic-socket")
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
