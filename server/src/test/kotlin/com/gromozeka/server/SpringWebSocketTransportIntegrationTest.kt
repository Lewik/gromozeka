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
        try {
            val context = tomcat.addContext("", directory.toString())
            context.parentClassLoader = javaClass.classLoader
            Tomcat.addServlet(context, "default", DefaultServlet())
            context.addServletMappingDecoded("/", "default")
            context.addServletContainerInitializer(WsSci(), emptySet())
            tomcat.start()
            val container = context.servletContext.getAttribute(ServerContainer::class.java.name) as ServerContainer
            container.defaultMaxBinaryMessageBufferSize = DEFAULT_WEBSOCKET_MESSAGE_BYTES
            container.defaultMaxTextMessageBufferSize = DEFAULT_WEBSOCKET_MESSAGE_BYTES
            for ((path, limit) in listOf("/ws" to DEFAULT_WEBSOCKET_MESSAGE_BYTES, "/worker/ws" to MAX_WORKER_GATEWAY_MESSAGE_BYTES)) {
                val handler = CoroutineWebSocketHandler(maxBinaryMessageBytes = limit) { socket, _ ->
                    socket.receive()?.let { socket.send(it) }
                }
                handlers += handler
                val endpoint = ServerEndpointConfig.Builder.create(StandardWebSocketHandlerAdapter::class.java, path)
                    .configurator(object : ServerEndpointConfig.Configurator() {
                        override fun <T : Any> getEndpointInstance(endpointClass: Class<T>): T = endpointClass.cast(
                            StandardWebSocketHandlerAdapter(handler, StandardWebSocketSession(HttpHeaders(), emptyMap(), null, null))
                        )
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
                } finally { workerSocket.abort() }

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
            assertEquals(DEFAULT_WEBSOCKET_MESSAGE_BYTES, container.defaultMaxBinaryMessageBufferSize)
            assertEquals(DEFAULT_WEBSOCKET_MESSAGE_BYTES, container.defaultMaxTextMessageBufferSize)
        } finally {
            try { handlers.forEach { it.destroy() } } finally {
                try { tomcat.stop() } finally { tomcat.destroy() }
            }
        }
    }

    private class RecordingListener : WebSocket.Listener {
        val message = CompletableFuture<Pair<Int, ByteArray>>()
        val closed = CompletableFuture<Int>()
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

        override fun onError(webSocket: WebSocket, error: Throwable) {
            message.completeExceptionally(error)
            closed.completeExceptionally(error)
        }
    }
}
