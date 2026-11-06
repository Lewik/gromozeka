package com.gromozeka.infrastructure.ai.runtime

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class SdkHttpTestServer(responseBody: String) : AutoCloseable {
    private val recorded = ConcurrentLinkedQueue<RecordedRequest>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sdk-http-test-server").apply { isDaemon = true }
    }
    private val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
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
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                exchange.responseBody.use { it.write(responseBytes) }
            } finally {
                exchange.close()
            }
        }
        start()
    }

    val origin: String get() = "http://127.0.0.1:${server.address.port}"
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
