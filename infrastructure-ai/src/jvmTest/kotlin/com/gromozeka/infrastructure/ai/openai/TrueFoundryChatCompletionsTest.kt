package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.runtime.RoutingAiRuntimeProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpTestServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

/** Real SDK and production runtime against synthetic gateway replies; no live TrueFoundry credentials. */
class TrueFoundryChatCompletionsTest {
    @Test
    fun `chat-only gateway supports encrypted reasoning tool replay schema and controlled 429 retry`() = runBlocking {
        val replies = listOf(
            SdkHttpTestServer.Reply("""{"error":{"message":"Synthetic limit","type":"rate_limit_error"}}""", 429,
                mapOf("Retry-After" to "0")),
            SdkHttpTestServer.Reply(completion(TOOL_MESSAGE, "tool_calls")),
            SdkHttpTestServer.Reply(completion(ANSWER_MESSAGE, "stop")),
        )
        SdkHttpTestServer(replies, expectedTarget = CHAT_PATH).use { server ->
            val runtime = runtime(server.origin)
            assertFalse(runtime.capabilities.supportsAutoCompaction)
            val first = runtime.call(request(listOf(user)))
            assertEquals(AiStepOutcome.TOOL_CALLS, first.outcome)
            assertEquals("lookup", first.toolCalls.single().call.name)
            assertEquals(Json.parseToJsonElement("""{"value":"test"}"""), first.toolCalls.single().call.input)
            val stored = persist(first)
            val second = runtime.call(request(listOf(user, stored, toolResult)))
            assertEquals(AiStepOutcome.COMPLETE, second.outcome)
            val text = second.messages.single().content.filterIsInstance<ContentItem.AssistantMessage>().single().structured
            assertEquals("Result: found.", text.fullText)
            assertFalse(text.failedToParse)
            assertEquals(6, second.usage?.promptTokens)
            assertEquals(4, second.usage?.cacheReadTokens)
            assertEquals(3, second.usage?.thinkingTokens)
            assertEquals(2, second.usage?.completionTokens)
            assertEquals(10, second.contextUsage?.inputTokens)

            assertEquals(3, server.requests.size)
            assertEquals(server.requests[0].body, server.requests[1].body)
            for (sent in server.requests) {
                assertEquals(CHAT_PATH, sent.target)
                assertEquals("POST", sent.method)
                assertEquals(listOf("Bearer $TOKEN"), sent.headers["authorization"])
                assertNull(sent.headers["x-tfy-openai-use-responses-api"], "The client must not force a gateway route change")
                val body = Json.parseToJsonElement(sent.body).jsonObject
                assertEquals(MODEL, body.getValue("model").jsonPrimitive.content)
                assertEquals("low", body.getValue("reasoning_effort").jsonPrimitive.content)
                assertEquals(128, body.getValue("max_completion_tokens").jsonPrimitive.int)
                assertFalse("max_tokens" in body)
                assertFalse("context_management" in body)
                assertFalse("previous_response_id" in body)
                assertFalse("include" in body)
                assertEquals("json_schema", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
                assertTrue(body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject.getValue("strict").jsonPrimitive.boolean)
                assertEquals("lookup", body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            }
            val messages = Json.parseToJsonElement(server.requests.last().body).jsonObject.getValue("messages").jsonArray
            assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals("Follow the tool results.", messages.first().jsonObject.getValue("content").jsonPrimitive.content)
            val assistant = messages[2].jsonObject
            val source = Json.parseToJsonElement(TOOL_MESSAGE).jsonObject
            for (field in listOf("thinking_blocks", "reasoning_content", "content", "tool_calls")) {
                assertEquals(source.getValue(field), assistant.getValue(field))
            }
            assertEquals("call-1", messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
            assertEquals("found", messages[3].jsonObject.getValue("content").jsonPrimitive.content)
        }
    }

    @Test
    fun `malformed encrypted reasoning is a safe terminal failure and never triggers another API`() = runBlocking {
        val source = Json.parseToJsonElement(TOOL_MESSAGE).jsonObject
        val malformed = JsonObject(source + ("thinking_blocks" to Json.parseToJsonElement("""[
            {"type":"reasoning","encrypted_content":"$CIPHERTEXT"}
        ]""")))
        SdkHttpTestServer(listOf(SdkHttpTestServer.Reply(completion(malformed.toString(), "tool_calls"))),
            expectedTarget = CHAT_PATH).use { server ->
            val error = assertFailsWith<AiCallException> { runtime(server.origin).call(request(listOf(user))) }
            assertEquals(AiCallFailure.Kind.INVALID_RESPONSE, error.failure.kind)
            assertEquals(1, error.failure.attempts)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.stackTraceToString().contains(CIPHERTEXT))
            assertEquals(listOf(CHAT_PATH), server.requests.map { it.target })
        }
    }

    @Test
    fun `disabled Responses endpoint returns 404 without falling back to Chat`() = runBlocking {
        SdkHttpTestServer(listOf(SdkHttpTestServer.Reply(completion(ANSWER_MESSAGE, "stop"))),
            expectedTarget = CHAT_PATH).use { server ->
            val error = assertFailsWith<AiCallException> {
                runtime(server.origin, responses = true).call(request(listOf(user)))
            }
            assertEquals(404, error.failure.httpStatus)
            assertEquals(1, error.failure.attempts)
            assertEquals(listOf("/api/llm/responses"), server.requests.map { it.target })
        }
    }

    private fun runtime(origin: String, responses: Boolean = false): com.gromozeka.domain.service.AiRuntime {
        val id = AiConnection.Id("gateway")
        val settings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"))
        val connection = if (responses) AiConnection.OpenAiApi(id, "Gateway", enabled = true,
            baseUrl = "$origin/api/llm", apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
        else AiConnection.OpenAiCompatible(id, "Gateway", enabled = true,
            baseUrl = "$origin/api/llm", apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
        val configuration = AiModelConfiguration(AiModelConfiguration.Id("configuration"), id, MODEL, "Gateway model",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            defaultParameters = AiModelConfiguration.DefaultParameters(timeoutSeconds = 10))
        val spec = AiModelSpec(MODEL, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000, 8192)))
        return RoutingAiRuntimeProvider(listOf(OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources))))
            .getRuntime(ResolvedAiRuntime(connection, configuration, spec), null)
    }

