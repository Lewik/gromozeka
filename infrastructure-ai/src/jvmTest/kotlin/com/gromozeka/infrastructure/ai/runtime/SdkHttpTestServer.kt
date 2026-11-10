package com.gromozeka.infrastructure.ai.runtime

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

internal class SdkHttpTestServer(
    responseBodies: List<Reply>,
    sslContext: SSLContext? = null,
    private val expectedTarget: String? = null,
) : AutoCloseable {
    constructor(vararg responseBodies: String) : this(responseBodies.map { Reply(it) })

    data class Reply(val body: String, val status: Int = 200, val headers: Map<String, String> = emptyMap())

    private val recorded = ConcurrentLinkedQueue<RecordedRequest>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sdk-http-test-server").apply { isDaemon = true }
    }
    private val responses = ConcurrentLinkedQueue(responseBodies)
    private val scheme = if (sslContext == null) "http" else "https"

    init {
        require(responseBodies.isNotEmpty()) { "HTTP test server requires at least one response" }
    }

    private val server = (if (sslContext == null) HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        else HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { httpsConfigurator = HttpsConfigurator(sslContext) }).apply {
        executor = this@SdkHttpTestServer.executor
        createContext("/") { exchange ->
            try {
                recorded += RecordedRequest(
                    method = exchange.requestMethod,
                    target = exchange.requestURI.toASCIIString(),
                    headers = exchange.requestHeaders.entries.associate {
                        it.key.lowercase(Locale.ROOT) to it.value.toList()
                    },
                    body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) },
                )
                val reply = if (expectedTarget != null && exchange.requestURI.toASCIIString() != expectedTarget) {
                    Reply("""{"error":{"message":"Endpoint is disabled","type":"not_found_error"}}""", status = 404)
                } else checkNotNull(responses.poll()) { "Unexpected extra HTTP request in test" }
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                reply.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        start()
    }

    val origin: String get() = "$scheme://127.0.0.1:${server.address.port}"
    val requests: List<RecordedRequest> get() = recorded.toList()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "HTTP test server did not terminate" }
    }

    data class RecordedRequest(
        val method: String,
        val target: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )
}
