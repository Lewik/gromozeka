package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
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
    fun `compaction does not create a new web-search turn boundary`() {
        val source = userMessage("Archived user request")
        val summary = message(Conversation.Message.Role.ASSISTANT, listOf(ContentItem.ContextCompactionResult(
            ContentItem.ContextCompactionResult.Payload.ReadableSummary("handoff"), ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
            sourceMessageIds = listOf(source.id), coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES)))
        val history = listOf(source, summary) + persisted(mapResponse(listOf(webCall)))
        val projected = AiRuntimeRequest(emptyList(), history).projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        assertEquals(webCall["call_id"], search.pendingCall(projected, source.id, connectionId, model)?.get("call_id"))
        assertFalse(search.recentInput(projected, source.id).toString().contains("Archived user request"))
    }

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
            val request = request(listOf(userMessage("Search")) + persisted(mapResponse(listOf(webCall))))
            assertFailsWith<OpenAiSubscriptionUnauthorizedException> {
                search.executePending(request, session, "conversation", testModelConfiguration(connectionId, model), true)
            }
            status = 503
            val failed = assertNotNull(search.executePending(request, session, "conversation", testModelConfiguration(connectionId, model), true))
            val result = failed.messages.single().content.single() as ContentItem.ToolResult
            assertTrue(result.isError)
            assertEquals("Web search failed (HTTP 503)", (result.result.single() as ContentItem.ToolResult.Data.Text).content)
            assertNull(search.executePending(request.copy(messages = request.messages + persisted(failed)), session, "conversation", testModelConfiguration(connectionId, model), true))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun persistsNativeCallBeforeSearchAndReplaysCompletedResultExactlyOnce() = runBlocking {
        val calls = mutableListOf<JsonObject>()
        val output = "Source [Example](https://example.com)\nPDF text: \u0000\u0001, literal: \\u0000"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/alpha/search") { exchange ->
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestHeaders.getFirst("x-openai-internal-codex-responses-lite"))
            calls += Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            val body = buildJsonObject {
                put("output", output)
                put("encrypted_output", "opaque-secret")
                put("results", JsonArray(emptyList()))
            }.toString().toByteArray()
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
            assertNotNull(search.pendingCall(history, history.first().id, connectionId, model))
            assertNull(search.pendingCall(history, history.first().id, "other", model))
            assertNull(search.pendingCall(history, history.first().id, connectionId, "other-model"))
            val result = assertNotNull(search.executePending(request(history), session, "conversation", testModelConfiguration(connectionId, model), true))
            val toolResult = result.messages.single().content.single() as ContentItem.ToolResult
            assertEquals(output, (toolResult.result.single() as ContentItem.ToolResult.Data.Text).content)
            history += persisted(result)
            assertEquals(1, calls.size)
            assertEquals("conversation", calls.single()["id"]?.jsonPrimitive?.content)
            assertEquals(model, calls.single()["model"]?.jsonPrimitive?.content)
            assertEquals(JsonPrimitive(true), calls.single()["settings"]?.jsonObject?.get("external_web_access"))
            assertNull(OpenAiSubscriptionWebSearch(client("http://127.0.0.1:${server.address.port}"))
                .executePending(request(history), session, "conversation", testModelConfiguration(connectionId, model), true))
            assertEquals(1, calls.size)
            val replay = OpenAiSubscriptionRequestMapper().toRequest(
                AiRuntimeRequest(emptyList(), history), profile, "conversation", true, connectionId,
            ).input
            assertEquals(listOf("message", "function_call", "function_call_output"), replay.map { it["type"]?.jsonPrimitive?.content })
            assertEquals("web", replay[1]["namespace"]?.jsonPrimitive?.content)
            assertEquals("web-call", replay[2]["call_id"]?.jsonPrimitive?.content)
            assertFalse(replay.toString().contains("opaque-secret"))
            assertTrue(replay[2].toString().contains("https://example.com"))
            assertEquals(
                "Source [Example](https://example.com)\nPDF text: \\u0000\\u0001, literal: \\u0000",
                replay[2].getValue("output").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
            )
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
            val result = assertNotNull(search.executePending(request(history, options), session, "conversation", testModelConfiguration(connectionId, model), true))
            assertTrue((result.messages.single().content.single() as ContentItem.ToolResult).isError)
        }
        val newTurn = userMessage("New turn")
        assertNull(search.pendingCall(history + newTurn, newTurn.id, connectionId, model))
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
        val input = search.recentInput(history, history.last { it.role == Conversation.Message.Role.USER }.id)
        assertEquals(3, input.size)
        assertFalse(input.toString().contains("private reasoning"))
        assertFalse(input.toString().contains("function_call"))
        assertEquals(4000, input[1]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun searchContinuationIncludesTrailingRuntimeJson() {
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        val runtime = message(Conversation.Message.Role.RUNTIME, listOf(ContentItem.RuntimeJson(buildJsonObject {
            put("kind", "panel_state")
            put("revision", 7)
        })))
        val old = userMessage("old")
        val current = userMessage("current")
        val input = search.recentInput(listOf(
            old,
            current,
            message(Conversation.Message.Role.ASSISTANT, listOf(
                ContentItem.AssistantMessage(Conversation.Message.StructuredText("answer")),
            )),
            runtime,
        ), current.id)

        assertEquals(4, input.size)
        assertContains(input.first().toString(), "old")
        assertEquals("user", input.last().getValue("role").jsonPrimitive.content)
        assertEquals("input_text", input.last().getValue("content").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content)
        assertContains(input.last().toString(), "panel_state")
        assertContains(input.last().toString(), "revision")
    }

    @Test
    fun `runtime updates do not evict the human web search request`() {
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        val query = userMessage("Find the current release notes")
        val runtimeOne = message(Conversation.Message.Role.RUNTIME, listOf(ContentItem.RuntimeJson(JsonPrimitive("state-one"))))
        val runtimeTwo = message(Conversation.Message.Role.RUNTIME, listOf(ContentItem.RuntimeJson(JsonPrimitive("state-two"))))

        val input = search.recentInput(listOf(query, runtimeOne, runtimeTwo), query.id)

        assertEquals(3, input.size)
        assertContains(input[0].toString(), "Find the current release notes")
        assertContains(input[1].toString(), "state-one")
        assertContains(input[2].toString(), "state-two")
    }

    @Test
    fun `search input bounds runtime updates without dropping the human query`() {
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        val query = userMessage("Find the current release notes")
        val olderUpdates = List(100) { revision ->
            message(Conversation.Message.Role.RUNTIME, listOf(
                ContentItem.RuntimeJson(JsonPrimitive("old-state-$revision-${"x".repeat(200)}")),
            ))
        }
        val oversizedLatest = message(Conversation.Message.Role.RUNTIME, listOf(
            ContentItem.RuntimeJson(JsonPrimitive("latest-state-${"y".repeat(100_000)}")),
        ))

        val input = search.recentInput(listOf(query) + olderUpdates + oversizedLatest, query.id)
        val inputTexts = input.filter { it.getValue("role").jsonPrimitive.content == "user" }.map {
            it.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        }
        val combined = inputTexts.joinToString("\n")

        assertEquals(16_000, inputTexts.sumOf(String::length))
        assertContains(combined, "Find the current release notes")
        assertContains(combined, "latest-state")
        assertContains(combined, "[truncated]")
        assertFalse(combined.contains("old-state-0"))
        assertTrue(input.toString().length < 20_000)
    }

    @Test
    fun `runtime rooted turn does not execute an interrupted web call from an earlier turn`() = runBlocking {
        val search = OpenAiSubscriptionWebSearch(client("http://127.0.0.1:1"))
        val earlier = userMessage("Search the old topic")
        val interrupted = persisted(mapResponse(listOf(webCall)))
        val runtimeRoot = message(
            Conversation.Message.Role.RUNTIME,
            listOf(ContentItem.RuntimeJson(JsonPrimitive("start a different operation"))),
        )
        val history = listOf(earlier) + interrupted + runtimeRoot
        val request = AiRuntimeRequest(
            emptyList(),
            history,
            options = AiRuntimeOptions(
                toolContext = mapOf(TOOL_CONTEXT_TARGET_MESSAGE_ID to runtimeRoot.id.value),
            ),
        )

        assertNull(search.pendingCall(history, runtimeRoot.id, connectionId, model))
        assertNull(search.executePending(request, session, "conversation", testModelConfiguration(connectionId, model), true))
    }

    companion object {
        const val connectionId = "web-test-connection"
        const val model = "gpt-6-astra"
        val profile = OpenAiSubscriptionModelProfile(model, true, false, listOf("low"), false, null, false)
        val session = OpenAiSubscriptionSession("test-token", "unused", null, "test-account", Long.MAX_VALUE)
        val webCall = Json.parseToJsonElement("""{"type":"function_call","namespace":"web","name":"run","call_id":"web-call","arguments":"{\"search_query\":[{\"q\":\"Example\"}]}"}""").jsonObject
        fun client(baseUrl: String) = OpenAiSubscriptionResponsesClient(OpenAiSubscriptionResponseMapper(), OpenAiSubscriptionRequestMapper(),
            baseUrl, "1.4.9", 30000, 120000, 30000, 120000, admission = TestAiAdmission())
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
        fun request(messages: List<Conversation.Message>, options: AiRuntimeOptions = AiRuntimeOptions()) = AiRuntimeRequest(
            emptyList(),
            messages,
            options = options.copy(
                toolContext = options.toolContext + (TOOL_CONTEXT_TARGET_MESSAGE_ID to messages.first().id.value),
            ),
        )
        fun userMessage(text: String) = message(Conversation.Message.Role.USER, listOf(ContentItem.UserMessage(text)))
        fun message(role: Conversation.Message.Role, content: List<ContentItem>) = Conversation.Message(
            Conversation.Message.Id(UUID.randomUUID().toString()), Conversation.Id("conversation"), role = role,
            content = content, createdAt = Clock.System.now(),
        )
    }
}
