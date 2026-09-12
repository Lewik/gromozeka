package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.tool.ToolAccessPolicy
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class OpenAiSubscriptionWebSearchTest {
    @Test
    fun searchErrorsPreserveAuthRefreshAndReturnOtherFailuresAsToolResults() = runBlocking {
        var status = 401
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/alpha/search") { exchange ->
            val body = "private provider diagnostics".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:${server.address.port}"))
            val request = AiRuntimeRequest(emptyList(), listOf(userMessage("Search")) + persisted(mapResponse(listOf(webCall))))
            assertFailsWith<OpenAiSubscriptionUnauthorizedException> {
                search.executePending(request, session, "conversation", connectionId, model, true)
            }
            status = 503
            val failed = assertNotNull(search.executePending(request, session, "conversation", connectionId, model, true))
            val result = failed.messages.single().content.single() as ContentItem.ToolResult
            assertTrue(result.isError)
            assertEquals("Web search failed (HTTP 503)", (result.result.single() as ContentItem.ToolResult.Data.Text).content)
            assertNull(search.executePending(request.copy(messages = request.messages + persisted(failed)), session, "conversation", connectionId, model, true))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun persistsNativeCallBeforeSearchAndReplaysCompletedResultExactlyOnce() = runBlocking {
        val calls = mutableListOf<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/alpha/search") { exchange ->
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestHeaders.getFirst("x-openai-internal-codex-responses-lite"))
            calls += Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            val body = """{"output":"Source [Example](https://example.com)","encrypted_output":"opaque-secret","results":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val mapper = OpenAiSubscriptionResponseMapper()
            val response = mapResponse(listOf(webCall), mapper)
            assertEquals(AiStepOutcome.CONTINUE, response.outcome)
            assertTrue(response.toolCalls.isEmpty())
            assertEquals("web.run", (response.messages.single().content.single() as ContentItem.ToolCall).call.name)
            assertTrue(calls.isEmpty())
            val history = mutableListOf(userMessage("Find Example"))
            history += persisted(response)
            val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:${server.address.port}"))
            assertNotNull(search.pendingCall(history, connectionId, model))
            assertNull(search.pendingCall(history, "other", model))
            assertNull(search.pendingCall(history, connectionId, "other-model"))
            val result = assertNotNull(search.executePending(AiRuntimeRequest(emptyList(), history), session, "conversation", connectionId, model, true))
            history += persisted(result)
            assertEquals(1, calls.size)
            assertEquals("conversation", calls.single()["id"]?.jsonPrimitive?.content)
            assertEquals(model, calls.single()["model"]?.jsonPrimitive?.content)
            assertEquals(JsonPrimitive(true), calls.single()["settings"]?.jsonObject?.get("external_web_access"))
            assertNull(OpenAiSubscriptionWebSearch(client("http://127.0.0.1:${server.address.port}"))
                .executePending(AiRuntimeRequest(emptyList(), history), session, "conversation", connectionId, model, true))
            assertEquals(1, calls.size)
            val replay = OpenAiSubscriptionRequestMapper().toRequest(
                AiRuntimeRequest(emptyList(), history), profile, "conversation", true, connectionId,
            ).input
            assertEquals(listOf("message", "function_call", "function_call_output"), replay.map { it["type"]?.jsonPrimitive?.content })
            assertEquals("web", replay[1]["namespace"]?.jsonPrimitive?.content)
            assertEquals("web-call", replay[2]["call_id"]?.jsonPrimitive?.content)
            assertFalse(replay.toString().contains("opaque-secret"))
            assertTrue(replay[2].toString().contains("https://example.com"))
            val foreign = OpenAiSubscriptionRequestMapper().toRequest(
                AiRuntimeRequest(emptyList(), history), profile, "conversation", true, "other",
            ).input
            assertTrue(foreign.all { it["type"] == JsonPrimitive("message") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun disabledOrInterruptedSearchNeverExecutesAndIncompleteBatchesCannotScheduleTools() = runBlocking {
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        val history = listOf(userMessage("Search")) + persisted(mapResponse(listOf(webCall)))
        for (options in listOf(AiRuntimeOptions(toolChoice = AiToolChoice.None), AiRuntimeOptions(toolAccess = ToolAccessPolicy.AllowOnly()))) {
            val result = assertNotNull(search.executePending(AiRuntimeRequest(emptyList(), history, options = options), session, "conversation", connectionId, model, true))
            assertTrue((result.messages.single().content.single() as ContentItem.ToolResult).isError)
        }
        assertNull(search.pendingCall(history + userMessage("New turn"), connectionId, model))
        assertEquals(AiStepOutcome.INCOMPLETE, mapResponse(listOf(webCall), status = "incomplete").outcome)
        assertTrue(mapResponse(listOf(webCall), status = "incomplete").messages.isEmpty())
        val refusal = Json.parseToJsonElement("""{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"No"}]}""").jsonObject
        val refused = mapResponse(listOf(webCall, refusal))
        assertEquals(AiStepOutcome.REFUSED, refused.outcome)
        assertTrue(refused.messages.flatMap { it.content }.none { it is ContentItem.ToolCall })
        val ordinary = JsonObject(webCall + mapOf("namespace" to JsonPrimitive("functions"), "name" to JsonPrimitive("read_file"), "call_id" to JsonPrimitive("ordinary")))
        val mixed = mapResponse(listOf(webCall, ordinary))
        assertEquals(AiStepOutcome.TOOL_CALLS, mixed.outcome)
        assertEquals(listOf("read_file"), mixed.toolCalls.map { it.call.name })
    }

    @Test
    fun searchReceivesOnlyRecentVisibleText() {
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        val history = listOf(userMessage("old"), userMessage("previous"), message(
            Conversation.Message.Role.ASSISTANT, listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("x".repeat(5000))),
                ContentItem.Thinking("private reasoning")),
        ), userMessage("current")) + persisted(mapResponse(listOf(webCall)))
        val input = search.recentInput(history)
        assertEquals(3, input.size)
        assertFalse(input.toString().contains("private reasoning"))
        assertFalse(input.toString().contains("function_call"))
        assertEquals(4000, input[1]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.length)
    }

    companion object {
        const val connectionId = "web-test-connection"
        const val model = "gpt-6-astra"
        val profile = OpenAiSubscriptionModelProfile(model, true, false, listOf("low"), false, null, false)
        val session = OpenAiSubscriptionSession("test-token", "unused", null, "test-account", Long.MAX_VALUE)
        val webCall = Json.parseToJsonElement("""{"type":"function_call","namespace":"web","name":"run","call_id":"web-call","arguments":"{\"search_query\":[{\"q\":\"Example\"}]}"}""").jsonObject
        fun client(baseUrl: String) = OpenAiSubscriptionResponsesClient(OpenAiSubscriptionResponseMapper(), OpenAiSubscriptionRequestMapper(),
            baseUrl, "1.4.9", 30000, 120000, 30000, 120000)
        fun mapResponse(items: List<JsonObject>, mapper: OpenAiSubscriptionResponseMapper = OpenAiSubscriptionResponseMapper(), status: String = "completed") =
            mapper.toRuntimeResponse(items, OpenAiSubscriptionCompletedResponse("response", status), "conversation", connectionId,
                "web-test-model", model, AiModelConfiguration.AssistantResponseFormat.TEXT)
        fun persisted(response: AiRuntimeResponse) = response.messages.map { assistant ->
            message(Conversation.Message.Role.ASSISTANT, assistant.content).copy(providerMetadata = JsonObject(
                (response.providerMetadata + assistant.metadata).mapValues { (_, value) -> when (value) {
                    is JsonElement -> value
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                } }
            ))
        }
        fun userMessage(text: String) = message(Conversation.Message.Role.USER, listOf(ContentItem.UserMessage(text)))
        fun message(role: Conversation.Message.Role, content: List<ContentItem>) = Conversation.Message(
            Conversation.Message.Id(UUID.randomUUID().toString()), Conversation.Id("conversation"), role = role,
            content = content, createdAt = Clock.System.now(),
        )
    }
}
