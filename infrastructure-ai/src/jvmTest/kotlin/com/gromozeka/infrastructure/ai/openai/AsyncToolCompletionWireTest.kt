package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.infrastructure.ai.anthropic.AnthropicSdkMessageMapper
import com.gromozeka.infrastructure.ai.runtime.ModelGenerationPolicy
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class AsyncToolCompletionWireTest {
    @Test fun `Chat API preserves normal tool response format and places Runtime provenance on the synthetic assistant call`() {
        for (kind in listOf(AiConnection.Kind.OPENAI_API, AiConnection.Kind.OPENAI_COMPATIBLE)) {
            for (error in listOf(false, true)) {
                val fixture = history(error)
                val mapper = OpenAiSdkMessageMapper(kind)
                fun body(messages: List<Conversation.Message>) = Json.parseToJsonElement(
                    com.openai.core.jsonMapper().writeValueAsString(mapper.toCreateParams("model", request(messages))._body())
                ).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
                val ordinary = body(fixture.synchronous).last()
                val asynchronous = body(fixture.asynchronous)
                assertEquals(listOf("assistant", "tool", "assistant", "tool"), asynchronous.map { it.getValue("role").jsonPrimitive.content })
                assertEquals(fixture.source.value, asynchronous[1].getValue("tool_call_id").jsonPrimitive.content)
                assertEquals(fixture.completion.value, asynchronous.last().getValue("tool_call_id").jsonPrimitive.content)
                assertEquals(JsonObject(ordinary - "tool_call_id"), JsonObject(asynchronous.last() - "tool_call_id"))
                assertTrue(asynchronous[2].getValue("content").jsonPrimitive.content.contains("operation"))
                assertTrue(asynchronous[2].getValue("content").jsonPrimitive.content.contains(fixture.source.value))
                assertFalse(asynchronous.toString().contains("runtime_notification"))
            }
        }
    }

    @Test fun `Responses API uses original receipt pair and independent native completion output with identical payload`() {
        for (error in listOf(false, true)) {
            val fixture = history(error)
            val mapper = OpenAiResponsesMessageMapper("connection", "configuration", "model")
            fun input(messages: List<Conversation.Message>) = Json.parseToJsonElement(
                com.openai.core.jsonMapper().writeValueAsString(mapper.toCreateParams("model", false, request(messages))._body())
            ).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
            val ordinary = input(fixture.synchronous).last()
            val asynchronous = input(fixture.asynchronous)
            val calls = asynchronous.filter { it["type"] == JsonPrimitive("function_call") }
            val outputs = asynchronous.filter { it["type"] == JsonPrimitive("function_call_output") }
            assertEquals(listOf(fixture.source.value, fixture.completion.value), calls.map { it.getValue("call_id").jsonPrimitive.content })
            assertEquals(listOf(fixture.source.value, fixture.completion.value), outputs.map { it.getValue("call_id").jsonPrimitive.content })
            assertEquals(JsonObject(ordinary - "call_id"), JsonObject(outputs.last() - "call_id"))
            val notice = asynchronous.single { it["role"] == JsonPrimitive("assistant") }
            assertTrue(notice.getValue("content").toString().contains("operation"))
            assertTrue(notice.getValue("content").toString().contains(fixture.source.value))
            assertTrue(asynchronous.none { it["role"] == JsonPrimitive("user") })
        }
    }

    @Test fun `Anthropic API and Bedrock retain ordinary tool result blocks rather than a Runtime text envelope`() {
        val generation = ModelGenerationPolicy(limits = AiModelSpec.Limits.TextGeneration(200_000, 128_000))
        for (kind in listOf(AiConnection.Kind.ANTHROPIC_API, AiConnection.Kind.ANTHROPIC_BEDROCK)) {
            for (error in listOf(false, true)) {
                val fixture = history(error)
                val mapper = AnthropicSdkMessageMapper(kind, null, null, generation)
                fun messages(input: List<Conversation.Message>) = Json.parseToJsonElement(
                    com.anthropic.core.jsonMapper().writeValueAsString(mapper.toCreateParams("model", request(input)).messages())
                ).jsonArray.map { it.jsonObject }
                val ordinary = messages(fixture.synchronous).last().getValue("content").jsonArray.single().jsonObject
                val asynchronous = messages(fixture.asynchronous)
                val actual = asynchronous.last().getValue("content").jsonArray.single().jsonObject
                assertEquals("tool_result", actual.getValue("type").jsonPrimitive.content)
                assertEquals(fixture.completion.value, actual.getValue("tool_use_id").jsonPrimitive.content)
                assertEquals(JsonObject(ordinary - "tool_use_id"), JsonObject(actual - "tool_use_id"))
                val syntheticCall = asynchronous[2]
                assertEquals("assistant", syntheticCall.getValue("role").jsonPrimitive.content)
                val blocks = syntheticCall.getValue("content").jsonArray.map { it.jsonObject }
                assertEquals(listOf("text", "tool_use"), blocks.map { it.getValue("type").jsonPrimitive.content })
                assertTrue(blocks.first().getValue("text").jsonPrimitive.content.contains(fixture.source.value))
                assertFalse(asynchronous.toString().contains("runtime_notification"))
            }
        }
    }

    @Test fun `adaptive Anthropic completion remains a native tool turn after new human input`() {
        val generation = ModelGenerationPolicy(limits = AiModelSpec.Limits.TextGeneration(200_000, 128_000))
        for (kind in listOf(AiConnection.Kind.ANTHROPIC_API, AiConnection.Kind.ANTHROPIC_BEDROCK)) {
            val fixture = history(false)
            val human = fixture.asynchronous.first().copy(id = Conversation.Message.Id("new-human"),
                role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage("new human input")))
            val input = fixture.asynchronous.take(2) + human + fixture.asynchronous.drop(2)
            val request = request(input).let { it.copy(options = it.options.copy(
                reasoning = AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE, effort = AiReasoningEffort.HIGH))) }
            val params = AnthropicSdkMessageMapper(kind, null, null, generation).toCreateParams("model", request)
            assertTrue(params.thinking().orElseThrow().isAdaptive())
            val body = Json.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(params._body())).jsonObject
            assertEquals(JsonPrimitive("adaptive"), body.getValue("thinking").jsonObject["type"])
            assertEquals(JsonPrimitive("high"), body.getValue("output_config").jsonObject["effort"])
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            val humanIndex = messages.indexOfFirst { it.toString().contains("new human input") }
            val completionIndex = messages.indexOfLast { it["role"] == JsonPrimitive("assistant") }
            assertTrue(humanIndex >= 0 && completionIndex > humanIndex)
            val blocks = messages[completionIndex].getValue("content").jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "tool_use"), blocks.map { it.getValue("type").jsonPrimitive.content })
            assertEquals(JsonPrimitive(fixture.completion.value), blocks.last()["id"])
            val result = messages.last().getValue("content").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("tool_result"), result["type"])
            assertEquals(JsonPrimitive(fixture.completion.value), result["tool_use_id"])
            assertFalse(body.toString().contains("runtime_notification"))
        }
    }

    private data class History(
        val source: ContentItem.ToolCall.Id,
        val completion: ContentItem.ToolCall.Id,
        val synchronous: List<Conversation.Message>,
        val asynchronous: List<Conversation.Message>,
    )

    private fun history(error: Boolean): History {
        val source = ContentItem.ToolCall.Id("source-call")
        val completion = syntheticToolCallId("async-tool:operation:completion")
        val call = Conversation.Message(
            Conversation.Message.Id("source"), Conversation.Id("conversation"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.ToolCall(source, ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))),
            createdAt = Instant.fromEpochSeconds(1),
        )
        val originalResult = ContentItem.ToolResult(source, "tool", listOf(ContentItem.ToolResult.Data.Text("{\"answer\":42,\"status\":\"WORKING\"}")), isError = error)
        val result = call.copy(id = Conversation.Message.Id("result"), role = Conversation.Message.Role.USER, content = listOf(originalResult))
        val receipt = result.copy(id = Conversation.Message.Id("receipt"), content = listOf(originalResult.copy(isError = false,
            result = listOf(ContentItem.ToolResult.Data.Text("{\"status\":\"ACCEPTED\",\"operation_id\":\"operation\"}")))))
        val metadata = buildJsonObject {
            putSyntheticMessageMetadata(SyntheticMessageKind.ASYNC_TOOL_COMPLETION, BackgroundActivityMessageSource(source, AgentDefinition.Id("agent")))
            put("asyncToolOperationId", "operation")
        }
        val completionMessage = call.copy(id = Conversation.Message.Id("completion-call"), author = Conversation.Message.Author.Runtime(),
            content = listOf(ContentItem.ToolCall(completion, ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))),
            instructions = listOf(Conversation.Message.Instruction.RuntimeObservation("Runtime completion for operation; source ${source.value}; not a new execution.")),
            providerMetadata = metadata)
        val delivered = result.copy(id = Conversation.Message.Id("completion-result"), author = Conversation.Message.Author.Runtime(),
            content = listOf(originalResult.copy(toolUseId = completion)), providerMetadata = metadata)
        return History(source, completion, listOf(call, result), listOf(call, receipt, completionMessage, delivered))
    }

    private fun request(messages: List<Conversation.Message>) = AiRuntimeRequest(emptyList(), messages,
        options = AiRuntimeOptions(maxOutputTokens = 4096))
}
