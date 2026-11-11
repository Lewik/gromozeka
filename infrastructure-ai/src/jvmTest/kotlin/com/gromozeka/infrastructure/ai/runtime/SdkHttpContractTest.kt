package com.gromozeka.infrastructure.ai.runtime

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.anthropic.AnthropicSdkMessageMapper
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesMessageMapper
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkMessageMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.net.Proxy
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SdkHttpContractTest {
    @Test
    fun chatCompletionsPreservesEndpointHeadersAndPayload() {
        for (suffix in listOf("", "/")) {
            SdkHttpTestServer(chatResponse()).use { server ->
                val client = OpenAIOkHttpClient.builder()
                    .baseUrl("${server.origin}/api/llm$suffix")
                    .apiKey(TEST_TOKEN)
                    .proxy(Proxy.NO_PROXY)
                    .timeout(Duration.ofSeconds(5))
                    .build()
                try {
                    val mapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, "http-test", MODEL)
                    val params = mapper.toCreateParams(MODEL, request())
                    val response = client.chat().completions().create(params)
                    assertAnswer(mapper.toRuntimeResponse(response, AiModelConfiguration.AssistantResponseFormat.TEXT, params))
                } finally {
                    client.close()
                }

                val body = assertSingleRequest(server, "/api/llm/chat/completions", "authorization", "Bearer $TEST_TOKEN")
                val messages = body.getValue("messages").jsonArray
                assertEquals(listOf("system", "user", "assistant", "user"), messages.roles())
                assertEquals(listOf(SYSTEM_PROMPT, "First question.", "Earlier answer.", "Next question."), messages.texts())
                assertEquals(256, body.getValue("max_completion_tokens").jsonPrimitive.int)
                val function = body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
                assertEquals("lookup", function.getValue("name").jsonPrimitive.content)
                assertEquals(toolSchema, function.getValue("parameters"))
                val format = body.getValue("response_format").jsonObject
                assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
                assertEquals(answerSchema, format.getValue("json_schema").jsonObject.getValue("schema"))
            }
        }
    }

    @Test
    fun responsesPreservesEndpointHeadersAndPayload() {
        for (suffix in listOf("", "/")) {
            SdkHttpTestServer(responsesResponse()).use { server ->
                val client = OpenAIOkHttpClient.builder()
                    .baseUrl("${server.origin}/api/llm$suffix")
                    .apiKey(TEST_TOKEN)
                    .proxy(Proxy.NO_PROXY)
                    .timeout(Duration.ofSeconds(5))
                    .build()
                try {
                    val mapper = OpenAiResponsesMessageMapper("http-test", "http-test-config", MODEL)
                    val response = client.responses().create(mapper.toCreateParams(MODEL, false, request()))
                    assertAnswer(mapper.toRuntimeResponse(response, AiModelConfiguration.AssistantResponseFormat.TEXT))
                } finally {
                    client.close()
                }

                val body = assertSingleRequest(server, "/api/llm/responses", "authorization", "Bearer $TEST_TOKEN")
                assertEquals(SYSTEM_PROMPT, body.getValue("instructions").jsonPrimitive.content)
                val input = body.getValue("input").jsonArray
                assertEquals(listOf("user", "assistant", "user"), input.roles())
                assertEquals(listOf("First question.", "Earlier answer.", "Next question."), input.texts())
                assertEquals(256, body.getValue("max_output_tokens").jsonPrimitive.int)
                assertEquals(JsonPrimitive(false), body.getValue("store"))
                val function = body.getValue("tools").jsonArray.single().jsonObject
                assertEquals("function", function.getValue("type").jsonPrimitive.content)
                assertEquals("lookup", function.getValue("name").jsonPrimitive.content)
                assertEquals(toolSchema, function.getValue("parameters"))
                val format = body.getValue("text").jsonObject.getValue("format").jsonObject
                assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
                assertEquals(answerSchema, format.getValue("schema"))
            }
        }
    }

    @Test
    fun anthropicMessagesPreservesEndpointCurrentApiKeyAuthAndPayload() {
        for (suffix in listOf("", "/")) {
            SdkHttpTestServer(anthropicResponse()).use { server ->
                val client = AnthropicOkHttpClient.builder()
                    .baseUrl("${server.origin}/api/llm$suffix")
                    .apiKey(TEST_TOKEN)
                    .maxRetries(0)
                    .proxy(Proxy.NO_PROXY)
                    .timeout(Duration.ofSeconds(5))
                    .build()
                try {
                    val mapper = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API, "http-test", MODEL)
                    val response = client.messages().create(mapper.toCreateParams(MODEL, request()))
                    assertAnswer(mapper.toRuntimeResponse(response, AiModelConfiguration.AssistantResponseFormat.TEXT))
                } finally {
                    client.close()
                }

                val body = assertSingleRequest(server, "/api/llm/v1/messages", "x-api-key", TEST_TOKEN)
                assertFalse("authorization" in server.requests.single().headers)
                assertTrue(server.requests.single().headers.getValue("anthropic-version").single().isNotBlank())
                assertEquals(SYSTEM_PROMPT, body.getValue("system").text())
                val messages = body.getValue("messages").jsonArray
                assertEquals(listOf("user", "assistant", "user"), messages.roles())
                assertEquals(listOf("First question.", "Earlier answer.", "Next question."), messages.texts())
                assertEquals(256, body.getValue("max_tokens").jsonPrimitive.int)
                val function = body.getValue("tools").jsonArray.single().jsonObject
                assertEquals("lookup", function.getValue("name").jsonPrimitive.content)
                assertEquals(toolSchema, function.getValue("input_schema"))
                val format = body.getValue("output_config").jsonObject.getValue("format").jsonObject
                assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
                assertEquals(answerSchema, format.getValue("schema"))
            }
        }
    }

    @Test
    fun syntheticCallIdsArePortableAcrossResponsesChatAndMessagesWithoutChangingModelIds() {
        val legacy = "cmd_" + "a".repeat(64) + "_status"
        val short = com.gromozeka.domain.model.syntheticToolCallId(legacy).value
        val realId = "call_provider_generated_original_id"
        for (syntheticId in listOf(legacy, short)) {
            val original = request().messages.first()
            fun pair(prefix: String, id: String, synthetic: Boolean): List<Conversation.Message> {
                val call = original.copy(id = Conversation.Message.Id("$prefix-call"), role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id(id),
                        Conversation.Message.ContentItem.ToolCall.Data("lookup", JsonObject(emptyMap())))),
                    providerMetadata = if (synthetic) JsonObject(mapOf("synthetic" to JsonPrimitive(true),
                        "syntheticKind" to JsonPrimitive("command_task_completion"))) else JsonObject(emptyMap()))
                return listOf(call, call.copy(id = Conversation.Message.Id("$prefix-result"), role = Conversation.Message.Role.USER,
                    content = listOf(Conversation.Message.ContentItem.ToolResult(Conversation.Message.ContentItem.ToolCall.Id(id), "lookup",
                        listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("done"))))))
            }
            val input = request().copy(messages = listOf(original) + pair("synthetic", syntheticId, true) + pair("model", realId, false),
                options = request().options.copy(toolContext = mapOf("threadId" to "branch")))
            SdkHttpTestServer(chatResponse(), responsesResponse(), anthropicResponse()).use { server ->
                val openai = OpenAIOkHttpClient.builder().baseUrl("${server.origin}/api/llm").apiKey(TEST_TOKEN)
                    .proxy(Proxy.NO_PROXY).maxRetries(0).timeout(Duration.ofSeconds(5)).build()
                try {
                    openai.chat().completions().create(OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, "http-test", MODEL).toCreateParams(MODEL, input))
                    openai.responses().create(OpenAiResponsesMessageMapper("http-test", "http-test-config", MODEL).toCreateParams(MODEL, false, input))
                } finally { openai.close() }
                val anthropic = AnthropicOkHttpClient.builder().baseUrl("${server.origin}/api/llm").apiKey(TEST_TOKEN)
                    .proxy(Proxy.NO_PROXY).maxRetries(0).timeout(Duration.ofSeconds(5)).build()
                try { anthropic.messages().create(AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API, "http-test", MODEL).toCreateParams(MODEL, input)) }
                finally { anthropic.close() }
                assertEquals(3, server.requests.size)
                for (recorded in server.requests) {
                    val calls = mutableListOf<String>()
                    val results = mutableListOf<String>()
                    fun collect(value: JsonElement) {
                        when (value) {
                            is JsonArray -> value.forEach(::collect)
                            is JsonObject -> {
                                val type = (value["type"] as? JsonPrimitive)?.content
                                when {
                                    type == "function_call" -> calls += value.getValue("call_id").jsonPrimitive.content
                                    type == "function_call_output" -> results += value.getValue("call_id").jsonPrimitive.content
                                    type == "tool_use" || type == "function" && "id" in value -> calls += value.getValue("id").jsonPrimitive.content
                                    type == "tool_result" -> results += value.getValue("tool_use_id").jsonPrimitive.content
                                    value["role"] == JsonPrimitive("tool") -> results += value.getValue("tool_call_id").jsonPrimitive.content
                                }
                                value.values.forEach(::collect)
                            }
                            else -> Unit
                        }
                    }
                    collect(Json.parseToJsonElement(recorded.body))
                    assertEquals(listOf(short, realId), calls, recorded.target)
                    assertEquals(calls, results, recorded.target)
                    assertTrue(Regex("[A-Za-z0-9]{9}").matches(calls.first()))
                }
                assertEquals(syntheticId, (input.messages[1].content.single() as Conversation.Message.ContentItem.ToolCall).id.value)
            }
        }
    }

    private fun assertSingleRequest(
        server: SdkHttpTestServer,
        target: String,
        credentialHeader: String,
        credentialValue: String,
    ): JsonObject {
        val requests = server.requests
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals(target, request.target)
        assertEquals(listOf(credentialValue), request.headers[credentialHeader])
        assertEquals("application/json", request.headers.getValue("content-type").single().substringBefore(';'))
        assertTrue(request.headers.getValue("user-agent").single().isNotBlank())
        assertFalse(TEST_TOKEN in request.body)
        return Json.parseToJsonElement(request.body).jsonObject.also {
            assertEquals(MODEL, it.getValue("model").jsonPrimitive.content)
        }
    }

    private fun assertAnswer(response: AiRuntimeResponse) {
        assertEquals(AiStepOutcome.COMPLETE, response.outcome)
        assertEquals(ANSWER, response.messages.single().content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>().single().structured.fullText)
    }

    private fun JsonArray.roles(): List<String> = map { it.jsonObject.getValue("role").jsonPrimitive.content }
    private fun JsonArray.texts(): List<String> = map { it.jsonObject.getValue("content").text() }
    private fun JsonElement.text(): String = when (this) {
        is JsonPrimitive -> content
        is JsonArray -> joinToString("") { it.jsonObject.getValue("text").jsonPrimitive.content }
        else -> error("Unexpected text representation: $this")
    }

    private val toolSchema = Json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""").jsonObject
    private val answerSchema = Json.parseToJsonElement("""{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""").jsonObject
    private val tool = object : AiToolCallback {
        override val definition = AiToolDefinition("lookup", "Synthetic test function.", toolSchema.toString())
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("HTTP contract tests must not execute tools")
    }

    private fun request(): AiRuntimeRequest {
        val user = Conversation.Message(
            id = Conversation.Message.Id("user-1"),
            conversationId = Conversation.Id("http-test"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("First question.")),
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        return AiRuntimeRequest(
            systemPrompts = listOf("First instruction.", "", "Second instruction."),
            messages = listOf(
                user,
                user.copy(id = Conversation.Message.Id("assistant-1"), role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Earlier answer.")))),
                user.copy(id = Conversation.Message.Id("user-2"), content = listOf(Conversation.Message.ContentItem.UserMessage("Next question."))),
            ),
            tools = listOf(tool),
            options = AiRuntimeOptions(
                maxOutputTokens = 256,
                responseFormat = AiResponseFormat.JsonSchema(name = "answer", schema = answerSchema),
                assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
            ),
        )
    }

    private fun chatResponse(): String = """{
        "id":"chatcmpl-fixture","object":"chat.completion","created":1,"model":"$MODEL",
        "choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(ANSWER)}},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}
    }"""

    private fun responsesResponse(): String = """{
        "id":"resp-fixture","object":"response","created_at":1,"status":"completed","model":"$MODEL",
        "output":[{"type":"message","id":"msg-fixture","role":"assistant","status":"completed",
            "content":[{"type":"output_text","text":${JsonPrimitive(ANSWER)},"annotations":[]}]}],
        "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,
            "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}
    }"""

    private fun anthropicResponse(): String = """{
        "id":"msg-fixture","type":"message","role":"assistant","model":"$MODEL",
        "content":[{"type":"text","text":${JsonPrimitive(ANSWER)}}],
        "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}
    }"""

    private companion object {
        const val TEST_TOKEN = "synthetic-sdk-http-token"
        const val MODEL = "gateway-account/test-model"
        const val SYSTEM_PROMPT = "First instruction.\n\nSecond instruction."
        const val ANSWER = """{"answer":"fixture"}"""
    }
}
