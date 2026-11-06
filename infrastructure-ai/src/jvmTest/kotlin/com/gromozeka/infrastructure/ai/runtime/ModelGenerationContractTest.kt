package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.ResolvedAiRuntime
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class ModelGenerationContractTest {
    @Test
    fun `chat token field is explicitly selected and never guessed from the model ID`() {
        for (field in AiModelBehavior.ChatCompletionsTokenLimit.entries) {
            val mapper = chat(ModelGenerationPolicy(AiModelBehavior(chatCompletionsTokenLimit = field)))
            for (limit in listOf(null, 1234)) {
                val body = chatBody(mapper, request().copy(options = AiRuntimeOptions(maxOutputTokens = limit)))
                assertEquals(MODEL, body.getValue("model").jsonPrimitive.content)
                val tokenFields = body.keys.intersect(setOf("max_tokens", "max_completion_tokens"))
                val expected = when (field) {
                    AiModelBehavior.ChatCompletionsTokenLimit.MAX_COMPLETION_TOKENS -> "max_completion_tokens"
                    AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS -> "max_tokens"
                    AiModelBehavior.ChatCompletionsTokenLimit.OMIT -> null
                }
                assertEquals(expected?.let(::setOf).orEmpty(), tokenFields)
                expected?.let { assertEquals(limit ?: 8192, body.getValue(it).jsonPrimitive.int) }
            }
        }
    }

    @Test
    fun `OpenAI disabled reasoning sends none despite a retained effort or display`() {
        for (effort in listOf(null) + AiReasoningEffort.entries) {
            val request = request(AiReasoningConfig(AiReasoningMode.DISABLED, effort, AiReasoningDisplay.SUMMARIZED))
            assertEquals(JsonPrimitive("none"), chatBody(chat(), request)["reasoning_effort"])
            val body = responsesBody(request)
            assertEquals(buildJsonObject { put("effort", "none") }, body["reasoning"])
        }
        assertFalse("reasoning_effort" in chatBody(chat(), request()))
        assertFalse("reasoning" in responsesBody(request()))
    }

    @Test
    fun `OpenAI efforts retain their exact values including max and reject unsupported budget or ambiguous adaptive mode`() {
        for (effort in AiReasoningEffort.entries) {
            val request = request(AiReasoningConfig(AiReasoningMode.ADAPTIVE, effort))
            assertEquals(JsonPrimitive(effort.name.lowercase()), chatBody(chat(), request)["reasoning_effort"])
            assertEquals(JsonPrimitive(effort.name.lowercase()), responsesBody(request).getValue("reasoning").jsonObject["effort"])
        }
        for (config in listOf(AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE),
            AiReasoningConfig(mode = AiReasoningMode.TOKEN_BUDGET, budgetTokens = 1024), AiReasoningConfig(budgetTokens = 1024))) {
            assertFailsWith<IllegalArgumentException> { chatBody(chat(), request(config)) }
            assertFailsWith<IllegalArgumentException> { responsesBody(request(config)) }
        }
    }

    @Test
    fun `declared always-on model rejects off instead of silently enabling reasoning`() {
        val policy = ModelGenerationPolicy(reasoningCapabilities = AiReasoningCapabilities(modes = setOf(AiReasoningMode.ADAPTIVE)))
        val request = request(AiReasoningConfig(mode = AiReasoningMode.DISABLED))
        assertFailsWith<IllegalArgumentException> { chatBody(chat(policy), request) }
        assertFailsWith<IllegalArgumentException> { responsesBody(request, policy) }
        assertFailsWith<IllegalArgumentException> { anthropic(policy).toCreateParams(MODEL, request) }
    }

    @Test
    fun `prefixed Anthropic model keeps explicit adaptive beta and forced-choice rules after copying the spec`() {
        val original = spec(AiProvider.ANTHROPIC).copy(
            id = "claude-opus-5-5",
            reasoning = AiReasoningCapabilities(modes = setOf(AiReasoningMode.ADAPTIVE)),
            behavior = AiModelBehavior(defaultReasoningMode = AiReasoningMode.ADAPTIVE,
                supportsForcedToolChoice = false, anthropicThinkingPrefixDrop = true),
        )
        for (id in listOf(original.id, MODEL)) {
            val mapper = anthropic(ModelGenerationPolicy(original.copy(id = id)))
            val params = mapper.toCreateParams(id, request())
            val body = anthropicBody(params._body())
            assertEquals(JsonPrimitive(id), body["model"])
            assertEquals(JsonPrimitive("adaptive"), body.getValue("thinking").jsonObject["type"])
            assertEquals(JsonPrimitive("drop_block"), body.getValue("thinking").jsonObject.getValue("block_binding").jsonObject["prefix_mismatch_behavior"])
            assertEquals(listOf("thinking-binding-controls-2026-08-01"), params._headers().values("anthropic-beta"))
            assertFailsWith<IllegalArgumentException> { mapper.toCreateParams(id, request(AiReasoningConfig(mode = AiReasoningMode.DISABLED))) }
            assertFailsWith<IllegalArgumentException> { mapper.toCreateParams(id, request().copy(options = AiRuntimeOptions(toolChoice = AiToolChoice.RequiredAny))) }
        }
        // The literal native name alone no longer opts a model into special behavior.
        assertFalse("thinking" in anthropicBody(anthropic().toCreateParams(original.id, request())._body()))
    }

    @Test
    fun `Anthropic disabled thinking retains independent effort but enforces the declared maximum`() {
        val policy = ModelGenerationPolicy(AiModelBehavior(maximumDisabledReasoningEffort = AiReasoningEffort.HIGH))
        val mapper = anthropic(policy)
        val body = anthropicBody(mapper.toCreateParams(MODEL, request(AiReasoningConfig(AiReasoningMode.DISABLED, AiReasoningEffort.HIGH)))._body())
        assertEquals(buildJsonObject { put("type", "disabled") }, body["thinking"])
        assertEquals(buildJsonObject { put("effort", "high") }, body["output_config"])
        assertFailsWith<IllegalArgumentException> {
            mapper.toCreateParams(MODEL, request(AiReasoningConfig(AiReasoningMode.DISABLED, AiReasoningEffort.XHIGH)))
        }
    }

    @Test
    fun `Chat Completions supports explicitly selected Anthropic thinking without changing API or model ID`() {
        val behavior = AiModelBehavior(chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS,
            chatCompletionsReasoning = AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING)
        val mapper = chat(ModelGenerationPolicy(behavior))
        val body = chatBody(mapper, request(AiReasoningConfig(mode = AiReasoningMode.TOKEN_BUDGET, budgetTokens = 1024)))
        assertEquals(JsonPrimitive("enabled"), body.getValue("thinking").jsonObject["type"])
        assertEquals(JsonPrimitive(1024), body.getValue("thinking").jsonObject["budget_tokens"])
        assertEquals(JsonPrimitive(4096), body["max_tokens"])
        assertFalse("reasoning_effort" in body)
        val off = chatBody(mapper, request(AiReasoningConfig(AiReasoningMode.DISABLED, AiReasoningEffort.HIGH)))
        assertEquals(buildJsonObject { put("type", "disabled") }, off["thinking"])
        assertEquals(buildJsonObject { put("effort", "high") }, off["output_config"])
        assertFailsWith<IllegalArgumentException> {
            chatBody(mapper, request(AiReasoningConfig(mode = AiReasoningMode.TOKEN_BUDGET, budgetTokens = 4096)))
        }
    }

    @Test
    fun `tool minimum is enforced by every mapper without silently raising effort or enabling off`() {
        val policy = ModelGenerationPolicy(AiModelBehavior(minimumToolReasoningEffort = AiReasoningEffort.MEDIUM))
        for (config in listOf(null, AiReasoningConfig(effort = AiReasoningEffort.LOW),
            AiReasoningConfig(AiReasoningMode.DISABLED, AiReasoningEffort.HIGH))) {
            val withTools = request(config).copy(tools = listOf(tool))
            assertFailsWith<IllegalArgumentException> { chatBody(chat(policy), withTools) }
            assertFailsWith<IllegalArgumentException> { responsesBody(withTools, policy) }
            assertFailsWith<IllegalArgumentException> { anthropic(policy).toCreateParams(MODEL, withTools) }
            // Explicitly disabled tool use does not impose a tool-only minimum.
            val noTools = withTools.copy(options = withTools.options.copy(toolChoice = AiToolChoice.None))
            chatBody(chat(policy), noTools)
            responsesBody(noTools, policy)
            anthropic(policy).toCreateParams(MODEL, noTools)
        }
        val valid = request(AiReasoningConfig(effort = AiReasoningEffort.MEDIUM)).copy(tools = listOf(tool))
        chatBody(chat(policy), valid)
        responsesBody(valid, policy)
        anthropic(policy).toCreateParams(MODEL, valid)
    }

    @Test
    fun `routing supplies the complete spec and model defaults to the backend`() = runBlocking {
        val connection = AiConnection.OpenAiCompatible(AiConnection.Id("connection"), "Synthetic", baseUrl = "http://localhost/unused")
        val configuration = AiModelConfiguration(AiModelConfiguration.Id("config"), connection.id, MODEL, "Synthetic",
            defaultParameters = AiModelConfiguration.DefaultParameters(reasoning = AiReasoningConfig(mode = AiReasoningMode.DISABLED)))
        val spec = spec(connection.kind.provider).copy(behavior = AiModelBehavior(chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.OMIT))
        val resolved = ResolvedAiRuntime(connection, configuration, spec)
        var observed: ResolvedAiRuntime? = null
        val backend = object : AiRuntimeBackend {
            override fun supports(connectionKind: AiConnection.Kind) = true
            override fun createRuntime(runtime: ResolvedAiRuntime, workspaceRootPath: String?): AiRuntime {
                observed = runtime
                assertEquals("/synthetic", workspaceRootPath)
                return object : AiRuntime {
                    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                        val body = chatBody(chat(ModelGenerationPolicy(runtime.modelSpec)), request)
                        assertFalse("max_completion_tokens" in body)
                        assertEquals(JsonPrimitive("none"), body["reasoning_effort"])
                        return AiRuntimeResponse(messages = emptyList(), outcome = AiStepOutcome.COMPLETE)
                    }
                    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("Not used")
                }
            }
        }
        RoutingAiRuntimeProvider(listOf(backend)).getRuntime(resolved, "/synthetic").call(request())
        assertSame(resolved, observed)
    }

    @Test
    fun `real SDK HTTP requests carry selected token field and explicit off`() {
        val response = """{"id":"fixture","object":"chat.completion","created":1,"model":"$MODEL","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""
        SdkHttpTestServer(response).use { server ->
            val client = OpenAIOkHttpClient.builder().baseUrl("${server.origin}/api/llm")
                .apiKey("synthetic-policy-token").proxy(Proxy.NO_PROXY).maxRetries(0).timeout(Duration.ofSeconds(5)).build()
            try {
                val mapper = chat(ModelGenerationPolicy(AiModelBehavior(chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS)))
                client.chat().completions().create(mapper.toCreateParams(MODEL, request(AiReasoningConfig(AiReasoningMode.DISABLED, AiReasoningEffort.HIGH))))
            } finally { client.close() }
            val sent = server.requests.single()
            assertEquals("/api/llm/chat/completions", sent.target)
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals(JsonPrimitive(MODEL), body["model"])
            assertEquals(JsonPrimitive(4096), body["max_tokens"])
            assertEquals(JsonPrimitive("none"), body["reasoning_effort"])
            assertFalse("max_completion_tokens" in body)
        }
    }

    private fun chat(policy: ModelGenerationPolicy = ModelGenerationPolicy()) = OpenAiSdkMessageMapper(
        AiConnection.Kind.OPENAI_COMPATIBLE, "connection", MODEL, policy)
    private fun anthropic(policy: ModelGenerationPolicy = ModelGenerationPolicy()) = AnthropicSdkMessageMapper(
        AiConnection.Kind.ANTHROPIC_API, generation = policy)
    private fun chatBody(mapper: OpenAiSdkMessageMapper, request: AiRuntimeRequest) =
        Json.parseToJsonElement(com.openai.core.jsonMapper().writeValueAsString(mapper.toCreateParams(MODEL, request)._body())).jsonObject
    private fun responsesBody(request: AiRuntimeRequest, policy: ModelGenerationPolicy = ModelGenerationPolicy()) =
        Json.parseToJsonElement(com.openai.core.jsonMapper().writeValueAsString(
            OpenAiResponsesMessageMapper("connection", "config", MODEL, policy).toCreateParams(MODEL, false, request)._body())).jsonObject
    private fun anthropicBody(body: Any) = Json.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(body)).jsonObject
    private fun spec(provider: AiProvider) = AiModelSpec(MODEL, provider, setOf(AiModelCapability.TEXT_GENERATION, AiModelCapability.TOOL_CALLING),
        AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000, 8192)))
    private fun request(reasoning: AiReasoningConfig? = null) = AiRuntimeRequest(
        systemPrompts = listOf("Synthetic instruction"),
        messages = listOf(Conversation.Message(Conversation.Message.Id("message"), Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")),
            createdAt = Instant.fromEpochMilliseconds(0))),
        options = AiRuntimeOptions(maxOutputTokens = 4096, reasoning = reasoning),
    )
    private val tool = object : AiToolCallback {
        override val definition = AiToolDefinition("lookup", "Synthetic", """{"type":"object","properties":{}}""")
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Must not execute")
    }
    private companion object { const val MODEL = "gateway-account/prefixed-model" }
}
