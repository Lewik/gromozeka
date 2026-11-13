package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiModelConfiguration
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAiSubscriptionWebSocketLifetimeTest {
    @Test
    fun activeResponseSurvivesIdleEvictionTriggeredByAnotherConversation() = runBlocking {
        val peer = Peer()
        val client = peer.client()
        val slow = async { client.request("slow") }
        try {
            assertEquals("slow", withTimeout(5_000) { peer.requests.receive() }.conversation)
            delay(600)
            assertEquals("completed", client.request("other").completed?.status)
            peer.release.complete(Unit)
            assertEquals("completed", withTimeout(3_000) { slow.await() }.completed?.status)
            assertEquals("completed", client.request("slow").completed?.status)
            assertEquals(2, peer.connections.get())
        } finally {
            peer.release.complete(Unit)
            slow.cancelAndJoin()
            peer.stop()
        }
    }

    @Test
    fun idleSessionIsEvictedAndNextRequestStartsWithFullContext() = runBlocking {
        val peer = Peer()
        try {
            val client = peer.client()
            client.request("idle")
            assertEquals("idle", peer.requests.receive().conversation)
            delay(600)
            client.request("other")
            assertEquals("other", peer.requests.receive().conversation)
            client.request("idle")
            val fresh = peer.requests.receive()
            assertEquals("idle", fresh.conversation)
            assertFalse("previous_response_id" in fresh.body)
            assertEquals(3, peer.connections.get())
        } finally {
            peer.stop()
        }
    }

    @Test
    fun cancellingAQueuedRequestReleasesItsSessionReservation() = runBlocking {
        val peer = Peer()
        val client = peer.client()
        val active = async { client.request("slow") }
        try {
            withTimeout(5_000) { peer.requests.receive() }
            val queued = async { client.request("slow") }
            delay(600)
            queued.cancelAndJoin()
            client.request("other")
            assertEquals("other", peer.requests.receive().conversation)
            peer.release.complete(Unit)
            assertEquals("completed", withTimeout(3_000) { active.await() }.completed?.status)
            delay(600)
            client.request("slow")
            assertEquals("slow", peer.requests.receive().conversation)
            assertEquals(3, peer.connections.get())
        } finally {
            peer.release.complete(Unit)
            active.cancelAndJoin()
            peer.stop()
        }
    }

    private suspend fun OpenAiSubscriptionResponsesClient.request(conversation: String) = create(
        configuration = testModelConfiguration(),
        session = OpenAiSubscriptionSession("test-token", "test-refresh", null, "test-account", Long.MAX_VALUE),
        conversationKey = conversation,
        requestBody = OpenAiSubscriptionResponsesRequest("gpt-5.6-sol", listOf(buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("role", JsonPrimitive("user"))
            put("content", JsonPrimitive("Synthetic request ${UUID.randomUUID()}"))
        })),
        modelProfile = OpenAiSubscriptionModelProfile(
            slug = "gpt-5.6-sol", useResponsesLite = true, supportsReasoningSummaries = true,
            supportedReasoningEfforts = listOf("low"), supportsVerbosity = true,
            defaultVerbosity = "low", supportsParallelToolCalls = true,
        ),
        assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
    )

    private data class Request(val conversation: String, val body: JsonObject)

    private class Peer {
        val release = CompletableDeferred<Unit>()
        val requests = Channel<Request>(Channel.UNLIMITED)
        val connections = AtomicInteger()
        private val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/responses") {
                    val connection = connections.incrementAndGet()
                    val conversation = requireNotNull(call.request.headers["conversation_id"])
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        requests.send(Request(conversation, Json.parseToJsonElement(frame.readText()).jsonObject))
                        if (conversation == "slow") release.await()
                        send("""{"type":"response.completed","response":{"id":"response-$connection","status":"completed","output":[]}}""")
                    }
                }
            }
        }.start(wait = false)

        suspend fun client() = OpenAiSubscriptionResponsesClient(
            responseMapper = OpenAiSubscriptionResponseMapper(),
            requestMapper = OpenAiSubscriptionRequestMapper(),
            baseUrl = "http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}",
            clientVersion = "test", websocketIdleMs = 300,
            websocketResponseTimeoutMs = 10_000, websocketTransportTimeoutMs = 2_000,
            httpResponseTimeoutMs = 2_000, admission = TestAiAdmission(),
        )

        fun stop() { engine.stop(0, 1_000) }
    }
}
