package com.gromozeka.infrastructure.ai.openai.subscription

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OpenAiSubscriptionResponsesClientTest {
    @Test
    fun sendsResponsesLiteHttpContractAfterWebSocketIsUnavailable() = runBlocking {
        var requestBody: JsonObject? = null
        var requestHeaders: Map<String, List<String>> = emptyMap()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/responses") { exchange ->
            if (exchange.requestMethod == "GET") {
                exchange.sendResponseHeaders(426, -1)
                exchange.close()
            } else {
                requestHeaders = exchange.requestHeaders
                requestBody = Json.parseToJsonElement(
                    exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                ).jsonObject
                val body = """
                    data: {"type":"response.completed","response":{"id":"resp_test","status":"completed","output":[]}}

                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()

        try {
            val mapper = OpenAiSubscriptionRequestMapper()
            val profile = OpenAiSubscriptionModelProfile(
                slug = "gpt-5.6-sol",
                useResponsesLite = true,
                supportsReasoningSummaries = true,
                supportedReasoningEfforts = listOf("low", "max"),
                supportsVerbosity = true,
                defaultVerbosity = "low",
                supportsParallelToolCalls = true,
            )
            val client = OpenAiSubscriptionResponsesClient(
                responseMapper = OpenAiSubscriptionResponseMapper(),
                requestMapper = mapper,
                baseUrl = "http://127.0.0.1:${server.address.port}",
                clientVersion = "1.4.9",
                websocketIdleMs = 300_000,
                websocketResponseTimeoutMs = 5_000,
                websocketTransportTimeoutMs = 1_000,
                httpResponseTimeoutMs = 5_000,
            )
            val logicalRequest = OpenAiSubscriptionResponsesRequest(
                model = profile.slug,
                input = emptyList(),
                instructions = "Base instructions",
                tools = listOf(JsonObject(mapOf("type" to JsonPrimitive("function")))),
                reasoning = JsonObject(mapOf("effort" to JsonPrimitive("low"))),
                include = listOf("reasoning.encrypted_content"),
            )

            val response = client.create(
                session = OpenAiSubscriptionSession(
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    idToken = null,
                    accountId = "account-1",
                    expiresAt = Long.MAX_VALUE,
                ),
                conversationKey = "conversation-1",
                requestBody = logicalRequest,
                modelProfile = profile,
            )

            assertEquals("resp_test", response.completed?.id)
            assertEquals("codex_cli_rs", requestHeaders.getValue("Originator").single())
            assertEquals("codex_cli_rs/1.4.9 (Gromozeka; JVM)", requestHeaders.getValue("User-agent").single())
            assertEquals("true", requestHeaders.getValue("X-openai-internal-codex-responses-lite").single())
            val body = requireNotNull(requestBody)
            assertNull(body["instructions"])
            assertNull(body["tools"])
            assertFalse(body.getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
            assertEquals("additional_tools", body.getValue("input").jsonArray[0].jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("all_turns", body.getValue("reasoning").jsonObject.getValue("context").jsonPrimitive.content)
            assertEquals(1, (body.getValue("input").jsonArray[0].jsonObject.getValue("tools") as JsonArray).size)
        } finally {
            server.stop(0)
        }
    }
}