    private fun request(messages: List<Message>) = AiRuntimeRequest(listOf("Follow the tool results."), messages,
        tools = listOf(tool), options = AiRuntimeOptions(
            maxOutputTokens = 128, reasoning = AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE, effort = AiReasoningEffort.LOW),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            responseFormat = AiResponseFormat.JsonSchema("answer", Json.parseToJsonElement("""{
                "type":"object","properties":{"fullText":{"type":"string"}},"required":["fullText"],"additionalProperties":false
            }""").jsonObject), toolContext = mapOf("threadId" to "thread")))

    private fun persist(response: AiRuntimeResponse): Message {
        val assistant = response.messages.single()
        val metadata = JsonObject(assistant.metadata.mapValues { (_, value) -> when (value) {
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            null -> JsonNull
            else -> error("Unexpected fixture metadata")
        } })
        val stored = user.copy(id = Message.Id("assistant"), role = Message.Role.ASSISTANT,
            content = assistant.content, providerMetadata = metadata)
        return Json.decodeFromString<Message>(Json.encodeToString(stored))
    }

    private val user = Message(Message.Id("user"), Conversation.Id("conversation"), role = Message.Role.USER,
        content = listOf(ContentItem.UserMessage("Check the data.")), createdAt = Instant.fromEpochMilliseconds(0))
    private val toolResult = user.copy(id = Message.Id("tool-result"), content = listOf(
        ContentItem.ToolResult(ContentItem.ToolCall.Id("call-1"), "lookup", listOf(ContentItem.ToolResult.Data.Text("found")))))
    private val tool = object : AiToolCallback {
        override val definition = AiToolDefinition("lookup", "Synthetic lookup.",
            """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""")
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Tool results are synthetic")
    }

    private object Sources : SettingsProvider {
        override val userProfile: UserProfile get() = error("Not used")
        override val userDeviceSettings: UserDeviceSettings get() = error("Not used")
        override val mode: AppMode get() = error("Not used")
        override val homeDirectory: String get() = error("Not used")
        override fun resolveSecret(secretRef: SecretRef?): String {
            check(secretRef == SecretRef.Inline(TOKEN))
            return TOKEN
        }
    }

    private companion object {
        const val CHAT_PATH = "/api/llm/chat/completions"
        const val TOKEN = "synthetic-truefoundry-token"
        const val MODEL = "gateway-account/reasoning-model"
        const val CIPHERTEXT = "synthetic-encrypted-state"
        const val TOOL_MESSAGE = """{"role":"assistant","content":"I will check.","reasoning_content":"Use the lookup.",
            "thinking_blocks":[{"type":"reasoning","id":"rs-1","encrypted_content":"$CIPHERTEXT","vendor_field":{"a":1}}],
            "tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{\"value\":\"test\"}"}}]}"""
        const val ANSWER_MESSAGE = """{"role":"assistant","content":"{\"fullText\":\"Result: found.\"}"}"""
        fun completion(message: String, finish: String) = """{"id":"completion","object":"chat.completion","created":1,
            "model":"resolved-model","choices":[{"index":0,"message":$message,"finish_reason":"$finish"}],
            "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
            "prompt_tokens_details":{"cached_tokens":4},"completion_tokens_details":{"reasoning_tokens":3}}}"""
    }
}
