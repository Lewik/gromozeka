package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OpenAiSubscriptionRequestMapperTest {
    private val mapper = OpenAiSubscriptionRequestMapper()
    private val conversationId = Conversation.Id("conversation-test")
    private val createdAt = Instant.parse("2026-05-08T00:00:00Z")

    @Test
    fun keepsToolCallsFromCompactionAnchorSoFollowingToolResultsRemainMatched() {
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("call_keep")
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    assistantCompactionMessage(
                        id = "assistant-anchor",
                        toolCallId = toolCallId,
                    ),
                    userToolResultMessage(
                        id = "tool-result",
                        toolCallId = toolCallId,
                    ),
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val itemTypes = request.input.map { it.string("type") }
        assertEquals(listOf("compaction", "function_call", "function_call_output"), itemTypes)
        assertEquals("call_keep", request.input[1].string("call_id"))
        assertEquals("call_keep", request.input[2].string("call_id"))
    }

    @Test
    fun dropsFunctionCallOutputsThatDoNotHaveAMatchingFunctionCallInTheReplayWindow() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    assistantCompactionMessage(
                        id = "assistant-anchor",
                        toolCallId = null,
                    ),
                    userToolResultMessage(
                        id = "orphan-result",
                        toolCallId = Conversation.Message.ContentItem.ToolCall.Id("call_orphan"),
                    ),
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val itemTypes = request.input.map { it.string("type") }
        assertEquals(listOf("compaction"), itemTypes)
        assertFalse(request.input.any { it.string("type") == "function_call_output" })
    }

    @Test
    fun mapsResolvedAutoCompactionThresholdIntoContextManagement() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                options = AiRuntimeOptions(autoCompactionThresholdTokens = 945_000),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        val contextManagement = request.contextManagement?.single()
        assertEquals("compaction", contextManagement?.string("type"))
        assertEquals(945_000, contextManagement?.int("compact_threshold"))
    }

    @Test
    fun omitsContextManagementWhenAutoCompactionThresholdIsAbsent() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                options = AiRuntimeOptions(autoCompactionThresholdTokens = null),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertNull(request.contextManagement)
    }

    @Test
    fun omitsToolsWhenToolChoiceIsNone() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(toolChoice = AiToolChoice.None),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertEquals(emptyList(), request.tools)
        assertNull(request.toolChoice)
    }

    @Test
    fun includesToolsWhenToolChoiceAllowsTools() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(toolChoice = AiToolChoice.Auto),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertEquals(1, request.tools.size)
    }

    @Test
    fun mapsSystemMessagesAsDeveloperInputItemsForSubscriptionEndpoint() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(systemMessage(id = "system-note", text = "Runtime context.")),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        assertEquals(listOf("message"), request.input.map { it.string("type") })
        assertEquals("developer", request.input.single().string("role"))
        assertEquals("input_text", request.input.single().jsonArray("content").single().jsonObject.string("type"))
    }

    @Test
    fun dropsErrorSystemMessagesFromSubscriptionReplayInput() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(systemMessage(id = "system-error", text = "Previous API error.", isError = true)),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        assertEquals(emptyList(), request.input)
    }

    private fun assistantCompactionMessage(
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id?,
    ): Conversation.Message {
        val content = buildList {
            if (toolCallId != null) {
                add(
                    Conversation.Message.ContentItem.ToolCall(
                        id = toolCallId,
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = "grz_read_file",
                            input = buildJsonObject { put("file_path", "/tmp/example.txt") },
                        ),
                    )
                )
            }
        }

        return Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = content,
            providerMetadata = buildJsonObject {
                put(
                    OPENAI_REASONING_ITEMS_METADATA_KEY,
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "compaction")
                                put("encrypted_content", "encrypted")
                            }
                        )
                    )
                )
            },
            createdAt = createdAt,
        )
    }

    private fun userToolResultMessage(
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = "grz_read_file",
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.Text("file contents")
                    ),
                )
            ),
            createdAt = createdAt,
        )

    private fun systemMessage(
        id: String,
        text: String,
        isError: Boolean = false,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.SYSTEM,
            content = listOf(
                Conversation.Message.ContentItem.System(
                    level = Conversation.Message.ContentItem.System.SystemLevel.INFO,
                    content = text,
                )
            ),
            error = if (isError) Conversation.Message.GenerationError(message = text, type = "api") else null,
            createdAt = createdAt,
        )

    private fun testTool(name: String): AiToolCallback =
        object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = name,
                description = "Test tool",
                inputSchema = """{"type":"object","properties":{}}""",
            )

            override fun call(toolInput: String, context: com.gromozeka.domain.tool.ToolExecutionContext?): String =
                "ok"
        }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.jsonArray(key: String): JsonArray =
        this.getValue(key).jsonArray
}
