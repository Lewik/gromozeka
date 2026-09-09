package com.gromozeka.server.telegram

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.*

class TelegramHttpApiTest {
    @Test fun `already applied edit is an idempotent success but other bad requests fail`(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var description = "Bad Request: message is not modified"
        server.createContext("/") { exchange ->
            val body = buildJsonObject { put("ok", false); put("error_code", 400); put("description", description) }.toString().encodeToByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = TelegramHttpApi("123:test", URI("http://127.0.0.1:${server.address.port}/"))
            val parameters = buildJsonObject { put("chat_id", -123); put("message_id", 77); put("text", "reply") }
            assertEquals(77, api.call("editMessageText", parameters).jsonObject.long("message_id"))
            assertFailsWith<TelegramApiFailure> { api.call("sendMessage", parameters) }
            description = "Bad Request: message to edit not found"
            assertFailsWith<TelegramApiFailure> { api.call("editMessageText", parameters) }
        } finally { server.stop(0) }
    }

    @Test fun `external bytes renew file paths and enforce metadata and stream limits`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lookups = 0
        var downloads = 0
        var declaredSize = 3
        var payload = "abc"
        server.createContext("/") { exchange ->
            val response = if (exchange.requestURI.path.endsWith("/getFile")) {
                lookups++
                """{"ok":true,"result":{"file_path":"photos/current-$lookups.jpg","file_size":$declaredSize}}"""
            } else {
                downloads++
                assertTrue(exchange.requestURI.path.endsWith("current-$lookups.jpg"))
                payload
            }.encodeToByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val api = TelegramHttpApi("123:test", URI("http://127.0.0.1:${server.address.port}/"))
            assertEquals("abc", api.download("stable-id", 5).decodeToString())
            assertEquals("abc", api.download("stable-id", 5).decodeToString())
            assertEquals(2, lookups); assertEquals(2, downloads)
            declaredSize = 100
            assertFailsWith<com.gromozeka.domain.service.ArtifactContentUnavailableException> { api.download("stable-id", 5) }
            assertEquals(2, downloads)
            declaredSize = 0; payload = "too many bytes"
            assertFailsWith<com.gromozeka.domain.service.ArtifactContentUnavailableException> { api.download("stable-id", 5) }
            assertEquals(3, downloads)
        } finally { server.stop(0) }
    }

    @Test
    fun `Bot API requests use UTF8 JSON and preserve response metadata`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var path: String? = null
        var body: JsonObject? = null
        var method: String? = null
        server.createContext("/") {
            path = it.requestURI.path
            method = it.requestMethod
            body = Json.parseToJsonElement(it.requestBody.readAllBytes().decodeToString()).jsonObject
            val result = """{"ok":true,"result":{"message_id":10,"date":123}}""".encodeToByteArray()
            it.sendResponseHeaders(200, result.size.toLong())
            it.responseBody.use { output -> output.write(result) }
        }
        server.start()
        try {
            val result = TelegramHttpApi("123:test", URI("http://127.0.0.1:${server.address.port}/"))
                .call("sendMessage", buildJsonObject { put("chat_id", -123); put("text", "Привет 😀") })
            assertEquals("POST", method)
            assertEquals("/bot123:test/sendMessage", path)
            assertEquals("Привет 😀", body?.string("text"))
            assertEquals(10, result.jsonObject.long("message_id"))
        } finally { server.stop(0) }
    }

    @Test
    fun `retry_after is decoded but remote descriptions cannot expose token`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") {
            val response = """{"ok":false,"error_code":429,"description":"123:SECRET", "parameters":{"retry_after":17}}"""
                .encodeToByteArray()
            it.sendResponseHeaders(429, response.size.toLong())
            it.responseBody.use { output -> output.write(response) }
        }
        server.start()
        try {
            val error = assertFailsWith<TelegramApiFailure> {
                TelegramHttpApi("123:SECRET", URI("http://127.0.0.1:${server.address.port}/")).call("getUpdates")
            }
            assertEquals(17, error.retryAfterSeconds)
            assertFalse(error.stackTraceToString().contains("SECRET"))
        } finally { server.stop(0) }
    }

    @Test
    fun `malformed reply is an uncertain delivery not a retryable success`(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") {
            it.sendResponseHeaders(502, 3)
            it.responseBody.use { output -> output.write("bad".encodeToByteArray()) }
        }
        server.start()
        try {
            assertFailsWith<TelegramDeliveryUncertain> {
                TelegramHttpApi("123:SECRET", URI("http://127.0.0.1:${server.address.port}/")).call("sendMessage")
            }
        } finally { server.stop(0) }
    }
}
