package com.gromozeka.infrastructure.ai.anthropic

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiStepOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import kotlin.time.Instant
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicSdkMessageMapperTest {

    @Test
    fun `preserves signed empty and redacted thinking and content order through a tool turn`() {
        val mapper = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API, "anthropic-test", "claude-opus-5")
        val native = com.anthropic.core.jsonMapper().readValue("""{
            "id":"msg-1","model":"claude-opus-5-snapshot","stop_reason":"tool_use",
            "usage":{"input_tokens":10,"output_tokens":20},
            "content":[
                {"type":"thinking","thinking":"","signature":"signed-empty"},
                {"type":"text","text":"I will check."},
                {"type":"tool_use","id":"call-1","name":"check","input":{}},
                {"type":"text","text":"And compare the results."},
                {"type":"redacted_thinking","data":"opaque-redacted"}
            ]
        }""", com.anthropic.models.messages.Message::class.java)
        val response = mapper.toRuntimeResponse(native, AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA)
        assertEquals(AiStepOutcome.TOOL_CALLS, response.outcome)
        val thinking = response.messages.single().content.filterIsInstance<Conversation.Message.ContentItem.Thinking>()
        assertTrue(thinking.all { it.isVisible })
        assertEquals("signed-empty", thinking.first().signature)
        assertEquals(Conversation.Message.ContentItem.Thinking.Kind.REDACTED, thinking.last().kind)
        val assistant = requestWithoutJsonSchema().messages.single().copy(
            role = Conversation.Message.Role.ASSISTANT,
            content = response.messages.single().content,
            providerMetadata = JsonObject(response.messages.single().metadata.mapValues { (_, value) ->
                value as? JsonElement ?: JsonPrimitive(value.toString())
            }),
        )
        val replay = mapper.toCreateParams("claude-opus-5", requestWithoutJsonSchema().copy(messages = listOf(assistant)))
            .messages().single().content().asBlockParams()
        val json = kotlinx.serialization.json.Json
        assertEquals(
            json.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(native.content())),
            json.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(replay)),
        )
        val foreign = mapper.toCreateParams("claude-other", requestWithoutJsonSchema().copy(messages = listOf(assistant)))
            .messages().single().content().asBlockParams()
        assertFalse(foreign.any { it.isThinking() || it.isRedactedThinking() })
    }

    @Test
    fun `distinguishes provider pause truncation refusal and completion`() {
        val mapper = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
        val outcomes = mapOf(
            "pause_turn" to AiStepOutcome.CONTINUE,
            "end_turn" to AiStepOutcome.COMPLETE,
            "max_tokens" to AiStepOutcome.INCOMPLETE,
            "model_context_window_exceeded" to AiStepOutcome.INCOMPLETE,
            "refusal" to AiStepOutcome.REFUSED,
            "unknown" to AiStepOutcome.FAILED,
        )
        outcomes.forEach { (reason, outcome) ->
            val native = com.anthropic.core.jsonMapper().readValue("""{
                "id":"msg-1","model":"claude-opus-5","stop_reason":"$reason",
                "usage":{"input_tokens":1,"output_tokens":1},"content":[{"type":"text","text":"Partial or final text"}]
            }""", com.anthropic.models.messages.Message::class.java)
            assertEquals(outcome, mapper.toRuntimeResponse(native, AiModelConfiguration.AssistantResponseFormat.TEXT).outcome)
        }
    }

    @Test
    fun bedrockRejectsNativeJsonSchemaOutputFormat() {
        val error = assertFailsWith<IllegalArgumentException> {
            AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_BEDROCK)
                .toCreateParams("anthropic.claude-sonnet-4-20250514-v1:0", requestWithJsonSchema())
        }

        assertTrue("does not support Anthropic native structured output" in error.message.orEmpty())
    }

    @Test
    fun directAnthropicSendsNativeJsonSchemaOutputFormat() {
        val params = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
            .toCreateParams("claude-sonnet-4-20250514", requestWithJsonSchema())

        assertFalse(params.outputConfig().isEmpty)
        assertFalse(params.outputConfig().get().format().isEmpty)
    }

    @Test
    fun directAnthropicCachesStableSystemPromptPrefix() {
        val params = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
            .toCreateParams("claude-sonnet-4-20250514", requestWithJsonSchema())

        val systemBlocks = params.system().orElseThrow().asTextBlockParams()
        assertTrue(params.cacheControl().isPresent)
        assertTrue(systemBlocks.single().cacheControl().isPresent)
    }

    @Test
    fun bedrockLeavesPromptCachingToTheProvider() {
        val params = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_BEDROCK)
            .toCreateParams("anthropic.claude-sonnet-4-20250514-v1:0", requestWithoutJsonSchema())

        assertTrue(params.system().orElseThrow().isString())
        assertFalse(params.cacheControl().isPresent)
    }

    @Test
    fun opus5AcceptsAdaptiveThinkingAtMaximumEffort() {
        val params = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
            .toCreateParams(
                "claude-opus-5",
                requestWithoutJsonSchema(
                    AiReasoningConfig(
                        mode = AiReasoningMode.ADAPTIVE,
                        effort = AiReasoningEffort.MAX,
                    )
                ),
            )

        assertTrue(params.thinking().orElseThrow().isAdaptive())
        assertEquals("max", params.outputConfig().orElseThrow().effort().orElseThrow().asString())
    }

    @Test
    fun opus5RejectsManualThinkingBudget() {
        val error = assertFailsWith<IllegalArgumentException> {
            AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
                .toCreateParams(
                    "claude-opus-5",
                    requestWithoutJsonSchema(
                        AiReasoningConfig(
                            mode = AiReasoningMode.TOKEN_BUDGET,
                            budgetTokens = 16_000,
                        )
                    ),
                )
        }

        assertTrue("does not support manual thinking token budgets" in error.message.orEmpty())
    }

    @Test
    fun opus5RejectsDisabledThinkingAtExtendedEfforts() {
        listOf(AiReasoningEffort.XHIGH, AiReasoningEffort.MAX).forEach { effort ->
            val error = assertFailsWith<IllegalArgumentException> {
                AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
                    .toCreateParams(
                        "claude-opus-5",
                        requestWithoutJsonSchema(
                            AiReasoningConfig(
                                mode = AiReasoningMode.DISABLED,
                                effort = effort,
                            )
                        ),
                    )
            }

            assertTrue("cannot combine disabled thinking" in error.message.orEmpty())
        }
    }

    @Test
    fun `maps binary screenshot tool result to an Anthropic image block`() {
        val params = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
            .toCreateParams(
                "claude-sonnet-4-20250514",
                AiRuntimeRequest(
                    systemPrompts = emptyList(),
                    messages = listOf(imageToolResultMessage()),
                ),
            )

        val toolResult = params.messages().single()
            .content().asBlockParams().single().asToolResult()
        val image = toolResult.content().orElseThrow().asBlocks().single()

        assertTrue(image.isImage())
        assertEquals("call-screenshot", toolResult.toolUseId())
    }

    @Test
    fun `maps binary document tool result to an Anthropic document block`() {
        val params = AnthropicSdkMessageMapper(AiConnection.Kind.ANTHROPIC_API)
            .toCreateParams(
                "claude-sonnet-4-20250514",
                AiRuntimeRequest(
                    systemPrompts = emptyList(),
                    messages = listOf(documentToolResultMessage()),
                ),
            )

        val toolResult = params.messages().single()
            .content().asBlockParams().single().asToolResult()
        val document = toolResult.content().orElseThrow().asBlocks().single()

        assertTrue(document.isDocument())
        assertEquals("call-document", toolResult.toolUseId())
    }

    private fun requestWithJsonSchema(): AiRuntimeRequest =
        AiRuntimeRequest(
            systemPrompts = listOf("Return JSON only."),
            messages = listOf(userMessage("Extract the answer.")),
            options = AiRuntimeOptions(
                responseFormat = AiResponseFormat.JsonSchema(
                    name = "answer_schema",
                    schema = buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "answer",
                                    buildJsonObject {
                                        put("type", "string")
                                    }
                                )
                            }
                        )
                        put("required", buildJsonArray { add("answer") })
                        put("additionalProperties", false)
                    }
                )
            )
        )

    private fun requestWithoutJsonSchema(reasoning: AiReasoningConfig? = null): AiRuntimeRequest =
        AiRuntimeRequest(
            systemPrompts = listOf("Return a concise answer."),
            messages = listOf(userMessage("Extract the answer.")),
            options = AiRuntimeOptions(reasoning = reasoning),
        )

    private fun userMessage(text: String): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("message-1"),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun imageToolResultMessage(): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("tool-result-1"),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-screenshot"),
                    toolName = "grz_capture_screenshot",
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                            data = "AQID",
                            mediaType = Conversation.Message.MediaType.parse("image/png"),
                            fileName = "worker-screen.png",
                        )
                    ),
                    isError = false,
                )
            ),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun documentToolResultMessage(): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("tool-result-document"),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-document"),
                    toolName = "grz_read_document",
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                            data = "AQID",
                            mediaType = Conversation.Message.MediaType.parse("application/pdf"),
                            fileName = "report.pdf",
                        )
                    ),
                    isError = false,
                )
            ),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
