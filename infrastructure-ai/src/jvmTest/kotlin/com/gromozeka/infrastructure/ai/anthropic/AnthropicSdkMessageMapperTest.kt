package com.gromozeka.infrastructure.ai.anthropic

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicSdkMessageMapperTest {

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

    private fun requestWithoutJsonSchema(): AiRuntimeRequest =
        AiRuntimeRequest(
            systemPrompts = listOf("Return a concise answer."),
            messages = listOf(userMessage("Extract the answer.")),
        )

    private fun userMessage(text: String): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("message-1"),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
