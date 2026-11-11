package com.gromozeka.server

import com.gromozeka.remote.protocol.MAX_WORKER_GATEWAY_MESSAGE_BYTES
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import jakarta.websocket.server.ServerContainer
import jakarta.websocket.server.ServerEndpointConfig
import org.apache.catalina.servlets.DefaultServlet
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.websocket.server.WsSci
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.adapter.standard.StandardWebSocketHandlerAdapter
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpringWebSocketTransportIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real Tomcat reassembles and echoes large worker messages while retaining the UI limit`() {
        val tomcat = Tomcat().apply {
            setBaseDir(directory.toString())
            setPort(0)
            connector.setProperty("address", "127.0.0.1")
        }
        val handlers = mutableListOf<CoroutineWebSocketHandler>()
        val serverSessions = ConcurrentHashMap<String, StandardWebSocketSession>()
        val openedSessions = ConcurrentHashMap<String, LinkedBlockingQueue<StandardWebSocketSession>>()
        try {
            val context = tomcat.addContext("", directory.toString())
            context.parentClassLoader = javaClass.classLoader
            Tomcat.addServlet(context, "default", DefaultServlet())
            context.addServletMappingDecoded("/", "default")
            context.addServletContainerInitializer(WsSci(), emptySet())
            tomcat.start()
            val container = context.servletContext.getAttribute(ServerContainer::class.java.name) as ServerContainer
            container.defaultMaxBinaryMessageBufferSize = WEBSOCKET_RECEIVE_BUFFER_SIZE
            container.defaultMaxTextMessageBufferSize = WEBSOCKET_RECEIVE_BUFFER_SIZE
            for ((path, limit) in listOf("/ws" to DEFAULT_WEBSOCKET_MESSAGE_BYTES, "/worker/ws" to MAX_WORKER_GATEWAY_MESSAGE_BYTES)) {
                openedSessions[path] = LinkedBlockingQueue()
                val handler = CoroutineWebSocketHandler(maxBinaryMessageBytes = limit) { socket, _ ->
                    openedSessions.getValue(path).add(serverSessions.getValue(path))
                    socket.receive()?.let { socket.send(it) }
                }
                handlers += handler
                val endpoint = ServerEndpointConfig.Builder.create(StandardWebSocketHandlerAdapter::class.java, path)
                    .configurator(object : ServerEndpointConfig.Configurator() {
                        override fun <T : Any> getEndpointInstance(endpointClass: Class<T>): T {
                            val session = StandardWebSocketSession(HttpHeaders(), emptyMap(), null, null)
                            serverSessions[path] = session
                            return endpointClass.cast(StandardWebSocketHandlerAdapter(handler, session))
                        }
                    }).build()
                container.addEndpoint(endpoint)
            }
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
                val payload = ByteArray(32 * 1024 * 1024 + 123) { (it % 251).toByte() }
                val bytes = WorkerGatewayCodec.encode(WorkerGatewayMessage.Request(
                    "binary-network", WorkerGatewayOperation.ARTIFACT_CONTENT, payload))
                assertTrue(bytes.size <= payload.size + 512)
                val worker = RecordingListener()
                val workerSocket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI("ws://127.0.0.1:${tomcat.connector.localPort}/worker/ws"), worker)
                    .get(5, TimeUnit.SECONDS)
                try {
                    val workerSession = checkNotNull(openedSessions.getValue("/worker/ws").poll(5, TimeUnit.SECONDS))
                    assertReceiveBuffersSmall(workerSession)
                    // Small individual frames still form one message above the former aggregate limit.
                    var offset = 0
                    while (offset < bytes.size) {
                        val length = minOf(4 * 1024 * 1024, bytes.size - offset)
                        workerSocket.sendBinary(ByteBuffer.wrap(bytes, offset, length), offset + length == bytes.size)
                            .get(10, TimeUnit.SECONDS)
                        offset += length
                    }
                    val echoed = worker.message.get(15, TimeUnit.SECONDS)
                    assertEquals(bytes.size, echoed.first)
                    assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes), echoed.second)
                    assertEquals(1000, worker.closed.get(5, TimeUnit.SECONDS))
                    assertReceiveBuffersSmall(workerSession)
                } finally { workerSocket.abort() }

                val text = "שלום🙂".repeat(40_000)
                val textListener = RecordingListener()
                val textSocket = client.newWebSocketBuilder()
                    .buildAsync(URI("ws://127.0.0.1:${tomcat.connector.localPort}/ws"), textListener)
                    .get(5, TimeUnit.SECONDS)
                try {
                    val textSession = checkNotNull(openedSessions.getValue("/ws").poll(5, TimeUnit.SECONDS))
                    assertReceiveBuffersSmall(textSession)
                    textSocket.sendText(text, true).get(10, TimeUnit.SECONDS)
                    assertEquals(text, textListener.text.get(10, TimeUnit.SECONDS))
                    assertEquals(1000, textListener.closed.get(5, TimeUnit.SECONDS))
                    assertReceiveBuffersSmall(textSession)
                } finally { textSocket.abort() }

                val ui = RecordingListener()
                val uiSocket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI("ws://127.0.0.1:${tomcat.connector.localPort}/ws"), ui)
                    .get(5, TimeUnit.SECONDS)
                try {
                    uiSocket.sendBinary(ByteBuffer.wrap(bytes, 0, DEFAULT_WEBSOCKET_MESSAGE_BYTES), false)
                        .get(10, TimeUnit.SECONDS)
                    uiSocket.sendBinary(ByteBuffer.wrap(bytes, DEFAULT_WEBSOCKET_MESSAGE_BYTES, 1), true)
                        .get(10, TimeUnit.SECONDS)
                    assertEquals(1009, ui.closed.get(10, TimeUnit.SECONDS))
                    assertFalse(ui.message.isDone)
                } finally { uiSocket.abort() }
            }
            assertEquals(WEBSOCKET_RECEIVE_BUFFER_SIZE, container.defaultMaxBinaryMessageBufferSize)
            assertEquals(WEBSOCKET_RECEIVE_BUFFER_SIZE, container.defaultMaxTextMessageBufferSize)
        } finally {
            try { handlers.forEach { it.destroy() } } finally {
                try { tomcat.stop() } finally { tomcat.destroy() }
            }
        }
    }

    private fun assertReceiveBuffersSmall(session: StandardWebSocketSession) {
        val native = checkNotNull(session.nativeSession)
        val frame = native.javaClass.getDeclaredField("wsFrame").apply { isAccessible = true }.get(native)
        for (field in listOf("messageBufferBinary", "messageBufferText")) {
            val buffer = frame.javaClass.superclass.getDeclaredField(field).apply { isAccessible = true }.get(frame) as java.nio.Buffer
            assertEquals(WEBSOCKET_RECEIVE_BUFFER_SIZE, buffer.capacity())
        }
    }

    private class RecordingListener : WebSocket.Listener {
        val message = CompletableFuture<Pair<Int, ByteArray>>()
        val closed = CompletableFuture<Int>()
        val text = CompletableFuture<String>()
        private val textParts = StringBuilder()
        private val digest = MessageDigest.getInstance("SHA-256")
        private var length = 0

        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            length += data.remaining()
            digest.update(data)
            if (last) message.complete(length to digest.digest())
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed.complete(statusCode)
            return null
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            textParts.append(data)
            if (last) text.complete(textParts.toString())
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            message.completeExceptionally(error)
            text.completeExceptionally(error)
            closed.completeExceptionally(error)
        }
    }
}
