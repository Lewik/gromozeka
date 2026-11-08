package com.gromozeka.infrastructure.ai.diagnostics

import com.gromozeka.infrastructure.ai.runtime.SdkHttpTestServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.test.*

class DangerousAiTrafficDumpTest {
    @Test
    fun disabledFlagDoesNotCreateFilesOrChangeHttp() = configured(false) { directory ->
        SdkHttpTestServer("unchanged").use { server ->
            AiTrafficDumpHttpClient(HttpClient.newHttpClient(), "test").use { client ->
                val response = client.send(HttpRequest.newBuilder(URI(server.origin)).GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals("unchanged", response.body())
            }
        }
        assertFalse(Files.exists(directory.resolve("ai-traffic")))
    }

    @Test
    fun jdkHttpPreservesFullBodiesCredentialsAndErrorResponses() = configured { directory ->
        val requestText = "private\u0000" + "Я📷".repeat(30_000)
        val responseText = "raw error\n" + "x".repeat(180_000)
        SdkHttpTestServer(listOf(SdkHttpTestServer.Reply(responseText, 400))).use { server ->
            AiTrafficDumpHttpClient(HttpClient.newHttpClient(), "openai-subscription").use { client ->
                val response = client.sendAsync(HttpRequest.newBuilder(URI(server.origin + "/responses?q=private"))
                    .header("Authorization", "Bearer unredacted-test-secret")
                    .POST(HttpRequest.BodyPublishers.ofString(requestText)).build(), HttpResponse.BodyHandlers.ofString()).get(10, TimeUnit.SECONDS)
                assertEquals(400, response.statusCode())
                assertEquals(responseText, response.body())
            }
            assertEquals(requestText, server.requests.single().body)
        }
        val recording = recordings(directory).single()
        assertEquals(requestText, Files.readString(recording.resolve("request.bin")))
        assertEquals(responseText, Files.readString(recording.resolve("response.bin")))
        val events = Files.readString(recording.resolve("events.jsonl"))
        assertContains(events, "Bearer unredacted-test-secret")
        assertContains(events, "q=private")
        assertContains(events, "400")
        assertContains(events, "response.complete")
    }

    @Test
    fun okhttpCaptureWritesOneShotRequestOnlyOnce() = configured { directory ->
        var writes = 0
        val client = OkHttpClient.Builder().addNetworkInterceptor(AiTrafficDumpInterceptor("sdk")).build()
        try {
            SdkHttpTestServer("response\u0000tail").use { server ->
                val request = Request.Builder().url(server.origin).header("x-api-key", "test-secret")
                    .post(object : RequestBody() {
                        override fun contentType() = null
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8("full request") }
                    }).build()
                client.newCall(request).execute().use { assertEquals("response\u0000tail", it.body!!.string()) }
                assertEquals("full request", server.requests.single().body)
            }
            assertEquals(1, writes)
            val recording = recordings(directory).single()
            assertEquals("full request", Files.readString(recording.resolve("request.bin")))
            assertEquals("response\u0000tail", Files.readString(recording.resolve("response.bin")))
            assertContains(Files.readString(recording.resolve("events.jsonl")), "test-secret")
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test
    fun openAiSdkCapturePreservesBinaryResponseAndOneShotBody() = configured { directory ->
        var writes = 0
        SdkHttpTestServer("audio\u0000payload").use { server ->
            val client = AiTrafficDumpOpenAiClient(com.openai.client.okhttp.OkHttpClient.builder()
                .proxy(java.net.Proxy.NO_PROXY).build())
            try {
                val request = com.openai.core.http.HttpRequest.builder()
                    .baseUrl(server.origin).addPathSegment("audio")
                    .method(com.openai.core.http.HttpMethod.POST).putHeader("Authorization", "Bearer sdk-secret")
                    .body(object : com.openai.core.http.HttpRequestBody {
                        override fun writeTo(outputStream: java.io.OutputStream) { writes++; outputStream.write("request".toByteArray()) }
                        override fun contentType() = "application/octet-stream"
                        override fun contentLength() = 7L
                        override fun repeatable() = false
                        override fun close() = Unit
                    }).build()
                client.execute(request, com.openai.core.RequestOptions.builder().build()).use {
                    assertEquals("audio\u0000payload", it.body().readBytes().toString(Charsets.UTF_8))
                }
            } finally { client.close() }
        }
        assertEquals(1, writes)
        val recording = recordings(directory).single()
        assertEquals("request", Files.readString(recording.resolve("request.bin")))
        assertEquals("audio\u0000payload", Files.readString(recording.resolve("response.bin")))
        assertContains(Files.readString(recording.resolve("events.jsonl")), "sdk-secret")
    }

    @Test
    fun anthropicSdkInterceptorPreservesCanonicalRequestAndResponse() = configured { directory ->
        val rawResponse = """{"id":"msg_test","type":"message","role":"assistant","model":"claude-test",
            "content":[{"type":"text","text":"answer"}],"stop_reason":"end_turn","stop_sequence":null,
            "usage":{"input_tokens":10,"output_tokens":5}}"""
        SdkHttpTestServer(rawResponse).use { server ->
            val client = com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()
                .baseUrl(server.origin).apiKey("anthropic-secret").proxy(java.net.Proxy.NO_PROXY)
                .addInterceptor { AiTrafficDumpAnthropicClient(it) }.build()
            try {
                client.messages().create(com.anthropic.models.messages.MessageCreateParams.builder()
                    .model("claude-test").maxTokens(8).addUserMessage("full private prompt").build())
            } finally { client.close() }
            val recording = recordings(directory).single()
            assertEquals(server.requests.single().body, Files.readString(recording.resolve("request.bin")))
            assertEquals(rawResponse, Files.readString(recording.resolve("response.bin")))
            assertContains(Files.readString(recording.resolve("events.jsonl")), "anthropic-secret")
        }
    }

    @Test
    fun rawBytesAndSplitSurrogatesArePreservedWithOrderedOffsets() = configured { directory ->
        val trace = assertNotNull(DangerousAiTrafficDump.open("test", "bytes"))
        val source = byteArrayOf(0, -1, -128, 65, 10)
        val buffer = ByteBuffer.wrap(source).apply { position(1) }
        trace.bytes("raw", buffer)
        assertEquals(1, buffer.position())
        trace.bytes("raw", byteArrayOf(7, 8))
        trace.textFragment("fragment", "\uD83D")
        trace.textFragment("fragment", "\uDCF7")
        val recording = recordings(directory).single()
        assertContentEquals(source.copyOfRange(1, source.size) + byteArrayOf(7, 8), Files.readAllBytes(recording.resolve("raw.bin")))
        assertEquals("📷", Files.readAllBytes(recording.resolve("fragment.bin")).toString(Charsets.UTF_16BE))
        val events = Files.readAllLines(recording.resolve("events.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(events.indices.map { it.toString() }, events.map { it.getValue("sequence").jsonPrimitive.content })
        val offsets = events.filter { it.getValue("event").jsonPrimitive.content == "data" }
            .map { it.getValue("details").jsonObject.getValue("offset").jsonPrimitive.content }
        assertEquals(listOf("0", "4", "0", "2"), offsets)
        if (Files.getFileStore(recording).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(recording.resolve("raw.bin"))))
        }
    }

    @Test
    fun cliCapturePreservesAllThreeStreamsAndSystemPrompt() = configured { directory ->
        val written = ByteArrayOutputStream()
        val process = object : Process() {
            override fun getOutputStream() = written
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf(0, -1, 10))
            override fun getErrorStream() = ByteArrayInputStream("private stderr".toByteArray())
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
            override fun pid() = 42L
            override fun onExit() = CompletableFuture.completedFuture<Process>(this)
        }
        val captured = AiTrafficDumpProcess.capture(process, "claude-code", listOf("claude", "--private"), "full system prompt")
        captured.outputStream.write("full stdin".toByteArray())
        assertContentEquals(byteArrayOf(0, -1, 10), captured.inputStream.readBytes())
        assertEquals("private stderr", captured.errorStream.reader().readText())
        assertEquals("full stdin", written.toString(Charsets.UTF_8))
        val recording = recordings(directory).single()
        assertEquals("full stdin", Files.readString(recording.resolve("stdin.bin")))
        assertContentEquals(byteArrayOf(0, -1, 10), Files.readAllBytes(recording.resolve("stdout.bin")))
        assertEquals("private stderr", Files.readString(recording.resolve("stderr.bin")))
        assertEquals("full system prompt", Files.readString(recording.resolve("system-prompt.bin")))
    }

    @Test
    fun copilotRpcCaptureKeepsUnredactedMessagesOutOfParentLoggers() = configured { directory ->
        val name = "gromozeka-test-copilot-${java.util.UUID.randomUUID()}"
        DangerousAiTrafficDump.captureJavaLogger(name, "github-copilot")
        val logger = Logger.getLogger(name)
        logger.fine("Sent: {\"token\":\"raw-secret\"}")
        logger.fine("Received: invalid JSON preserved")
        val recording = recordings(directory).single()
        val raw = Files.readString(recording.resolve("rpc.bin"))
        assertContains(raw, "raw-secret")
        assertContains(raw, "invalid JSON preserved")
        assertFalse(logger.useParentHandlers)
    }

    @Test
    fun unwritableDumpLocationDoesNotFailTheRequest() = configured { directory ->
        Files.writeString(directory.resolve("ai-traffic"), "blocks directory creation")
        SdkHttpTestServer("still works").use { server ->
            AiTrafficDumpHttpClient(HttpClient.newHttpClient(), "test").use { client ->
                assertEquals("still works", client.send(HttpRequest.newBuilder(URI(server.origin)).GET().build(), HttpResponse.BodyHandlers.ofString()).body())
            }
        }
    }

    @Test
    fun capturedHttpResponseRemainsCancellableWhileStreaming() = configured {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("partial".toByteArray())
                exchange.responseBody.flush()
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = AiTrafficDumpHttpClient(HttpClient.newHttpClient(), "test")
        try {
            val pending = client.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(pending.cancel(true))
            assertTrue(pending.isCompletedExceptionally)
            assertFails { pending.join() }
        } finally { release.countDown(); server.stop(0); client.close() }
    }

    @Test
    fun recordsBufferedResponseReadFailureWithoutChangingExceptionOrPartialBytes() = configured { directory ->
        val trace = assertNotNull(DangerousAiTrafficDump.open("test", "sdk-http"))
        val failure = IOException("synthetic-response-read-failure")
        val source = object : ByteArrayInputStream("partial".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) throw failure
                return super.read(buffer, offset, length)
            }
        }
        val actual = assertFailsWith<IOException> { trace.input("response", source).use { it.readBytes() } }
        assertSame(failure, actual)
        val recording = recordings(directory).single()
        assertEquals("partial", Files.readString(recording.resolve("response.bin")))
        assertContains(Files.readString(recording.resolve("error.bin")), failure.message!!)
    }

    @Test
    fun recordsSingleByteResponseReadFailureWithoutChangingException() = configured { directory ->
        val trace = assertNotNull(DangerousAiTrafficDump.open("test", "sdk-http"))
        val failure = IOException("synthetic-single-byte-read-failure")
        val source = object : InputStream() {
            override fun read(): Int = throw failure
        }
        val actual = assertFailsWith<IOException> { trace.input("response", source).use { it.read() } }
        assertSame(failure, actual)
        assertContains(Files.readString(recordings(directory).single().resolve("error.bin")), failure.message!!)
    }

    @Test
    fun recordsOkHttpResponseReadFailureAfterHeadersHaveReturned() = configured { directory ->
        val failure = IOException("synthetic-response-read-failure")
        val source = object : Source {
            private var delivered = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (delivered) throw failure
                delivered = true
                sink.writeUtf8("partial")
                return 7L
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = OkHttpClient.Builder()
            .addInterceptor(AiTrafficDumpInterceptor("test"))
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(object : ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = -1L
                        override fun source() = source
                    }).build()
            }.build()
        try {
            val actual = assertFailsWith<IOException> {
                client.newCall(Request.Builder().url("http://127.0.0.1/synthetic-no-network").build())
                    .execute().use { it.body!!.string() }
            }
            assertSame(failure, actual)
            val recording = recordings(directory).single()
            assertEquals("partial", Files.readString(recording.resolve("response.bin")))
            assertContains(Files.readString(recording.resolve("error.bin")), failure.message!!)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun preservesEmptyWebSocketControlFrameBoundariesAndOffsets() = configured { directory ->
        val trace = assertNotNull(DangerousAiTrafficDump.open("test", "websocket"))
        val channels = listOf("send.ping", "receive.ping", "send.pong", "receive.pong")
        channels.forEach { trace.bytes(it, ByteBuffer.allocate(0)) }
        trace.bytes("send.ping", byteArrayOf(1, 2))
        trace.bytes("send.ping", ByteBuffer.allocate(0))
        val recording = recordings(directory).single()
        val data = Files.readAllLines(recording.resolve("events.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.getValue("event").jsonPrimitive.content == "data" }
            .map { it.getValue("details").jsonObject }
        assertEquals((channels + listOf("send.ping", "send.ping")).map { "$it.bin" },
            data.map { it.getValue("file").jsonPrimitive.content })
        assertEquals(listOf("0", "0", "0", "0", "2", "0"),
            data.map { it.getValue("length").jsonPrimitive.content })
        assertEquals(listOf("0", "0", "0", "0", "0", "2"),
            data.map { it.getValue("offset").jsonPrimitive.content })
        assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(recording.resolve("send.ping.bin")))
        channels.drop(1).forEach { assertEquals(0L, Files.size(recording.resolve("$it.bin"))) }
    }

    private fun recordings(directory: Path): List<Path> = Files.list(directory.resolve("ai-traffic")).use { it.toList() }

    private fun configured(enabled: Boolean = true, action: (Path) -> Unit) {
        val previousFlag = System.getProperty(DangerousAiTrafficDump.FLAG)
        val previousPath = System.getProperty("logging.file.path")
        val directory = Files.createTempDirectory("gromozeka-traffic-test-")
        try {
            System.setProperty(DangerousAiTrafficDump.FLAG, enabled.toString())
            System.setProperty("logging.file.path", directory.toString())
            action(directory)
        } finally {
            if (previousFlag == null) System.clearProperty(DangerousAiTrafficDump.FLAG) else System.setProperty(DangerousAiTrafficDump.FLAG, previousFlag)
            if (previousPath == null) System.clearProperty("logging.file.path") else System.setProperty("logging.file.path", previousPath)
            directory.toFile().deleteRecursively()
        }
    }
}
