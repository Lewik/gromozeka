package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.tool.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class OpenAiSubscriptionIncrementalReplayTest {
    private val mapper = OpenAiSubscriptionRequestMapper()
    private val responseMapper = OpenAiSubscriptionResponseMapper()
    private val now = Instant.parse("2026-11-08T00:00:00Z")
    private val conversationId = Conversation.Id("diagnostic")
    private val connectionId = "diagnostic-connection"
    private val format = AiModelConfiguration.AssistantResponseFormat.TEXT

    @Test
    fun `lite default function namespaces survive response persistence as strict continuations`() {
        for (namespace in listOf(null, JsonNull, JsonPrimitive("functions"))) {
            assertToolContinuation(Scenario(lite = true, namespace = namespace), JsonPrimitive("functions"))
        }
    }

    @Test
    fun `ordinary response default function namespaces survive persistence as strict continuations`() {
        for (namespace in listOf(JsonPrimitive("functions"), JsonNull, null)) {
            assertToolContinuation(Scenario(lite = false, namespace = namespace), null)
        }
    }

    @Test
    fun `provider-managed web namespace is preserved`() {
        assertToolContinuation(Scenario(lite = true, namespace = JsonPrimitive("web")), JsonPrimitive("web"))
    }

    @Test
    fun `unsupported namespace is not converted into a default function`() {
        for (lite in listOf(false, true)) {
            assertFailsWith<IllegalArgumentException> { Scenario(lite, JsonPrimitive("unsupported")) }
        }
    }

    @Test
    fun `changed request semantics never qualify as strict continuations`() {
        for (lite in listOf(false, true)) {
            val s = Scenario(lite, null)
            val edits = listOf(
                s.next.copy(tools = s.next.tools + tool("second_tool")),
                s.next.copy(systemPrompts = listOf("Changed synthetic instruction")),
                s.next.copy(options = s.next.options.copy(reasoning = AiReasoningConfig(effort = AiReasoningEffort.HIGH))),
                s.next.copy(messages = s.next.messages.mapIndexed { index, message ->
                    if (index == 0) message.copy(content = listOf(ContentItem.UserMessage("Changed original input"))) else message
                }),
                s.next.copy(messages = listOf(Conversation.Message(
                    Conversation.Message.Id("summary"), conversationId, role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(ContentItem.ContextCompactionResult(
                        payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("Synthetic compact"),
                        origin = ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                        coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                    )), createdAt = now,
                ))),
            )
            for (request in edits) {
                val plan = s.plan(request)
                assertEquals(OpenAiSubscriptionRequestMode.FULL, plan.mode)
                assertNull(plan.request.previousResponseId)
            }
        }
    }

    private fun assertToolContinuation(s: Scenario, namespace: JsonElement?) {
        val nextTransport = s.transport(s.next)
        assertEquals(s.expected, nextTransport.input.take(s.expected.size))
        assertEquals(namespace, s.expected.last()["namespace"])
        val plan = s.plan(s.next)
        assertEquals(OpenAiSubscriptionRequestMode.INCREMENTAL, plan.mode)
        assertEquals("resp-1", plan.request.previousResponseId)
        assertEquals(nextTransport.input.drop(s.expected.size), plan.request.input)
        assertEquals(listOf("function_call_output"), plan.request.input.map { it["type"]?.jsonPrimitive?.content })
        assertEquals(s.output.first(), s.expected[s.firstTransport.input.size])
    }

    private inner class Scenario(lite: Boolean, namespace: JsonElement?) {
        val profile = OpenAiSubscriptionModelProfile("gpt-6-astra", lite, true, listOf("low", "medium", "high", "xhigh", "max"), true, "low", true)
        val web = namespace == JsonPrimitive("web")
        val first = AiRuntimeRequest(listOf("Synthetic instruction"), listOf(Conversation.Message(
            Conversation.Message.Id("user"), conversationId, role = Conversation.Message.Role.USER,
            content = listOf(ContentItem.UserMessage("Use the synthetic tool")), createdAt = now,
        )), tools = listOf(tool("synthetic_tool")), options = AiRuntimeOptions(reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX)))
        val output = listOf(
            buildJsonObject {
                put("type", "reasoning"); put("id", "reasoning-1"); put("summary", JsonArray(emptyList()))
                put("encrypted_content", "synthetic-opaque-reasoning")
            },
            buildJsonObject {
                put("type", "function_call"); put("id", "function-1"); put("status", "completed")
                put("call_id", "call_1"); put("name", if (web) "run" else "synthetic_tool")
                put("arguments", "{ \"key\" : \"value\", \"n\" : 1 }")
                namespace?.let { put("namespace", it) }
            },
        )
        val firstTransport = transport(first)
        val expected = firstTransport.input + mapper.toReplayItems(output, format, profile)
        private val response = responseMapper.toRuntimeResponse(output,
            OpenAiSubscriptionCompletedResponse("resp-1", "completed"), "diagnostic", connectionId, "diagnostic-model", profile.slug, format)
        private val persisted = response.messages.mapIndexed { index, assistant ->
            val message = Conversation.Message(Conversation.Message.Id("assistant-$index"), conversationId,
                role = Conversation.Message.Role.ASSISTANT, content = assistant.content, createdAt = now,
                providerMetadata = JsonObject((response.providerMetadata + assistant.metadata).mapValues { (_, value) -> when (value) {
                    is JsonElement -> value
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    null -> JsonNull
                    else -> JsonPrimitive(value.toString())
                } }))
            Json.decodeFromString<Conversation.Message>(Json.encodeToString(message))
        }
        val next = first.copy(messages = first.messages + persisted + Conversation.Message(
            Conversation.Message.Id("result"), conversationId, role = Conversation.Message.Role.USER,
            content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("call_1"), if (web) "web.run" else "synthetic_tool",
                listOf(ContentItem.ToolResult.Data.Text("Synthetic result")))), createdAt = now,
        ))
        fun transport(request: AiRuntimeRequest): OpenAiSubscriptionResponsesRequest = mapper.toTransportRequest(
            mapper.toRequest(request, profile, "diagnostic", webSearchEnabled = web, connectionId = connectionId), profile)
        fun plan(request: AiRuntimeRequest): OpenAiSubscriptionRequestPlan {
            val state = OpenAiSubscriptionIncrementalState()
            state.record(mapper.buildTransportSignature(firstTransport), "resp-1", expected)
            val transport = transport(request)
            return state.plan(transport, mapper.buildTransportSignature(transport))
        }
    }

    private fun tool(name: String) = object : AiToolCallback {
        override val metadata = ServerToolMetadata
        override val definition = AiToolDefinition(name, "Synthetic tool", """{"type":"object","properties":{"key":{"type":"string"},"n":{"type":"number"}}}""")
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("No tool execution in mapper diagnostics")
    }
}
