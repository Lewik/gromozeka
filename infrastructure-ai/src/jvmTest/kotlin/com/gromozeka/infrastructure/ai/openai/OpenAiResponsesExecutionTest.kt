package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.infrastructure.ai.openai.siwc.OpenAiSiwcAccessTokenProvider
import com.gromozeka.infrastructure.ai.openai.siwc.OpenAiSiwcResourceError
import com.gromozeka.infrastructure.ai.openai.siwc.mapOpenAiSiwcResourceFailure
import com.gromozeka.infrastructure.ai.runtime.RoutingAiRuntimeProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpCallExecutor
import com.gromozeka.infrastructure.ai.runtime.StalledServer
import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission
import com.gromozeka.infrastructure.ai.runtime.credentialFingerprint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class OpenAiResponsesExecutionTest {
    private val httpClients = com.gromozeka.infrastructure.ai.runtime.SdkHttpClientPool()
    @AfterTest fun closeHttpClients() = httpClients.close()

    @Test
    fun `streaming and stored continuation are independent and reduce the actual serialized body`() = runBlocking {
        for (stream in listOf(false, true)) for (stored in listOf(false, true)) {
            ResponsesServer { exchange, request, index -> reply(exchange, response("opaque-parent-$index"), stream) }.use { server ->
                val state = OpenAiResponsesContinuations()
                val connection = connection(server.origin, stream, stored)
                var history = List(16) { user("history-$it", "Synthetic history $it " + "x".repeat(4096)) }
                repeat(3) { index ->
                    val request = request(history)
                    val result = runtime(connection, state).call(request)
                    assertEquals(AiStepOutcome.COMPLETE, result.outcome)
                    history = history + persisted(result, "response-$index") + user("next-$index")
                }
                val sent = server.requests
                assertEquals(3, sent.size)
                sent.forEach { assertEquals(stream, it.body["stream"]?.jsonPrimitive?.booleanOrNull ?: false) }
                sent.forEach { assertEquals(stored, it.body.getValue("store").jsonPrimitive.boolean) }
                assertNull(sent[0].body["previous_response_id"])
                if (stored) {
                    assertEquals("opaque-parent-0", sent[1].body.getValue("previous_response_id").jsonPrimitive.content)
                    assertEquals("opaque-parent-1", sent[2].body.getValue("previous_response_id").jsonPrimitive.content)
                    assertEquals(1, sent[1].body.getValue("input").jsonArray.size)
                    assertTrue(sent[1].bytes < sent[0].bytes / 20)
                } else {
                    assertTrue(sent.all { it.body["previous_response_id"] == null })
                    assertTrue(sent[1].bytes > sent[0].bytes)
                }
                sent.forEach { assertEquals("/api/llm/responses", it.target) }
            }
        }
    }

    @Test
    fun `tool result continues native reasoning with fresh instructions and tool permissions`() = runBlocking {
        for (stream in listOf(false, true)) {
            ResponsesServer { exchange, _, index ->
                val output = if (index == 0) """[
                    {"type":"reasoning","id":"rs-1","encrypted_content":"synthetic-opaque-state","summary":[]},
                    {"type":"message","id":"comment-1","role":"assistant","status":"completed","phase":"commentary","content":[{"type":"output_text","text":"Checking","annotations":[]}]},
                    {"type":"function_call","id":"fc-1","call_id":"call-1","name":"lookup","arguments":"{}","status":"completed"}
                ]""" else TEXT_OUTPUT
                reply(exchange, response("resp_tfyv1-synthetic-$index", output = output), stream)
            }.use { server ->
                val state = OpenAiResponsesContinuations()
                val connection = connection(server.origin, stream, true)
                val first = runtime(connection, state).call(request(listOf(user("one"))).copy(tools = listOf(tool)))
                assertEquals(AiStepOutcome.TOOL_CALLS, first.outcome)
                val resultMessage = user("tool-result").copy(content = listOf(ContentItem.ToolResult(
                    toolUseId = ContentItem.ToolCall.Id("call-1"), toolName = "lookup", result = listOf(ContentItem.ToolResult.Data.Text("found")))))
                val next = request(listOf(user("one")) + persisted(first, "first") + resultMessage)
                    .copy(systemPrompts = listOf("Current readonly instructions"), tools = emptyList())
                assertEquals(AiStepOutcome.COMPLETE, runtime(connection, state).call(next).outcome)
                val body = server.requests.last().body
                assertEquals("resp_tfyv1-synthetic-0", body.getValue("previous_response_id").jsonPrimitive.content)
                val delta = body.getValue("input").jsonArray
                assertEquals(1, delta.size)
                assertEquals("function_call_output", delta.single().jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals("call-1", delta.single().jsonObject.getValue("call_id").jsonPrimitive.content)
                assertEquals("Current readonly instructions", body.getValue("instructions").jsonPrimitive.content)
                assertTrue(body["tools"] == null || body.getValue("tools").jsonArray.isEmpty())
                assertFalse(body.toString().contains("synthetic-opaque-state"))
            }
        }
    }

    @Test
    fun `history edits and changed covered runtime observations require a full request`() = runBlocking {
        for (changeObservation in listOf(false, true)) {
            ResponsesServer { exchange, _, index -> reply(exchange, response("parent-$index")) }.use { server ->
                val state = OpenAiResponsesContinuations()
                val connection = connection(server.origin)
                val original = user("one").copy(instructions = listOf(Conversation.Message.Instruction.RuntimeObservation("inventory one")))
                val first = runtime(connection, state).call(request(listOf(original)))
                val changed = if (changeObservation) original.copy(instructions = listOf(Conversation.Message.Instruction.RuntimeObservation("inventory two")))
                    else original.copy(content = listOf(ContentItem.UserMessage("edited text, same ID")))
                runtime(connection, state).call(request(listOf(changed) + persisted(first, "first") + user("next")))
                assertNull(server.requests.last().body["previous_response_id"])
                assertEquals(3, server.requests.last().body.getValue("input").jsonArray.size)
            }
        }
    }

    @Test
    fun `actor thread agent model endpoint and credential snapshots isolate continuation`() = runBlocking {
        ResponsesServer { exchange, _, index -> reply(exchange, response("parent-$index")) }.use { server ->
            val state = OpenAiResponsesContinuations()
            val connection = connection(server.origin)
            val firstRequest = request(listOf(user("one")))
            val first = runtime(connection, state).call(firstRequest)
            val next = request(listOf(user("one")) + persisted(first, "first") + user("next"))
            for (field in listOf(TOOL_CONTEXT_USER_ID, TOOL_CONTEXT_PROJECT_ID, TOOL_CONTEXT_THREAD_ID, TOOL_CONTEXT_AGENT_DEFINITION_ID, TOOL_CONTEXT_CONVERSATION_ID)) {
                val changed = next.copy(options = next.options.copy(toolContext = next.options.toolContext + (field to "other")))
                runtime(connection, state).call(changed)
                assertNull(server.requests.last().body["previous_response_id"], field)
            }
            runtime(connection, state, model = "other-model").call(next)
            assertNull(server.requests.last().body["previous_response_id"])
            runtime(connection.copy(baseUrl = server.origin + "/other"), state).call(next)
            assertNull(server.requests.last().body["previous_response_id"])
            runtime(connection.copy(apiKey = SecretRef.Inline("synthetic-other-token")), state).call(next)
            assertNull(server.requests.last().body["previous_response_id"])
            runtime(connection.copy(httpSettings = connection.httpSettings.copy(extraHeaders = mapOf("x-route" to "other"))), state).call(next)
            assertNull(server.requests.last().body["previous_response_id"])
            runtime(connection, state).call(next)
            assertEquals("parent-0", server.requests.last().body.getValue("previous_response_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `only explicit complete missing-parent rejection retries once with full context`() = runBlocking {
        for (stream in listOf(false, true)) ResponsesServer { exchange, _, index ->
            if (index == 1 && stream) reply(exchange, MISSING_PARENT_STREAM, isEventStream = true)
            else if (index == 1) reply(exchange, MISSING_PARENT, status = 404)
            else reply(exchange, response("parent-$index"), stream)
        }.use { server ->
            val state = OpenAiResponsesContinuations()
            val connection = connection(server.origin, stream)
            val first = runtime(connection, state).call(request(listOf(user("root"))))
            val next = request(listOf(user("root")) + persisted(first, "first") + user("next"))
            val second = runtime(connection, state).call(next)
            assertEquals(AiStepOutcome.COMPLETE, second.outcome)
            assertEquals(3, server.requests.size)
            assertNotNull(server.requests[1].body["previous_response_id"])
            assertNull(server.requests[2].body["previous_response_id"])
            assertEquals(3, server.requests[2].body.getValue("input").jsonArray.size)
            runtime(connection, state).call(request(next.messages + persisted(second, "second") + user("last")))
            assertEquals("parent-2", server.requests.last().body.getValue("previous_response_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `arbitrary errors do not trigger full retries and invalidate the cursor`() = runBlocking {
        for (status in listOf(400, 401, 403, 409, 429, 500)) {
            ResponsesServer { exchange, _, index ->
                if (index == 1) reply(exchange, """{"error":{"type":"invalid_request_error","code":"unrelated","message":"private-error-marker"}}""", status = status)
                else reply(exchange, response("parent-$index"))
            }.use { server ->
                val state = OpenAiResponsesContinuations()
                val connection = connection(server.origin).copy(httpSettings = settings().copy(maxAttempts = 1))
                val first = runtime(connection, state).call(request(listOf(user("root"))))
                val next = request(listOf(user("root")) + persisted(first, "first") + user("next"))
                val error = assertFailsWith<AiCallException> { runtime(connection, state).call(next) }
                assertEquals(2, server.requests.size)
                assertFalse(error.toString().contains("private-error-marker"))
                runtime(connection, state).call(next)
                assertNull(server.requests.last().body["previous_response_id"])
            }
        }
    }

    @Test
    fun `incomplete failed malformed and unterminated streams never publish executable tool calls`() = runBlocking {
        val outputs = listOf(
            terminal(response("bad", status = "incomplete", output = TOOL_OUTPUT), "response.incomplete"),
            terminal(response("bad", status = "failed", output = TOOL_OUTPUT), "response.failed"),
            "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"item_id\":\"m\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\",\"logprobs\":[]}\n\n",
            "event: error\ndata: {\"type\":\"error\",\"sequence_number\":1,\"code\":\"previous_response_not_found\",\"message\":\"private-stream-marker\",\"param\":\"previous_response_id\"}\n\n",
            terminal(response("bad", output = """[{"type":"function_call","id":"fc","call_id":"call-1","name":"lookup","arguments":"not json"}]""")),
        )
        for ((index, body) in outputs.withIndex()) ResponsesServer { exchange, _, _ -> reply(exchange, body, isEventStream = true) }.use { server ->
            val result = runCatching { runtime(connection(server.origin, true), OpenAiResponsesContinuations()).call(request(listOf(user("root")))) }
            if (index < 2) {
                val response = result.getOrThrow()
                assertTrue(response.outcome.isFailure)
                assertTrue(response.toolCalls.isEmpty())
            } else {
                assertIs<AiCallException>(result.exceptionOrNull())
                assertFalse(result.exceptionOrNull().toString().contains("private-stream-marker"))
            }
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `stream error preserves a structured parameter path`() {
        val body = """event: error
data: {"type":"error","sequence_number":1,"code":"subscription_sharing_unsupported_capability","message":"private-stream-marker","param":"input[0].tools"}

"""
        ResponsesServer { exchange, _, _ -> reply(exchange, body, isEventStream = true) }.use { server ->
            val connection = connection(server.origin, stream = true)
            OpenAiSdkClientFactory(Sources, httpClients).createInferenceClient(
                connection,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
            ).use { inference ->
                val params = OpenAiResponsesMessageMapper(
                    connectionId = connection.id.value,
                    modelConfigurationId = "configuration",
                    modelName = MODEL,
                ).toCreateParams(MODEL, false, request(listOf(user("root"))))

                val thrown = assertFails {
                    collectOpenAiResponsesStream(inference.client, params, java.time.Duration.ofSeconds(5))
                        .get(5, TimeUnit.SECONDS)
                }
                val error = assertNotNull(
                    generateSequence(thrown) { it.cause }
                        .filterIsInstance<OpenAiResponsesStreamException>()
                        .firstOrNull(),
                )
                assertEquals("subscription_sharing_unsupported_capability", error.code)
                assertEquals("input[0].tools", error.parameter)
            }
        }
    }

    @Test
    fun `SIWC terminal failed response follows structured failure recovery`() = runBlocking {
        val cases = listOf(
            Triple("subscription_sharing_usage_limit_exceeded",
                AiCallFailure.Kind.RATE_LIMITED, AiCallFailure.Outcome.REJECTED),
            Triple("subscription_sharing_usage_unavailable",
                AiCallFailure.Kind.TRANSPORT, AiCallFailure.Outcome.UNKNOWN),
            Triple("subscription_sharing_invalid_user",
                AiCallFailure.Kind.AUTHENTICATION, AiCallFailure.Outcome.REJECTED),
        )
        for ((code, kind, outcome) in cases) {
            ResponsesServer { exchange, _, _ ->
                exchange.responseHeaders.set("x-request-id", "req_failed-1")
                val failed = """{
                    "id":"failed","object":"response","created_at":1,"status":"failed","model":"$MODEL","output":[],
                    "error":{"code":"$code","message":"private-stream-marker"}
                }"""
                reply(exchange, terminal(failed, "response.failed"), isEventStream = true)
            }.use { server ->
                val tokens = RecordingSiwcAccessTokens()
                val thrown = assertFailsWith<AiCallException> {
                    siwcRuntime(connection(server.origin, stream = true), OpenAiResponsesContinuations(), tokens)
                        .call(request(listOf(user("root"))))
                }
                val error = thrown.failure

                assertEquals(kind, error.kind, code)
                assertEquals(outcome, error.outcome, code)
                assertEquals(200, error.httpStatus, code)
                assertEquals(code, error.providerErrorCode)
                assertEquals("req_failed-1", error.providerRequestId)
                assertFalse(thrown.toString().contains("private-stream-marker"))
                if (code == "subscription_sharing_invalid_user") {
                    assertEquals(listOf(credentialFingerprint(TOKEN)), tokens.rejectedFingerprints)
                    assertEquals(listOf(200), tokens.rejections.map { it.status })
                } else {
                    assertTrue(tokens.rejections.isEmpty())
                }
            }
        }
    }

    @Test
    fun `SSE is consumed progressively but a partial tool call is not returned before completion`() = runBlocking {
        val partialSent = CountDownLatch(1)
        val finish = CountDownLatch(1)
        ResponsesServer { exchange, _, _ ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val partial = "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"sequence_number\":0,\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc\",\"call_id\":\"call-1\",\"name\":\"lookup\",\"arguments\":\"\",\"status\":\"in_progress\"}}\n\n"
            exchange.responseBody.write(partial.toByteArray()); exchange.responseBody.flush()
            partialSent.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            exchange.responseBody.write(terminal(response("complete", output = TOOL_OUTPUT)).toByteArray())
        }.use { server ->
            val call = async(Dispatchers.Default) { runtime(connection(server.origin, true), OpenAiResponsesContinuations()).call(request(listOf(user("root")))) }
            try {
                await(partialSent)
                delay(100)
                assertTrue(call.isActive)
                finish.countDown()
                val result = call.await()
                assertEquals(AiStepOutcome.TOOL_CALLS, result.outcome)
                assertEquals(1, result.toolCalls.size)
            } finally { finish.countDown(); call.cancelAndJoin() }
        }
    }

    @Test
    fun `cancellation and deadline close the native SSE socket before and after headers`() = runBlocking {
        for (headers in listOf(false, true)) for (expire in listOf(false, true)) StalledServer(headers,
            contentType = "text/event-stream", partialContent = ": heartbeat\n\n").use { server ->
            val call = async(Dispatchers.Default) {
                runCatching { runtime(connection(server.origin, true), OpenAiResponsesContinuations()).call(request(listOf(user("root")))
                    .let { if (expire) it.copy(options = it.options.copy(executionDeadline = Clock.System.now() + 700.milliseconds)) else it }) }
            }
            try {
                await(server.started)
                if (expire) {
                    val error = call.await().exceptionOrNull()
                    assertIs<AiCallException>(error)
                    assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, error.failure.kind)
                } else withTimeout(2000) { call.cancelAndJoin() }
                assertEquals(-1, withContext(Dispatchers.IO) { server.peerClosed.get(3, TimeUnit.SECONDS) })
                assertEquals(1, server.requests.get())
            } finally { call.cancelAndJoin() }
        }
    }

    @Test
    fun `missing-parent recovery retains the original deadline`() = runBlocking {
        ResponsesServer { exchange, _, index ->
            if (index > 0) Thread.sleep(900)
            if (index == 1) reply(exchange, MISSING_PARENT, status = 400) else reply(exchange, response("parent-$index"))
        }.use { server ->
            val state = OpenAiResponsesContinuations()
            val connection = connection(server.origin)
            val first = runtime(connection, state).call(request(listOf(user("root"))))
            val next = request(listOf(user("root")) + persisted(first, "first") + user("next"))
            val error = assertFailsWith<AiCallException> {
                runtime(connection, state).call(next.copy(options = next.options.copy(executionDeadline = Clock.System.now() + 1500.milliseconds)))
            }
            assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, error.failure.kind)
            assertEquals(3, server.requests.size)
        }
    }

    @Test
    fun `helper calls remain stateless and counting uses the full effective context`() = runBlocking {
        ResponsesServer { exchange, sent, index ->
            if (sent.target.endsWith("input_tokens")) reply(exchange, """{"object":"response.input_tokens","input_tokens":123}""")
            else reply(exchange, response("parent-$index"))
        }.use { server ->
            val state = OpenAiResponsesContinuations()
            val connection = connection(server.origin)
            val first = runtime(connection, state).call(request(listOf(user("root"))))
            val next = request(listOf(user("root")) + persisted(first, "first") + user("next"))
            val inspected = runtime(connection, state).inspectContext(next, true)
            assertEquals(123, inspected.inputTokens)
            val count = server.requests.last().body
            assertEquals(3, count.getValue("input").jsonArray.size)
            assertNull(count["previous_response_id"])
            assertNull(count["store"])
            for (purpose in listOf(AI_CONTEXT_QUERY_PURPOSE, AI_ISOLATED_CALL_PURPOSE, "MEMORY_HELPER")) {
                runtime(connection, state).call(next.copy(options = next.options.copy(usagePurpose = purpose)))
                assertFalse(server.requests.last().body.getValue("store").jsonPrimitive.boolean)
                assertNull(server.requests.last().body["previous_response_id"])
            }
            runtime(connection, state).call(next)
            assertEquals("parent-0", server.requests.last().body.getValue("previous_response_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `terminal SSE response completes without waiting for EOF`() = runBlocking {
        val finish = CountDownLatch(1)
        ResponsesServer { exchange, _, _ ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(terminal(response("done")).toByteArray())
            exchange.responseBody.flush()
            check(finish.await(5, TimeUnit.SECONDS))
        }.use { server ->
            try {
                val result = withTimeout(3000) { runtime(connection(server.origin, true), OpenAiResponsesContinuations()).call(request(listOf(user("one")))) }
                assertEquals(AiStepOutcome.COMPLETE, result.outcome)
            } finally { finish.countDown() }
        }
    }

    @Test
    fun `completed output items survive an empty terminal response output`() = runBlocking {
        val item = Json.parseToJsonElement(TEXT_OUTPUT).jsonArray.single()
        val itemDone = "event: response.output_item.done\ndata: " + buildJsonObject {
            put("type", "response.output_item.done")
            put("sequence_number", 1)
            put("output_index", 0)
            put("item", item)
        } + "\n\n"
        val completed = terminal(response("done", output = "[]"))
        ResponsesServer { exchange, _, _ ->
            reply(exchange, itemDone + completed, isEventStream = true)
        }.use { server ->
            val result = runtime(connection(server.origin, stream = true), OpenAiResponsesContinuations())
                .call(request(listOf(user("one"))))
            val text = result.messages.flatMap { it.content }
                .filterIsInstance<ContentItem.AssistantMessage>()
                .single()
                .structured.fullText
            assertEquals("OK", text)
        }
    }

    @Test
    fun `missing-parent recovery shares attempt cap and one credential snapshot`() = runBlocking {
        for (limit in listOf(1, 2, 3)) ResponsesServer { exchange, _, index ->
            if (index == 1) reply(exchange, MISSING_PARENT, status = 400) else reply(exchange, response("parent-$index"))
        }.use { server ->
            val connection = connection(server.origin).copy(httpSettings = settings().copy(maxAttempts = limit))
            val state = OpenAiResponsesContinuations()
            val first = runtime(connection, state).call(request(listOf(user("one"))))
            val next = request(listOf(user("one")) + persisted(first, "first") + user("next"))
            if (limit == 1) {
                val failure = assertFailsWith<AiCallException> { runtime(connection, state).call(next) }.failure
                assertEquals(1, failure.attempts)
                assertEquals(2, server.requests.size)
            } else {
                assertEquals(AiStepOutcome.COMPLETE, runtime(connection, state).call(next).outcome)
                assertEquals(3, server.requests.size)
            }
        }
        val token = Files.createTempFile("grz-response-credential-", ".token")
        try {
            Files.writeString(token, TOKEN)
            ResponsesServer { exchange, _, index ->
                if (index == 1) { Files.writeString(token, "rotated-synthetic-token"); reply(exchange, MISSING_PARENT, status = 400) }
                else reply(exchange, response("parent-$index"))
            }.use { server ->
                val state = OpenAiResponsesContinuations()
                val connection = connection(server.origin).copy(apiKey = SecretRef.File(token.toString()))
                val first = runtime(connection, state).call(request(listOf(user("one"))))
                val next = request(listOf(user("one")) + persisted(first, "first") + user("next"))
                val second = runtime(connection, state).call(next)
                assertTrue(server.requests.all { it.headers["authorization"] == listOf("Bearer $TOKEN") })
                runtime(connection, state).call(request(next.messages + persisted(second, "second") + user("last")))
                assertEquals(listOf("Bearer rotated-synthetic-token"), server.requests.last().headers["authorization"])
                assertNull(server.requests.last().body["previous_response_id"])
            }
        } finally { Files.deleteIfExists(token) }
    }

    @Test
    fun `TPM receives delta wire full accounting and bounded final Response feedback for SSE`() = runBlocking {
        for (stream in listOf(false, true)) ResponsesServer { exchange, _, index ->
            val native = Json.parseToJsonElement(response("parent-$index")).jsonObject
            val usage = Json.parseToJsonElement("""{"input_tokens":42,"output_tokens":3,"total_tokens":45,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}""")
            reply(exchange, JsonObject(native + ("usage" to usage)).toString(), stream)
        }.use { server ->
            val accounting = AccountingAdmission()
            val state = OpenAiResponsesContinuations()
            val connection = connection(server.origin, stream)
            val first = runtime(connection, state, admission = accounting, tpm = quotaPolicy).call(request(listOf(user("one"))))
            val next = request(listOf(user("one")) + persisted(first, "first") + user("next"))
            runtime(connection, state, admission = accounting, tpm = quotaPolicy).call(next)
            assertEquals<List<JsonElement>>(server.requests.map { it.body }, accounting.wire)
            assertNull(accounting.expanded.first())
            val expanded = accounting.expanded.last()!!.jsonObject
            assertEquals(3, expanded.getValue("input").jsonArray.size)
            assertEquals(1, accounting.wire.last().jsonObject.getValue("input").jsonArray.size)
            assertNull(expanded["previous_response_id"])
            assertTrue(accounting.feedback.all { !it.bodyUnavailable && it.body!!.jsonObject["usage"] != null })
            assertEquals(0, accounting.active)
        }
    }

    @Test
    fun `stale missing-parent recovery cannot replace a concurrently completed cursor`() = runBlocking {
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        ResponsesServer { exchange, _, index ->
            when (index) {
                0 -> reply(exchange, response("parent-X"))
                1 -> { enteredA.countDown(); check(releaseA.await(5, TimeUnit.SECONDS)); reply(exchange, MISSING_PARENT, status = 400) }
                2 -> reply(exchange, response("parent-Y"))
                3 -> reply(exchange, response("stale-A-full"))
                else -> reply(exchange, response("final"))
            }
        }.use { server ->
            val state = OpenAiResponsesContinuations()
            val connection = connection(server.origin)
            val root = listOf(user("root"))
            val first = runtime(connection, state).call(request(root))
            val prefix = root + persisted(first, "first")
            val a = async(Dispatchers.Default) { runtime(connection, state).call(request(prefix + user("racing-A"))) }
            try {
                await(enteredA)
                val b = runtime(connection, state).call(request(prefix + user("racing-B")))
                releaseA.countDown()
                assertEquals(AiStepOutcome.COMPLETE, a.await().outcome)
                runtime(connection, state).call(request(prefix + user("racing-B") + persisted(b, "B") + user("follow-B")))
                assertEquals("parent-Y", server.requests.last().body.getValue("previous_response_id").jsonPrimitive.content)
                assertEquals(1, server.requests.last().body.getValue("input").jsonArray.size)
            } finally { releaseA.countDown(); a.cancelAndJoin() }
        }
    }

    @Test
    fun `wire byte metrics are debug-only and match actual JSON without leaking private data`() = runBlocking {
        val logger = org.slf4j.LoggerFactory.getLogger(OpenAiResponsesSdkRuntime::class.java) as ch.qos.logback.classic.Logger
        val originalLevel = logger.level
        val rows = CopyOnWriteArrayList<Pair<String, String>>()
        val appender = object : ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(event: ch.qos.logback.classic.spi.ILoggingEvent) {
                rows += event.threadName to event.formattedMessage
            }
        }.apply { context = logger.loggerContext; start() }
        logger.addAppender(appender)
        try {
            for (level in listOf(ch.qos.logback.classic.Level.INFO, ch.qos.logback.classic.Level.DEBUG)) for (stream in listOf(false, true)) {
                logger.level = level
                rows.clear()
                ResponsesServer { exchange, _, index ->
                    reply(exchange, response("opaque-private-parent-$index", output = TEXT_OUTPUT.replace("OK", "private-output-marker")), stream)
                }.use { server ->
                    val state = OpenAiResponsesContinuations()
                    val connection = connection(server.origin, stream)
                    val root = listOf(user("root", "private-input-marker " + "x".repeat(65_536)))
                    val first = runtime(connection, state).call(request(root))
                    val next = request(root + persisted(first, "first") + user("new"))
                    runtime(connection, state).call(next)
                    runtime(connection.copy(responsesStoredContinuationEnabled = false), state).call(next)
                    val wire = rows.map { it.second }.filter { it.startsWith("RESPONSES_WIRE ") }
                    assertEquals(3, rows.count { it.second.startsWith("RESPONSES_HTTP ") })
                    if (level == ch.qos.logback.classic.Level.INFO) {
                        assertTrue(wire.isEmpty(), "Payload byte diagnostics must not run at INFO")
                    } else {
                        assertEquals(3, wire.size)
                        fun number(row: String, key: String) = Regex("(?:^| )$key=([0-9]+)").find(row)!!.groupValues[1].toInt()
                        assertEquals(server.requests[0].bytes, number(wire[0], "serialized_sent_bytes"))
                        assertEquals(server.requests[1].bytes, number(wire[1], "serialized_sent_bytes"))
                        // The actual full-reference call differs only by store:false (one extra byte).
                        assertEquals(server.requests[2].bytes - 1, number(wire[1], "serialized_full_bytes"))
                    }
                    for ((thread, message) in rows) {
                        assertEquals("responses-metrics", thread)
                        for (privateValue in listOf("http://", "https://", "127.0.0.1", TOKEN, "opaque-private-parent", "private-input-marker", "private-output-marker")) {
                            assertFalse((thread + message).contains(privateValue), "Private data in a Responses metric")
                        }
                    }
                }
            }
        } finally { logger.level = originalLevel; logger.detachAppender(appender); appender.stop() }
    }

    private class AccountingAdmission : AiCallAdmission() {
        val wire = mutableListOf<JsonElement>()
        val expanded = mutableListOf<JsonElement?>()
        val feedback = mutableListOf<AiHttpQuotaFeedback>()
        var active = 0
        override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease = error("HTTP quota required")
        override suspend fun acquireHttp(operation: AiAdmissionOperation, request: JsonElement, accountingRequest: JsonElement?): AiAdmissionLease {
            assertEquals(0, active++)
            wire += request; expanded += accountingRequest
            return AiAdmissionLease(operation.copy(tpm = AiTpmReservation(AiTpmGroup.Id("test"), 100, quotaPolicy.feedback)),
                AiConcurrencyGroup.DEFAULT_ID, Clock.System.now())
        }
        override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) = error("HTTP feedback required")
        override suspend fun finishHttp(lease: AiAdmissionLease, outcome: AiAdmissionOutcome, feedback: AiHttpQuotaFeedback?) {
            assertEquals(1, active--)
            this.feedback += assertNotNull(feedback)
        }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }

    private fun connection(origin: String, stream: Boolean = false, stored: Boolean = true) = AiConnection.OpenAiApi(
        AiConnection.Id("responses"), "Synthetic", enabled = true, baseUrl = "$origin/api/llm", apiKey = SecretRef.Inline(TOKEN),
        responsesStreamingEnabled = stream, responsesStoredContinuationEnabled = stored, webSearchEnabled = false, httpSettings = settings())

    private fun runtime(connection: AiConnection.OpenAiApi, state: OpenAiResponsesContinuations, model: String = MODEL,
        admission: AiCallAdmission = TestAiAdmission(), tpm: AiTpmPolicy? = null): AiRuntime {
        val config = AiModelConfiguration(AiModelConfiguration.Id("configuration-$model"), connection.id, model, "Synthetic", tpm = tpm)
        val spec = AiModelSpec(model, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(128_000, 8192)))
        return RoutingAiRuntimeProvider(listOf(OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources, httpClients), admission, state)))
            .getRuntime(ResolvedAiRuntime(connection, config, spec), null)
    }

    private fun siwcRuntime(
        connection: AiConnection.OpenAiApi,
        state: OpenAiResponsesContinuations,
        accessTokens: OpenAiSiwcAccessTokenProvider,
    ): AiRuntime {
        val configuration = AiModelConfiguration(
            AiModelConfiguration.Id("siwc-configuration"),
            connection.id,
            MODEL,
            "Synthetic SIWC",
        )
        val executor = SdkHttpCallExecutor(
            connection = connection,
            timeoutSeconds = configuration.defaultParameters.timeoutSeconds,
            maxAttempts = 1,
            admission = TestAiAdmission(),
            modelConfiguration = configuration,
            failureMapper = { session, failure, cause, deadline ->
                mapOpenAiSiwcResourceFailure(accessTokens, session, failure, cause, deadline)
            },
        ) { deadline -> OpenAiSdkClientFactory(Sources, httpClients).createInferenceClient(connection, deadline) }
        return OpenAiResponsesSdkRuntime(
            connectionId = connection.id.value,
            modelConfigurationId = configuration.id.value,
            modelName = MODEL,
            webSearchEnabled = false,
            executor = executor,
            provider = OpenAiResponsesProvider(
                providerScope = AiConnection.Kind.OPENAI_SIWC.name,
                webSearchTool = ProviderNativeTool.OPENAI_SIWC_WEB_SEARCH,
                functionNamespace = "functions",
                supportsNativeCompaction = false,
                supportsDetachedNativeCompaction = false,
                supportsProviderTokenCount = false,
                streaming = true,
                terminalFailuresAsErrors = true,
                requestFeatures = OpenAiResponsesRequestFeatures(maxOutputTokens = false),
            ),
            continuations = state,
            quotaEnabled = false,
        )
    }

    private class RecordingSiwcAccessTokens : OpenAiSiwcAccessTokenProvider {
        val rejectedFingerprints = mutableListOf<String>()
        val rejections = mutableListOf<OpenAiSiwcResourceError>()

        override suspend fun validAccessToken(deadlineNanos: Long): String = error("The test supplies the SDK token directly")

        override fun resourceRejectedAccessToken(
            credentialFingerprint: String,
            error: OpenAiSiwcResourceError,
            deadlineNanos: Long,
        ) {
            rejectedFingerprints += credentialFingerprint
            rejections += error
        }
    }

    private fun request(messages: List<Conversation.Message>) = AiRuntimeRequest(listOf("Current instructions"), messages,
        options = AiRuntimeOptions(maxOutputTokens = 128, usagePurpose = "CONVERSATION", toolContext = mapOf(
            TOOL_CONTEXT_USER_ID to "actor", TOOL_CONTEXT_PROJECT_ID to "project", TOOL_CONTEXT_CONVERSATION_ID to "conversation",
            TOOL_CONTEXT_THREAD_ID to "branch", TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent")))

    private fun user(id: String, text: String = id) = Conversation.Message(Conversation.Message.Id(id), Conversation.Id("conversation"),
        role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(text)), createdAt = Instant.fromEpochMilliseconds(0))

    private fun persisted(response: AiRuntimeResponse, id: String): List<Conversation.Message> = response.messages.mapIndexed { index, message ->
        fun value(value: Any?): JsonElement = when (value) {
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            null -> JsonNull
            else -> error("Unexpected synthetic metadata type")
        }
        user("$id-$index").copy(role = Conversation.Message.Role.ASSISTANT, content = message.content,
            providerMetadata = JsonObject((response.providerMetadata + message.metadata).mapValues { value(it.value) }))
    }

    private object Sources : SettingsProvider {
        override val userProfile: UserProfile get() = error("Not used")
        override val userDeviceSettings: UserDeviceSettings get() = error("Not used")
        override val mode: AppMode get() = error("Not used")
        override val homeDirectory: String get() = error("Not used")
        override fun resolveSecret(secretRef: SecretRef?): String? = when (secretRef) {
            is SecretRef.Inline -> secretRef.value
            is SecretRef.File -> Files.readString(Path.of(secretRef.path)).trim()
            else -> null
        }
    }

    private companion object {
        val quotaPolicy = AiTpmPolicy(AiTpmGroupSelector.Fixed(AiTpmGroup.Id("test")), AiQuotaCalculator.BuiltIn(),
            AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Body("/usage/input_tokens")))
        const val TOKEN = "synthetic-responses-token"
        const val MODEL = "account/exact-model"
        const val TEXT_OUTPUT = """[{"type":"message","id":"m-1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK","annotations":[]}]}]"""
        const val TOOL_OUTPUT = """[{"type":"function_call","id":"fc-1","call_id":"call-1","name":"lookup","arguments":"{}","status":"completed"}]"""
        const val MISSING_PARENT = """{"error":{"type":"invalid_request_error","code":"previous_response_not_found","param":"previous_response_id","message":"private-missing-parent-marker"}}"""
        const val MISSING_PARENT_STREAM = """event: error
data: {"type":"error","sequence_number":1,"code":"previous_response_not_found","message":"private-missing-parent-marker","param":"previous_response_id"}

"""
        fun settings() = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"))
        fun response(id: String, status: String = "completed", output: String = TEXT_OUTPUT): String =
            """{"id":"$id","object":"response","created_at":1,"status":"$status","model":"$MODEL","output":$output}"""
        fun terminal(response: String, event: String = "response.completed"): String {
            val compact = Json.parseToJsonElement(response).toString()
            return "event: $event\ndata: {\"type\":\"$event\",\"sequence_number\":1,\"response\":$compact}\n\n"
        }
        fun reply(exchange: HttpExchange, body: String, stream: Boolean = false, status: Int = 200, isEventStream: Boolean = false) {
            val events = stream || isEventStream
            val bytes = (if (stream) terminal(body) else body).toByteArray()
            exchange.responseHeaders.set("Content-Type", if (events) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
        val tool = object : AiToolCallback {
            override val metadata = ServerToolMetadata
            override val definition = AiToolDefinition("lookup", "Synthetic tool", """{"type":"object","properties":{}}""")
            override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Transport test must not execute tools")
        }
    }
}

private class ResponsesServer(private val handle: (HttpExchange, Request, Int) -> Unit) : AutoCloseable {
    data class Request(val target: String, val body: JsonObject, val headers: Map<String, List<String>>, val bytes: Int)
    val requests = CopyOnWriteArrayList<Request>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@ResponsesServer.executor
        createContext("/") { exchange ->
            try {
                val bytes = exchange.requestBody.readBytes()
                val request = Request(exchange.requestURI.path, Json.parseToJsonElement(bytes.decodeToString()).jsonObject,
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }, bytes.size)
                requests += request
                handle(exchange, request, counter.getAndIncrement())
            } catch (_: IOException) {
                // Deadline/cancellation fixtures deliberately close the client before a delayed write.
            } catch (error: Throwable) { errors += error }
            finally { exchange.close() }
        }
        start()
    }
    val origin get() = "http://127.0.0.1:${server.address.port}"
    override fun close() {
        server.stop(0)
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            error("Responses test server did not stop")
        }
        check(errors.isEmpty()) { "Responses fixture handler failed: ${errors.firstOrNull()}" }
    }
}
