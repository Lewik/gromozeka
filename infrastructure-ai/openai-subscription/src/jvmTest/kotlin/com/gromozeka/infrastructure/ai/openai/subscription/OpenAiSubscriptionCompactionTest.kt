package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class OpenAiSubscriptionCompactionTest {
    @Test
    fun compactsBeforeInferenceAndReplaysThePersistedCheckpointAfterRestart() = runBlocking {
        Fixture().use { fixture ->
            val request = request()
            val mapped = fixture.mapper.toRequest(request, profile, "conversation", connectionId = "connection")
            val response = requireNotNull(fixture.compact(request, mapped))
            assertEquals(AiStepOutcome.CONTINUE, response.outcome)
            assertTrue(response.toolCalls.isEmpty())
            assertEquals(1, fixture.requests.size)
            val sent = fixture.requests.single()
            assertEquals(JsonPrimitive("compaction_trigger"), sent.getValue("input").jsonArray.last().jsonObject["type"])
            assertEquals(JsonPrimitive("additional_tools"), sent.getValue("input").jsonArray.first().jsonObject["type"])
            assertNull(sent["context_management"])
            assertNull(sent["text"])
            assertEquals(9_000, response.usage?.totalInputTokens)
            assertTrue(requireNotNull(response.contextUsage).inputTokens < 1_000)
            val checkpoint = persisted(response).single()
            val restored = Json.decodeFromString(Conversation.Message.serializer(),
                Json.encodeToString(Conversation.Message.serializer(), checkpoint))
            val resumed = request.copy(messages = request.messages + restored)
            val freshMapper = OpenAiSubscriptionRequestMapper()
            val replay = freshMapper.toRequest(resumed, profile, "conversation", connectionId = "connection")
            assertEquals(listOf("message", "compaction"), replay.input.map { it.getValue("type").toString().trim('"') })
            assertEquals(JsonPrimitive("Continue the implementation."), replay.input.first().getValue("content")
                .jsonArray.first().jsonObject.getValue("text"))
            assertEquals(JsonPrimitive("opaque-checkpoint"), replay.input.last()["encrypted_content"])
            Fixture().use { restarted ->
                assertNull(restarted.compact(resumed, replay))
                assertNull(restarted.compact(resumed.copy(options = AiRuntimeOptions(autoCompactionThresholdTokens = 1)), replay))
                assertTrue(restarted.requests.isEmpty())
            }
        }
    }

    @Test
    fun disabledCompactionAndOrdinaryResponsesDoNotSendLiteTriggers() = runBlocking {
        Fixture().use { fixture ->
            val disabled = request().copy(options = AiRuntimeOptions())
            val mapped = fixture.mapper.toRequest(disabled, profile, "conversation")
            assertNull(fixture.compact(disabled, mapped))
            assertNull(fixture.compact(request(), mapped, profile.copy(useResponsesLite = false)))
            assertNull(fixture.compact(request().copy(messages = listOf(user("small", "Hello"))),
                mapped.copy(input = listOf(userItem("Hello")))))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun usesPersistedMeasuredUsageWhenTheEstimateIsTooSmallAndIgnoresOtherScopes() {
        Fixture().use { fixture ->
            val mapped = OpenAiSubscriptionResponsesRequest(model = profile.slug, input = listOf(userItem("Hello")))
            val measured = user("measured", "Hello").copy(providerMetadata = buildJsonObject {
                put("provider", AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
                put("connectionId", "connection")
                put("model", profile.slug)
                put(OpenAiSubscriptionCompaction.CONTEXT_TOKENS_KEY, 900_000)
                put(OpenAiSubscriptionCompaction.CONTEXT_ESTIMATE_KEY, 2)
            })
            assertTrue(fixture.compaction.estimateContextTokens(listOf(measured), mapped, "connection") >= 900_000)
            assertTrue(fixture.compaction.estimateContextTokens(listOf(measured), mapped, "other") < 100)
            assertTrue(fixture.compaction.estimateContextTokens(listOf(measured), mapped.copy(model = "other"), "connection") < 100)
            val changedPrefix = mapped.copy(instructions = "new instructions ".repeat(100))
            assertTrue(fixture.compaction.estimateContextTokens(listOf(measured), changedPrefix, "connection") > 900_000)
        }
    }

    @Test
    fun incompleteOrMalformedCompactionNeverCreatesACheckpoint() = runBlocking<Unit> {
        for (output in listOf(
            """{"type":"message","role":"assistant","content":"Not a checkpoint"}""",
            """{"type":"compaction","encrypted_content":""}""",
        )) {
            Fixture(output = output).use { fixture ->
                val request = request()
                val mapped = fixture.mapper.toRequest(request, profile, "conversation")
                assertFailsWith<IllegalStateException> { fixture.compact(request, mapped) }
            }
        }
        Fixture(status = "incomplete").use { fixture ->
            val request = request()
            assertFailsWith<IllegalStateException> {
                fixture.compact(request, fixture.mapper.toRequest(request, profile, "conversation"))
            }
        }
        for (extra in listOf(
            """{"type":"compaction","encrypted_content":"second-checkpoint"}""",
            """{"type":"function_call","call_id":"call","name":"delete_file","arguments":"{}"}""",
        )) {
            Fixture(extraOutput = extra).use { fixture ->
                val request = request()
                assertFailsWith<IllegalStateException> {
                    fixture.compact(request, fixture.mapper.toRequest(request, profile, "conversation"))
                }
            }
        }
    }

    @Test
    fun retentionIsBoundedAndDoesNotReplayToolCallsAsNewActions() {
        Fixture().use { fixture ->
            val input = listOf(userItem("old".repeat(1000)), userItem("recent"),
                Json.parseToJsonElement("""{"type":"function_call","name":"delete_file","call_id":"call","arguments":"{}"}""").jsonObject,
                Json.parseToJsonElement("""{"type":"function_call_output","call_id":"call","output":"done"}""").jsonObject)
            assertEquals(listOf(userItem("recent")), fixture.compaction.retainUserInput(input, 20))
            assertTrue(fixture.compaction.retainUserInput(input, 0).isEmpty())
            val image = Json.parseToJsonElement("""{"type":"input_image","image_url":"data:image/png;base64,${"a".repeat(100_000)}"}""").jsonObject
            val estimate = fixture.compaction.estimateRequestTokens(OpenAiSubscriptionResponsesRequest(
                model = profile.slug, input = listOf(image),
            ))
            assertEquals(10_000L, estimate)
            val userWithImage = JsonObject(userItem("Describe the screenshot") + ("content" to JsonArray(
                userItem("Describe the screenshot").getValue("content").jsonArray + image,
            )))
            assertEquals(listOf(userItem("Describe the screenshot")), fixture.compaction.retainUserInput(listOf(userWithImage), 100))
        }
    }

    @Test
    fun recordsInputAndOutputUsageForTheNextModelStep() {
        Fixture().use { fixture ->
            val mapped = OpenAiSubscriptionResponsesRequest(model = profile.slug, input = listOf(userItem("Hello")))
            val parsed = OpenAiSubscriptionParsedResponse(
                outputItems = listOf(Json.parseToJsonElement("""{"type":"message","role":"assistant","content":"Hello back"}""").jsonObject),
                completed = OpenAiSubscriptionCompletedResponse("response", "completed", OpenAiSubscriptionUsage(inputTokens = 1000, outputTokens = 100)),
            )
            val response = fixture.responseMapper.toRuntimeResponse(parsed.outputItems, parsed.completed,
                "conversation", "connection", "configuration", profile.slug, AiModelConfiguration.AssistantResponseFormat.TEXT)
            val recorded = fixture.compaction.recordContextUsage(mapped, parsed, response, AiModelConfiguration.AssistantResponseFormat.TEXT)
            assertEquals(1100L, recorded.providerMetadata[OpenAiSubscriptionCompaction.CONTEXT_TOKENS_KEY])
            assertTrue(fixture.compaction.estimateContextTokens(persisted(recorded), mapped, "connection") >= 1100)
        }
    }

    private fun request() = AiRuntimeRequest(
        systemPrompts = listOf("Preserve the user's task."),
        messages = listOf(user("old", "Long previous work. ".repeat(2000)), user("new", "Continue the implementation.")),
        options = AiRuntimeOptions(autoCompactionThresholdTokens = 1000),
    )

    private fun user(id: String, text: String) = Conversation.Message(
        id = Conversation.Message.Id(id), conversationId = Conversation.Id("conversation"),
        role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(text)),
        createdAt = Instant.parse("2026-09-13T00:00:00Z"),
    )

    private fun userItem(text: String) = buildJsonObject {
        put("type", "message")
        put("role", "user")
        put("content", JsonArray(listOf(buildJsonObject { put("type", "input_text"); put("text", text) })))
    }

    private fun persisted(response: AiRuntimeResponse) = response.messages.mapIndexed { index, message ->
        user("assistant-$index", "").copy(role = Conversation.Message.Role.ASSISTANT, content = message.content,
            providerMetadata = JsonObject((response.providerMetadata + message.metadata).mapValues { (_, value) ->
                value as? JsonElement ?: when (value) {
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            }))
    }

    private class Fixture(
        output: String = """{"type":"compaction","encrypted_content":"opaque-checkpoint"}""",
        status: String = "completed",
        extraOutput: String? = null,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        private val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/responses") { exchange ->
                if (exchange.requestMethod == "GET") {
                    exchange.sendResponseHeaders(426, -1)
                    exchange.close()
                } else {
                    requests += Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    val body = ("data: {\"type\":\"response.output_item.done\",\"item\":$output}\n\n" +
                        (extraOutput?.let { "data: {\"type\":\"response.output_item.done\",\"item\":$it}\n\n" } ?: "") +
                        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"response\",\"status\":\"$status\",\"output\":[],\"usage\":{\"input_tokens\":9000,\"output_tokens\":100}}}\n\n").toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            start()
        }
        val mapper = OpenAiSubscriptionRequestMapper()
        val responseMapper = OpenAiSubscriptionResponseMapper()
        val client = OpenAiSubscriptionResponsesClient(responseMapper, mapper, "http://127.0.0.1:${server.address.port}",
            "test", 1000, 5000, 1000, 5000)
        val compaction = OpenAiSubscriptionCompaction(client, mapper, responseMapper)

        suspend fun compact(request: AiRuntimeRequest, mapped: OpenAiSubscriptionResponsesRequest,
            modelProfile: OpenAiSubscriptionModelProfile = profile) = compaction.executeIfNeeded(request, mapped,
            modelProfile, OpenAiSubscriptionSession("access", "refresh", null, "account", Long.MAX_VALUE),
            "conversation", "connection", "configuration")

        override fun close() = server.stop(0)
    }

    companion object {
        private val profile = OpenAiSubscriptionModelProfile("gpt-6-astra", true, true, listOf("low"), true, "low", false)
    }
}
