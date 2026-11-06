package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.runtime.SdkHttpTestServer
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import java.net.Proxy
import java.time.Duration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class OpenAiThinkingReplayTest {
    private val mapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, CONNECTION, MODEL)

    @Test
    fun `thinking and tool data survive persisted history without reconstruction`() {
        val response = mapResponse()
        val stored = store(response)
        val replay = outgoingAssistant(mapper, stored)

        assertEquals(message.getValue("thinking_blocks"), replay.getValue("thinking_blocks"))
        assertEquals(message.getValue("reasoning_content"), replay.getValue("reasoning_content"))
        assertEquals(message.getValue("content"), replay.getValue("content"))
        assertEquals(message.getValue("tool_calls"), replay.getValue("tool_calls"))
        assertFalse("debug_only" in replay)

        val thinking = response.messages.single().content.filterIsInstance<Conversation.Message.ContentItem.Thinking>()
        assertEquals(3, thinking.size)
        assertEquals(listOf("  Check the data.\n", "", ""), thinking.map { it.thinking })
        assertEquals(listOf("synthetic-signature-visible", "synthetic-signature-empty", "synthetic-redacted-data"), thinking.map { it.signature })
        assertEquals(Conversation.Message.ContentItem.Thinking.Kind.REDACTED, thinking.last().kind)
    }

    @Test
    fun `signed empty reasoning survives without ordinary answer or tools`() {
        val onlyThinking = JsonObject(mapOf(
            "role" to JsonPrimitive("assistant"),
            "thinking_blocks" to JsonArray(listOf(blocks[1])),
        ))
        val response = mapResponse(onlyThinking, "length")
        assertEquals(AiStepOutcome.INCOMPLETE, response.outcome)
        val thinking = response.messages.single().content.single() as Conversation.Message.ContentItem.Thinking
        assertEquals("", thinking.thinking)
        assertEquals("synthetic-signature-empty", thinking.signature)
        assertEquals(onlyThinking.getValue("thinking_blocks"), outgoingAssistant(mapper, store(response)).getValue("thinking_blocks"))
    }

    @Test
    fun `aggregate summary remains visible when signed blocks contain no text`() {
        val input = JsonObject(message + mapOf(
            "thinking_blocks" to JsonArray(listOf(blocks[1], blocks[2])),
            "reasoning_content" to JsonPrimitive("Readable summary."),
        ))
        val response = mapResponse(input)
        val thinking = response.messages.single().content.filterIsInstance<Conversation.Message.ContentItem.Thinking>()
        assertEquals(listOf("Readable summary.", "", ""), thinking.map { it.thinking })
        assertEquals(listOf(null, "synthetic-signature-empty", "synthetic-redacted-data"), thinking.map { it.signature })
        assertEquals(input.getValue("thinking_blocks"), outgoingAssistant(mapper, store(response)).getValue("thinking_blocks"))
    }

    @Test
    fun `opaque state is not replayed across connection model provider or branch boundaries`() {
        val stored = store(mapResponse())
        val originalMetadata = stored.providerMetadata
        fun verify(
            targetMapper: OpenAiSdkMessageMapper = mapper,
            targetModel: String = MODEL,
            thread: String = THREAD,
            source: Conversation.Message = stored,
        ) {
            val replay = outgoingAssistant(targetMapper, source, targetModel, thread)
            assertFalse("thinking_blocks" in replay)
            assertFalse("reasoning_content" in replay)
            assertFalse("synthetic-signature" in replay.toString())
            assertFalse("synthetic-redacted-data" in replay.toString())
            assertFalse("call-bound-signature" in replay.toString())
            assertEquals("I will use the tool.", replay.getValue("content").jsonPrimitive.content)
            val call = replay.getValue("tool_calls").jsonArray.single().jsonObject
            assertEquals("call-1", call.getValue("id").jsonPrimitive.content)
            assertEquals("lookup", call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        }

        verify(targetMapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, "other-connection", MODEL))
        verify(targetMapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, CONNECTION, "other-model"), targetModel = "other-model")
        assertFailsWith<IllegalArgumentException> {
            outgoingAssistant(mapper, stored, targetModel = "other-model")
        }
        verify(targetMapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_API, CONNECTION, MODEL))
        verify(thread = "fork-thread")
        verify(source = stored.copy(providerMetadata = JsonObject(stored.providerMetadata - AI_REPLAY_THREAD_METADATA_KEY)))
        verify(targetMapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, modelName = MODEL),
            source = stored.copy(providerMetadata = JsonObject(stored.providerMetadata - "connectionId")))
        assertEquals(originalMetadata, stored.providerMetadata)
    }

    @Test
    fun `malformed thinking blocks fail instead of losing state silently`() {
        val malformed = listOf(
            JsonPrimitive("not an array"),
            JsonArray(listOf(JsonPrimitive("not an object"))),
            JsonArray(listOf(Json.parseToJsonElement("""{"type":"thinking","thinking":42,"signature":"do-not-log"}"""))),
            JsonArray(listOf(Json.parseToJsonElement("""{"type":"redacted_thinking"}"""))),
        )
        for (value in malformed) {
            val error = assertFailsWith<IllegalArgumentException> {
                mapResponse(JsonObject(message + ("thinking_blocks" to value)))
            }
            assertFalse("do-not-log" in error.message.orEmpty())
        }
    }

    @Test
    fun `real SDK carries thinking through a tool result round trip over HTTP`() {
        val finalMessage = JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonPrimitive("Done.")))
        SdkHttpTestServer(completion(message, "tool_calls"), completion(finalMessage, "stop")).use { server ->
            val client = OpenAIOkHttpClient.builder()
                .baseUrl("${server.origin}/api/llm")
                .apiKey("synthetic-replay-test-token")
                .proxy(Proxy.NO_PROXY)
                .timeout(Duration.ofSeconds(5))
                .build()
            try {
                val first = client.chat().completions().create(mapper.toCreateParams(MODEL, request(listOf(user))))
                val firstResponse = mapper.toRuntimeResponse(first, AiModelConfiguration.AssistantResponseFormat.TEXT)
                assertEquals(AiStepOutcome.TOOL_CALLS, firstResponse.outcome)
                val stored = store(firstResponse)
                val second = client.chat().completions().create(mapper.toCreateParams(MODEL, request(listOf(user, stored, toolResult))))
                val answer = mapper.toRuntimeResponse(second, AiModelConfiguration.AssistantResponseFormat.TEXT)
                assertEquals(AiStepOutcome.COMPLETE, answer.outcome)
                assertEquals("Done.", (answer.messages.single().content.single() as Conversation.Message.ContentItem.AssistantMessage).structured.fullText)
            } finally { client.close() }

            val requests = server.requests
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.method == "POST" && it.target == "/api/llm/chat/completions" })
            val offeredTool = Json.parseToJsonElement(requests.first().body).jsonObject.getValue("tools").jsonArray.single().jsonObject
            assertEquals("lookup", offeredTool.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            val sent = Json.parseToJsonElement(requests.last().body).jsonObject.getValue("messages").jsonArray
            assertEquals(listOf("system", "user", "assistant", "tool"), sent.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            val assistant = sent[2].jsonObject
            assertEquals(message.getValue("thinking_blocks"), assistant.getValue("thinking_blocks"))
            assertEquals(message.getValue("tool_calls"), assistant.getValue("tool_calls"))
            assertEquals("call-1", sent[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
            assertEquals("Synthetic tool result.", sent[3].jsonObject.getValue("content").jsonPrimitive.content)
        }
    }

    private fun mapResponse(value: JsonObject = message, finishReason: String = "tool_calls"): AiRuntimeResponse =
        mapper.toRuntimeResponse(
            com.openai.core.jsonMapper().readValue(completion(value, finishReason), ChatCompletion::class.java),
            AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

    private fun store(response: AiRuntimeResponse): Conversation.Message {
        val assistant = response.messages.single()
        val metadata = JsonObject(assistant.metadata.mapValues { (_, value) ->
            when (value) {
                is JsonElement -> value
                is String -> JsonPrimitive(value)
                null -> JsonNull
                else -> error("Unexpected fixture metadata type")
            }
        } + (AI_REPLAY_THREAD_METADATA_KEY to JsonPrimitive(THREAD)))
        val stored = user.copy(id = Conversation.Message.Id("assistant-1"), role = Conversation.Message.Role.ASSISTANT,
            content = assistant.content, providerMetadata = metadata)
        return Json.decodeFromString<Conversation.Message>(Json.encodeToString(stored))
    }

    private fun outgoingAssistant(
        targetMapper: OpenAiSdkMessageMapper,
        stored: Conversation.Message,
        targetModel: String = MODEL,
        thread: String = THREAD,
    ): JsonObject {
        val params = targetMapper.toCreateParams(targetModel, request(listOf(user, stored), thread))
        return Json.parseToJsonElement(com.openai.core.jsonMapper().writeValueAsString(params._body()))
            .jsonObject.getValue("messages").jsonArray.single { it.jsonObject.getValue("role") == JsonPrimitive("assistant") }.jsonObject
    }

    private fun request(messages: List<Conversation.Message>, thread: String = THREAD) = AiRuntimeRequest(
        systemPrompts = listOf("Follow the tool results."), messages = messages, tools = listOf(tool),
        options = AiRuntimeOptions(toolContext = mapOf("threadId" to thread)),
    )

    private val tool = object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "lookup", description = "Synthetic lookup function.",
            inputSchema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""",
        )
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Replay tests use synthetic tool results")
    }

    private val user = Conversation.Message(
        id = Conversation.Message.Id("user-1"), conversationId = Conversation.Id("conversation"), role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Check the data.")), createdAt = Instant.fromEpochMilliseconds(0),
    )
    private val toolResult = user.copy(id = Conversation.Message.Id("tool-result"), content = listOf(
        Conversation.Message.ContentItem.ToolResult(Conversation.Message.ContentItem.ToolCall.Id("call-1"),
            result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("Synthetic tool result.")), toolName = "lookup"),
    ))

    // Synthetic payloads using the documented LiteLLM thinking_blocks and Anthropic block shapes:
    // https://docs.litellm.ai/docs/reasoning_content ; https://platform.claude.com/docs/en/build-with-claude/thinking
    private val message = Json.parseToJsonElement("""{
        "role":"assistant","content":" I will use the tool. ","reasoning_content":"Check the data.",
        "thinking_blocks":[
            {"type":"thinking","thinking":"  Check the data.\n","signature":"synthetic-signature-visible","vendor_extension":{"opaque":"unchanged"}},
            {"type":"thinking","thinking":"","signature":"synthetic-signature-empty"},
            {"type":"redacted_thinking","data":"synthetic-redacted-data"}
        ],
        "tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{\"value\": \"test\"}"},"extra_content":{"signature":"call-bound-signature"}}],
        "debug_only":"not part of replay"
    }""").jsonObject
    private val blocks: JsonArray get() = message.getValue("thinking_blocks").jsonArray

    private fun completion(value: JsonObject, finishReason: String): String = """{
        "id":"chatcmpl-thinking","object":"chat.completion","created":1,"model":"$MODEL",
        "choices":[{"index":0,"message":$value,"finish_reason":"$finishReason"}],
        "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}
    }"""

    private companion object {
        const val CONNECTION = "gateway-connection"
        const val MODEL = "gateway-account/reasoning-model"
        const val THREAD = "original-thread"
    }
}
