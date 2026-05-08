package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
