package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.runtime.RoutingAiRuntimeProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpTestServer
import com.openai.core.jsonMapper
import com.openai.models.responses.Response
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class OpenAiResponsesCompactionTest {
    private val mapper = OpenAiResponsesMessageMapper(CONNECTION, "configuration", MODEL)

    @Test
    fun `Responses advertises compaction through both capability and runtime routes but Chat does not`() {
        val provider = RoutingAiRuntimeProvider(listOf(OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources))))
        val responses = resolved(connection("http://127.0.0.1:1/api/llm"))
        assertTrue(provider.capabilities(responses).supportsAutoCompaction)
        assertTrue(provider.getRuntime(responses, null).capabilities.supportsAutoCompaction)
        assertFalse(provider.capabilities(responses).providerManagedAutoCompaction)
        assertEquals("OPENAI_API", responses.connection.kind.nativeCompactionProvider())
        val chat = resolved(AiConnection.OpenAiCompatible(AiConnection.Id(CONNECTION), "Chat", enabled = true,
            baseUrl = "http://127.0.0.1:1/api/llm", apiKey = SecretRef.Inline(TOKEN)))
        assertFalse(provider.capabilities(chat).supportsAutoCompaction)
        assertFalse(provider.getRuntime(chat, null).capabilities.supportsAutoCompaction)
    }

    @Test
    fun `configured threshold is sent with stateless requests and absent policy stays disabled`() {
        val configured = mapper.toCreateParams(MODEL, false, request(listOf(text("user"))))
        assertEquals("compaction", configured.contextManagement().get().single().type())
        assertEquals(7000L, configured.contextManagement().get().single().compactThreshold().get())
        assertEquals(false, configured.store().get())
        assertFalse(configured.previousResponseId().isPresent)
        val disabled = mapper.toCreateParams(MODEL, false, request(listOf(text("user"))).copy(options = AiRuntimeOptions()))
        assertFalse(disabled.contextManagement().isPresent)
    }

    @Test
    fun `checkpoint survives persistence and sparse loading without replaying the archive or losing tool pairs`() {
        val response = parse(response(COMPACT, CALL))
        assertEquals(AiStepOutcome.TOOL_CALLS, response.outcome)
        assertEquals(8000, response.usage?.totalInputTokens)
        assertNull(response.contextUsage, "Pre-compaction billing is not the retained context size")
        val generated = persist(response)
        val checkpoint = assertIs<Compaction>(generated.first().content.single())
        assertEquals(Compaction.Coverage.ALL_PREVIOUS, checkpoint.coverage)
        assertEquals(Compaction.Origin.PROVIDER_AUTO, checkpoint.origin)
        assertEquals("OPENAI_API", checkpoint.providerScope?.provider)
        assertEquals(CONNECTION, checkpoint.providerScope?.connectionId)
        val history = (0 until 40).map { text("archive-$it", "ARCHIVE_MARKER_$it") } + generated + toolResult() + text("next")
        val saved = Json.encodeToString(history)
        val restored = Json.decodeFromString<List<Message>>(saved)
        val selected = selectModelHistoryMessageIds(
            restored.map { MessageContextHeader(it.id, MessageContextMetadata.from(it)) },
            restored.filter { MessageContextMetadata.from(it).hasCompaction }, "OPENAI_API",
        )
        assertTrue(selected.size < 10)
        val input = mapper.toCreateParams(MODEL, false, request(restored.filter { it.id in selected })).input().get().asResponse()
        assertEquals(listOf("compaction", "function_call", "function_call_output", "message"),
            Json.parseToJsonElement(jsonMapper().writeValueAsString(input)).jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content ?: "message" })
        assertEquals(Json.parseToJsonElement(COMPACT), Json.parseToJsonElement(jsonMapper().writeValueAsString(input.first())))
        assertEquals("tool-1", input[1].asFunctionCall().callId())
        assertEquals("tool-1", input[2].asFunctionCallOutput().callId())
        assertFalse(jsonMapper().writeValueAsString(input).contains("ARCHIVE_MARKER"))
        assertEquals(saved, Json.encodeToString(history), "Context projection must not rewrite canonical history")
    }

    @Test
    fun `checkpoint payload remains replayable when branch rules remove raw provider metadata`() {
        val generated = persist(parse(response(COMPACT, ANSWER)))
        val history = listOf(text("old", "ARCHIVE_MARKER")) + generated
        val input = mapper.toCreateParams(MODEL, false, request(history).copy(options = request(history).options.copy(
            toolContext = mapOf("threadId" to "new-branch"),
        ))).input().get().asResponse()
        assertEquals(2, input.size)
        assertTrue(input.first().isCompaction())
        assertFalse(jsonMapper().writeValueAsString(input).contains("ARCHIVE_MARKER"))
    }

    @Test
    fun `later checkpoint replaces only model context while both source checkpoints remain stored`() {
        val first = persist(parse(response(COMPACT, ANSWER)), "first")
        val laterItem = COMPACT.replace("checkpoint-1", "checkpoint-2").replace("encrypted-state", "new-state")
        val second = persist(parse(response(laterItem, ANSWER)), "second")
        val history = listOf(text("old")) + first + text("middle") + second
        val input = mapper.toCreateParams(MODEL, false, request(history)).input().get().asResponse()
        assertEquals(2, input.size)
        assertEquals(Json.parseToJsonElement(laterItem), Json.parseToJsonElement(jsonMapper().writeValueAsString(input.first())))
        assertEquals(2, history.count { MessageContextMetadata.from(it).hasCompaction })
    }

    @Test
    fun `checkpoint-only output continues the agent and normal final output still completes it`() {
        assertEquals(AiStepOutcome.CONTINUE, parse(response(COMPACT)).outcome)
        assertEquals(AiStepOutcome.COMPLETE, parse(response(COMPACT, ANSWER)).outcome)
        assertEquals(AiStepOutcome.TOOL_CALLS, parse(response(COMPACT, CALL)).outcome)
        assertEquals(AiStepOutcome.INCOMPLETE, parse(response(COMPACT).replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")).outcome)
    }

    @Test
    fun `invalid checkpoint and wrong replay scope fail instead of silently dropping context`() {
        assertFailsWith<IllegalStateException> { parse(response(COMPACT.replace("encrypted-state", ""))) }
        val generated = persist(parse(response(COMPACT)))
        val other = OpenAiResponsesMessageMapper("other-connection", "configuration", MODEL)
        assertFailsWith<IllegalStateException> { other.toCreateParams(MODEL, false, request(generated)) }
        val checkpoint = generated.single().content.single() as Compaction
        val broken = generated.single().copy(content = listOf(checkpoint.copy(payload = Compaction.Payload.OpaqueProviderState(JsonObject(emptyMap())))))
        assertFailsWith<IllegalStateException> { mapper.toCreateParams(MODEL, false, request(listOf(broken))) }
    }

    @Test
    fun `gateway HTTP request enables compaction and next tool turn sends the persisted checkpoint`() = runBlocking {
        SdkHttpTestServer(response(COMPACT, CALL), response(ANSWER)).use { server ->
            val provider = RoutingAiRuntimeProvider(listOf(OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources))))
            val runtime = provider.getRuntime(resolved(connection("${server.origin}/api/llm")), null)
            val archive = (0 until 20).map { text("old-$it", "ARCHIVE_MARKER_$it") }
            val first = runtime.call(request(archive))
            assertEquals(AiStepOutcome.TOOL_CALLS, first.outcome)
            val result = runtime.call(request(archive + persist(first) + toolResult()))
            assertEquals(AiStepOutcome.COMPLETE, result.outcome)
            assertEquals(2, server.requests.size)
            server.requests.forEach { recorded ->
                assertEquals("/api/llm/responses", recorded.target)
                assertEquals("Bearer $TOKEN", recorded.headers["authorization"]?.single())
                val body = Json.parseToJsonElement(recorded.body).jsonObject
                assertEquals(JsonPrimitive(false), body["store"])
                assertEquals(Json.parseToJsonElement("""[{"type":"compaction","compact_threshold":7000}]"""), body["context_management"])
            }
            val nextInput = Json.parseToJsonElement(server.requests.last().body).jsonObject.getValue("input").jsonArray
            assertEquals(listOf("compaction", "function_call", "function_call_output"), nextInput.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            assertEquals(Json.parseToJsonElement(COMPACT), nextInput.first())
            assertFalse(server.requests.last().body.contains("ARCHIVE_MARKER"))
        }
    }

    private fun parse(body: String) = mapper.toRuntimeResponse(jsonMapper().readValue(body, Response::class.java), AiModelConfiguration.AssistantResponseFormat.TEXT)

    private fun request(messages: List<Message>) = AiRuntimeRequest(
        systemPrompts = listOf("Continue the task."), messages = messages,
        options = AiRuntimeOptions(maxOutputTokens = 128, autoCompactionThresholdTokens = 7000,
            toolContext = mapOf("threadId" to "thread")),
    )

    private fun persist(response: AiRuntimeResponse, prefix: String = "generated") = response.messages.mapIndexed { index, message ->
        text("$prefix-$index").copy(role = Message.Role.ASSISTANT, content = message.content,
            providerMetadata = JsonObject((response.providerMetadata + message.metadata + (AI_REPLAY_THREAD_METADATA_KEY to "thread")).mapValues { (_, value) ->
                value as? JsonElement ?: JsonPrimitive(value.toString())
            }))
    }

    private fun text(id: String, value: String = id) = Message(Message.Id(id), Conversation.Id("conversation"),
        role = Message.Role.USER, content = listOf(ContentItem.UserMessage(value)), createdAt = Instant.fromEpochMilliseconds(0))

    private fun toolResult() = text("result").copy(content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("tool-1"), "lookup", listOf(ContentItem.ToolResult.Data.Text("found")))))

    private fun connection(baseUrl: String) = AiConnection.OpenAiApi(AiConnection.Id(CONNECTION), "Gateway Responses", enabled = true,
        baseUrl = baseUrl, apiKey = SecretRef.Inline(TOKEN), httpSettings = AiHttpSettings(noProxy = listOf("*")))

    private fun resolved(connection: AiConnection) = ResolvedAiRuntime(connection,
        AiModelConfiguration(AiModelConfiguration.Id("configuration"), connection.id, MODEL, "Gateway model",
            defaultParameters = AiModelConfiguration.DefaultParameters(timeoutSeconds = 10)),
        AiModelSpec(MODEL, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(10_000, 1000, AiModelSpec.AutoCompaction.Percent(70)))))

    private object Sources : SettingsProvider {
        override val userProfile: UserProfile get() = error("Not used")
        override val userDeviceSettings: UserDeviceSettings get() = error("Not used")
        override val mode: AppMode get() = error("Not used")
        override val homeDirectory: String get() = error("Not used")
        override fun resolveSecret(secretRef: SecretRef?): String = TOKEN
    }

    private companion object {
        const val CONNECTION = "gateway"
        const val MODEL = "account/exact-model"
        const val TOKEN = "synthetic-compaction-token"
        const val COMPACT = """{"type":"compaction","id":"checkpoint-1","encrypted_content":"encrypted-state","future_field":"preserved"}"""
        const val CALL = """{"type":"function_call","id":"call-item","call_id":"tool-1","name":"lookup","arguments":"{}","status":"completed"}"""
        const val ANSWER = """{"type":"message","id":"answer","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done","annotations":[]}]}"""
        fun response(vararg output: String) = """{"id":"response","object":"response","created_at":1,"model":"$MODEL","status":"completed","output":[${output.joinToString(",")}],"usage":{"input_tokens":8000,"input_tokens_details":{"cached_tokens":0},"output_tokens":40,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":8040}}"""
    }
}
