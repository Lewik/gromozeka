package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission

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
            assertFalse(runtime.capabilities.supportsNativeCompaction)
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

    @Test
    fun `opt in repairs the reported schema finish reason without changing content usage or opaque replay`() = runBlocking {
        val source = JsonObject(Json.parseToJsonElement(TOOL_MESSAGE).jsonObject.minus("tool_calls") +
            ("content" to JsonPrimitive("""{"fullText":"ALPHA-74"}""")))
        for (calls in listOf<JsonElement?>(null, JsonNull, JsonArray(emptyList()))) {
            val message = if (calls == null) source else JsonObject(source + ("tool_calls" to calls))
            SdkHttpTestServer(completion(message.toString(), "tool_calls")).use { server ->
                val result = runtime(server.origin, workaround = true).call(request(listOf(user)))
                assertEquals(AiStepOutcome.COMPLETE, result.outcome)
                assertEquals("stop", result.finishReason)
                assertTrue(result.toolCalls.isEmpty())
                assertEquals("tool_calls", result.providerMetadata["originalFinishReason"])
                assertEquals("chatSchemaEmptyToolCallsAsStop", result.providerMetadata["finishReasonNormalization"])
                val stored = persist(result)
                assertEquals("ALPHA-74", stored.content.filterIsInstance<ContentItem.AssistantMessage>().single().structured.fullText)
                val raw = stored.providerMetadata.getValue("openaiCompatibleMessage").jsonObject
                for ((key, value) in message) assertEquals(value, raw[key], key)
                assertEquals(JsonPrimitive("tool_calls"), stored.providerMetadata["originalFinishReason"])
                assertEquals(6, result.usage?.promptTokens)
                assertEquals(4, result.usage?.cacheReadTokens)
                assertEquals(3, result.usage?.thinkingTokens)
                assertEquals(2, result.usage?.completionTokens)
                assertEquals(10, result.contextUsage?.inputTokens)
                val sent = Json.parseToJsonElement(server.requests.single().body).jsonObject
                assertEquals("json_schema", sent.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
                assertTrue(sent["stream"] == null || sent["stream"] == JsonPrimitive(false))
            }
        }
    }

    @Test
    fun `ordinary schema answer and genuine function calls stay unchanged with workaround enabled`() = runBlocking {
        val actualCall = JsonObject(Json.parseToJsonElement(TOOL_MESSAGE).jsonObject +
            ("content" to JsonPrimitive("""{"fullText":"Working"}""")))
        SdkHttpTestServer(completion(actualCall.toString(), "tool_calls"), completion(ANSWER_MESSAGE, "tool_calls"),
            completion(ANSWER_MESSAGE, "stop")).use { server ->
            val runtime = runtime(server.origin, workaround = true)
            val action = runtime.call(request(listOf(user)))
            assertEquals(AiStepOutcome.TOOL_CALLS, action.outcome)
            assertEquals("lookup", action.toolCalls.single().call.name)
            assertNull(action.providerMetadata["finishReasonNormalization"])
            val storedAction = persist(action)
            val answer = runtime.call(request(listOf(user, storedAction, toolResult)))
            assertEquals(AiStepOutcome.COMPLETE, answer.outcome)
            assertEquals("stop", answer.finishReason)
            val storedAnswer = persist(answer).copy(id = Message.Id("answer"))
            val followup = user.copy(id = Message.Id("followup"))
            val next = runtime.call(request(listOf(user, storedAction, toolResult, storedAnswer, followup)))
            assertEquals(AiStepOutcome.COMPLETE, next.outcome)
            assertNull(next.providerMetadata["finishReasonNormalization"])
            val sent = Json.parseToJsonElement(server.requests.last().body).jsonObject.getValue("messages").jsonArray
            val replay = sent[2].jsonObject
            for (key in listOf("thinking_blocks", "reasoning_content", "tool_calls", "content")) assertEquals(actualCall[key], replay[key])
            assertEquals(3, server.requests.size)
        }
    }

    @Test
    fun `disabled flag missing schema and forced tools never authorize finish reason repair`() = runBlocking {
        val cases = listOf(
            false to request(listOf(user)),
            true to request(listOf(user)).let { it.copy(options = it.options.copy(responseFormat = AiResponseFormat.Text)) },
            true to request(listOf(user)).let { it.copy(options = it.options.copy(toolChoice = AiToolChoice.RequiredAny)) },
            true to request(listOf(user)).let { it.copy(options = it.options.copy(toolChoice = AiToolChoice.RequiredTool("lookup"))) },
        )
        for ((enabled, request) in cases) SdkHttpTestServer(completion(ANSWER_MESSAGE, "tool_calls")).use { server ->
            val result = runtime(server.origin, workaround = enabled).call(request)
            assertEquals(AiStepOutcome.TOOL_CALLS, result.outcome)
            assertEquals("tool_calls", result.finishReason)
            assertTrue(result.toolCalls.isEmpty())
            assertNull(result.providerMetadata["finishReasonNormalization"])
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `invalid JSON or schema does not become a successful answer`() = runBlocking {
        val invalid = listOf("not JSON", "```json\n{\"fullText\":\"x\"}\n```", "{\"fullText\":1}",
            "{}", "{\"fullText\":\"x\",\"extra\":1}", "{\"fullText\":\"x\",\"fullText\":\"y\"}",
            "{\"fullText\":\"x\"} {}", "{\"fullText\":\"x\"", "null", "[]")
        for (text in invalid) {
            val message = buildJsonObject { put("role", "assistant"); put("content", text) }
            SdkHttpTestServer(completion(message.toString(), "tool_calls")).use { server ->
                val result = runtime(server.origin, workaround = true).call(request(listOf(user)))
                assertEquals(AiStepOutcome.TOOL_CALLS, result.outcome, text)
                assertNull(result.providerMetadata["finishReasonNormalization"])
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test
    fun `malformed tool fields legacy function calls and refusal are not treated as absent`() = runBlocking {
        val source = Json.parseToJsonElement(ANSWER_MESSAGE).jsonObject
        val cases = listOf(
            "tool_calls" to JsonPrimitive(""), "tool_calls" to JsonObject(emptyMap()), "tool_calls" to JsonPrimitive(false),
            "tool_calls" to JsonArray(listOf(JsonNull)), "tool_calls" to JsonArray(listOf(JsonObject(emptyMap()))),
            "function_call" to JsonPrimitive(""), "function_call" to JsonArray(emptyList()), "function_call" to JsonObject(emptyMap()),
            "function_call" to Json.parseToJsonElement("""{"name":"lookup","arguments":"{}"}"""),
            "refusal" to JsonPrimitive(""), "refusal" to JsonPrimitive("Refused"), "refusal" to JsonPrimitive(false),
            "role" to JsonPrimitive("user"),
        )
        for ((field, value) in cases) SdkHttpTestServer(completion(JsonObject(source + (field to value)).toString(), "tool_calls")).use { server ->
            try {
                val result = runtime(server.origin, workaround = true).call(request(listOf(user)))
                assertEquals(AiStepOutcome.TOOL_CALLS, result.outcome, "$field=$value")
                assertNull(result.providerMetadata["finishReasonNormalization"])
            } catch (error: AiCallException) {
                assertEquals(AiCallFailure.Kind.INVALID_RESPONSE, error.failure.kind)
            }
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `schema workaround does not repair HTTP errors truncated envelopes or other finish reasons`() = runBlocking {
        SdkHttpTestServer(listOf(SdkHttpTestServer.Reply(completion(ANSWER_MESSAGE, "tool_calls"), 500))).use { server ->
            val error = assertFailsWith<AiCallException> { runtime(server.origin, workaround = true).call(request(listOf(user))) }
            assertEquals(AiCallFailure.Kind.HTTP_ERROR, error.failure.kind)
            assertEquals(1, server.requests.size)
        }
        SdkHttpTestServer(completion(ANSWER_MESSAGE, "tool_calls").dropLast(8)).use { server ->
            assertFailsWith<AiCallException> { runtime(server.origin, workaround = true).call(request(listOf(user))) }
            assertEquals(1, server.requests.size)
        }
        for ((finish, outcome) in listOf("length" to AiStepOutcome.INCOMPLETE, "content_filter" to AiStepOutcome.REFUSED))
            SdkHttpTestServer(completion(ANSWER_MESSAGE, finish)).use { server ->
                val result = runtime(server.origin, workaround = true).call(request(listOf(user)))
                assertEquals(outcome, result.outcome)
                assertEquals(finish, result.finishReason)
                assertNull(result.providerMetadata["finishReasonNormalization"])
                assertEquals(1, server.requests.size)
            }
    }

    @Test
    fun `validation uses the sent schema constraints rather than only the assistant DTO`() = runBlocking {
        val constrained = request(listOf(user)).let { it.copy(options = it.options.copy(responseFormat = AiResponseFormat.JsonSchema("restricted",
            Json.parseToJsonElement("""{"type":"object","properties":{"fullText":{"type":"string","enum":["ALPHA-74"]}},"required":["fullText"],"additionalProperties":false}""").jsonObject))) }
        for ((text, expected) in listOf("ALPHA-74" to AiStepOutcome.COMPLETE, "BETA-22" to AiStepOutcome.TOOL_CALLS)) {
            val message = buildJsonObject { put("role", "assistant"); put("content", buildJsonObject { put("fullText", text) }.toString()) }
            SdkHttpTestServer(completion(message.toString(), "tool_calls")).use { server ->
                assertEquals(expected, runtime(server.origin, workaround = true).call(constrained).outcome)
                val body = Json.parseToJsonElement(server.requests.single().body).jsonObject
                assertEquals((constrained.options.responseFormat as AiResponseFormat.JsonSchema).schema,
                    body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject.getValue("schema"))
            }
        }
    }

    @Test
    fun `repair is restricted to a single compatible Chat choice`() {
        for (kind in listOf(AiConnection.Kind.OPENAI_API, AiConnection.Kind.OPENAI_COMPATIBLE)) {
            val spec = AiModelSpec(MODEL, kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
                AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32000, 8192)),
                behavior = AiModelBehavior(chatSchemaEmptyToolCallsAsStop = true))
            val mapper = OpenAiSdkMessageMapper(kind, "connection", MODEL, com.gromozeka.infrastructure.ai.runtime.ModelGenerationPolicy(spec))
            val body = Json.parseToJsonElement(completion(ANSWER_MESSAGE, "tool_calls")).jsonObject
            val multiple = JsonObject(body + ("choices" to JsonArray(body.getValue("choices").jsonArray +
                JsonObject(body.getValue("choices").jsonArray.single().jsonObject + ("index" to JsonPrimitive(1))))))
            val native = com.openai.core.jsonMapper().readValue((if (kind == AiConnection.Kind.OPENAI_API) body else multiple).toString(),
                com.openai.models.chat.completions.ChatCompletion::class.java)
            val result = mapper.toRuntimeResponse(native, AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
                mapper.toCreateParams(MODEL, request(listOf(user))))
            assertEquals(AiStepOutcome.TOOL_CALLS, result.outcome)
            assertNull(result.providerMetadata["finishReasonNormalization"])
        }
    }

    @Test
    fun `model switch estimates sent Chat context without discarded encrypted reasoning`() = runBlocking {
        val ciphertext = "synthetic-ciphertext-".repeat(65_000)
        val source = TOOL_MESSAGE.replace(CIPHERTEXT, ciphertext)
        SdkHttpTestServer(completion(source, "tool_calls"), completion(ANSWER_MESSAGE, "stop"),
            completion(ANSWER_MESSAGE, "stop")).use { server ->
            val sol = runtime(server.origin, model = "gateway/sol")
            val astra = runtime(server.origin, model = "gateway/astra")
            val first = sol.call(request(listOf(user)))
            val stored = persist(first)
            val archived = Json.encodeToString(stored)
            val beforeTool = request(listOf(user, stored))
            val next = request(listOf(user, stored, toolResult))
            val sameModel = sol.inspectContext(next)
            assertEquals(AiContextInspection.Precision.USAGE_CALIBRATED_ESTIMATE, sameModel.precision)
            assertEquals(15 + sol.tokenEstimator.input(next) - sol.tokenEstimator.input(beforeTool), sameModel.inputTokens)

            val switched = astra.inspectContext(next)
            assertEquals(AiContextInspection.Precision.ESTIMATE, switched.precision)
            assertEquals(astra.tokenEstimator.input(next), switched.inputTokens)
            assertTrue(AiRequestTokenEstimate.input(next) > 300_000, "Regression fixture must expose the old overestimate")
            assertTrue(switched.inputTokens < 1_000, "Discarded replay must not trigger a 1000-token compact threshold")
            assertEquals(1, server.requests.size, "Chat inspection must not probe a different API")

            astra.call(next)
            val sentToAstra = Json.parseToJsonElement(server.requests[1].body).jsonObject
            val astraAssistant = sentToAstra.getValue("messages").jsonArray[2].jsonObject
            assertNull(astraAssistant["thinking_blocks"])
            assertNull(astraAssistant["reasoning_content"])
            assertFalse(server.requests[1].body.contains(ciphertext))
            assertEquals(OpenAiChatTokenEstimate.input(sentToAstra), switched.inputTokens)

            sol.call(next)
            val solAssistant = Json.parseToJsonElement(server.requests[2].body).jsonObject.getValue("messages").jsonArray[2].jsonObject
            assertEquals(ciphertext, solAssistant.getValue("thinking_blocks").jsonArray.single().jsonObject.getValue("encrypted_content").jsonPrimitive.content)
            assertEquals(archived, Json.encodeToString(stored), "Counting and model switches must not rewrite archived reasoning")
            assertTrue(server.requests.all { it.target == CHAT_PATH })
        }
    }

    @Test
    fun `opaque byte length never stands in for reasoning tokens and user text is not stripped`() {
        fun body(ciphertext: String) = Json.parseToJsonElement("""{"messages":[${TOOL_MESSAGE.replace(CIPHERTEXT, ciphertext)}]}""").jsonObject
        val short = body("short")
        val large = body("x".repeat(1_200_000))
        assertEquals(OpenAiChatTokenEstimate.input(short), OpenAiChatTokenEstimate.input(large))
        val original = Json.parseToJsonElement("""{"messages":[{"role":"user","content":"ordinary"}]}""").jsonObject
        val longer = Json.parseToJsonElement("""{"messages":[{"role":"user","content":"encrypted_content is a business field ${"x".repeat(2000)}"}]}""").jsonObject
        assertTrue(OpenAiChatTokenEstimate.input(longer) > OpenAiChatTokenEstimate.input(original) + 400)
        assertEquals(large, body("x".repeat(1_200_000)), "Estimation must not mutate the outbound payload")
    }

    @Test
    fun `Chat input and output use the same mapped assistant representation`() {
        val mapper = OpenAiSdkMessageMapper(AiConnection.Kind.OPENAI_COMPATIBLE, "gateway", MODEL)
        val original = Json.parseToJsonElement(TOOL_MESSAGE).jsonObject
        val generated = AiAssistantMessage(listOf(ContentItem.Thinking("Display-only text ".repeat(10_000))),
            metadata = mapOf("openaiCompatibleMessage" to original))
        val body = Json.parseToJsonElement("""{"messages":[$TOOL_MESSAGE]}""").jsonObject
        assertEquals(OpenAiChatTokenEstimate.input(body) - 8, mapper.output(listOf(generated)))
        assertFailsWith<IllegalArgumentException> { mapper.output(listOf(generated.copy(metadata = emptyMap()))) }
    }

    private fun runtime(origin: String, responses: Boolean = false, workaround: Boolean = false,
        model: String = MODEL): com.gromozeka.domain.service.AiRuntime {
        val id = AiConnection.Id("gateway")
        val settings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"))
        val connection = if (responses) AiConnection.OpenAiApi(id, "Gateway", enabled = true,
            baseUrl = "$origin/api/llm", apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
        else AiConnection.OpenAiCompatible(id, "Gateway", enabled = true,
            baseUrl = "$origin/api/llm", apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
        val configuration = AiModelConfiguration(AiModelConfiguration.Id("configuration"), id, model, "Gateway model",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            defaultParameters = AiModelConfiguration.DefaultParameters(timeoutSeconds = 10))
        val spec = AiModelSpec(model, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000, 8192)),
            behavior = AiModelBehavior(chatSchemaEmptyToolCallsAsStop = workaround))
        return RoutingAiRuntimeProvider(listOf(OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources), admission = TestAiAdmission())))
            .getRuntime(ResolvedAiRuntime(connection, configuration, spec), null)
    }

    private fun request(messages: List<Message>) = AiRuntimeRequest(listOf("Follow the tool results."), messages,
        tools = listOf(tool), options = AiRuntimeOptions(
            usagePurpose = "CONVERSATION", maxOutputTokens = 128,
            reasoning = AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE, effort = AiReasoningEffort.LOW),
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
