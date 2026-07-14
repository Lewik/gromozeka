package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class OpenAiSubscriptionResponsesClientTest {
    @Test
    fun cancelsHttpFallbackWithCallerCoroutine() = runBlocking {
        val releaseResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/responses") { exchange ->
            if (exchange.requestMethod == "GET") {
                exchange.sendResponseHeaders(426, -1)
                exchange.close()
            } else {
                releaseResponse.await(15, TimeUnit.SECONDS)
                exchange.close()
            }
        }
        server.start()

        try {
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
                requestMapper = OpenAiSubscriptionRequestMapper(),
                baseUrl = "http://127.0.0.1:${server.address.port}",
                clientVersion = "1.4.9",
                websocketIdleMs = 300_000,
                websocketResponseTimeoutMs = 5_000,
                websocketTransportTimeoutMs = 1_000,
                httpResponseTimeoutMs = 10_000,
            )

            val elapsed = measureTime {
                assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                    withTimeout(2.seconds) {
                        client.create(
                            session = OpenAiSubscriptionSession(
                                accessToken = "access-token",
                                refreshToken = "refresh-token",
                                idToken = null,
                                accountId = "account-1",
                                expiresAt = Long.MAX_VALUE,
                            ),
                            conversationKey = "conversation-cancel",
                            requestBody = OpenAiSubscriptionResponsesRequest(
                                model = profile.slug,
                                input = emptyList(),
                            ),
                            modelProfile = profile,
                            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
                        )
                    }
                }
            }

            assertTrue(elapsed < 5.seconds, "HTTP fallback ignored coroutine cancellation for $elapsed")
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

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
                assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
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

    @Test
    fun sendsOnlyNewTransportItemsForStrictContinuation() {
        val prefix = buildJsonObject {
            put("type", JsonPrimitive("additional_tools"))
        }
        val firstUser = buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("content", JsonPrimitive("first"))
        }
        val assistant = buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("content", JsonPrimitive("answer"))
        }
        val secondUser = buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("content", JsonPrimitive("second"))
        }
        val firstRequest = OpenAiSubscriptionResponsesRequest(
            model = "gpt-5.6-luna",
            input = listOf(prefix, firstUser),
            tools = null,
        )
        val state = OpenAiSubscriptionIncrementalState()

        val initialPlan = state.plan(firstRequest, transportSignature = "shape")
        assertEquals(OpenAiSubscriptionRequestMode.FULL, initialPlan.mode)
        assertEquals(listOf(prefix, firstUser), initialPlan.request.input)
        assertNull(initialPlan.request.previousResponseId)

        state.record(
            transportSignature = "shape",
            responseId = "resp-1",
            expectedNextInputPrefix = firstRequest.input + assistant,
        )
        val continuation = firstRequest.copy(
            input = firstRequest.input + assistant + secondUser,
        )

        val incrementalPlan = state.plan(continuation, transportSignature = "shape")
        assertEquals(OpenAiSubscriptionRequestMode.INCREMENTAL, incrementalPlan.mode)
        assertEquals(listOf(secondUser), incrementalPlan.request.input)
        assertEquals("resp-1", incrementalPlan.request.previousResponseId)
        assertTrue(incrementalPlan.reason.contains("strict_extension"))
    }

    @Test
    fun sendsFullRequestWhenTransportShapeChangesOrStateIsCleared() {
        val input = buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("content", JsonPrimitive("first"))
        }
        val request = OpenAiSubscriptionResponsesRequest(
            model = "gpt-5.6-luna",
            input = listOf(input),
        )
        val state = OpenAiSubscriptionIncrementalState()
        state.record(
            transportSignature = "shape-1",
            responseId = "resp-1",
            expectedNextInputPrefix = request.input,
        )

        val changedShapePlan = state.plan(request, transportSignature = "shape-2")
        assertEquals(OpenAiSubscriptionRequestMode.FULL, changedShapePlan.mode)
        assertEquals("request_shape_changed", changedShapePlan.reason)
        assertNull(changedShapePlan.request.previousResponseId)

        state.clear()
        val clearedPlan = state.plan(request, transportSignature = "shape-1")
        assertEquals(OpenAiSubscriptionRequestMode.FULL, clearedPlan.mode)
        assertEquals("missing_previous_response", clearedPlan.reason)
    }
}
