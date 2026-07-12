package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun shortensLongToolCallIdsWithoutBreakingToolResultPairing() {
        val originalId = "call_" + "x".repeat(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH)
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id(originalId)
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

        val functionCallId = requireNotNull(request.input[1].string("call_id"))
        val functionCallOutputId = requireNotNull(request.input[2].string("call_id"))
        assertEquals(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH, functionCallId.length)
        assertEquals(functionCallId, functionCallOutputId)
        assertFalse(functionCallId == originalId)
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
    fun forcesDefaultServiceTierForMemoryStages() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                options = AiRuntimeOptions(
                    toolContext = mapOf("memoryNamespace" to "project:test"),
                ),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertEquals("default", request.serviceTier)
        assertEquals(
            "default",
            OpenAiSubscriptionResponsesWebSocketRequest.from(request, useResponsesLite = false).serviceTier,
        )
    }

    @Test
    fun leavesServiceTierUnsetForNormalRequests() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertNull(request.serviceTier)
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

        assertEquals(1, request.tools.orEmpty().size)
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

    @Test
    fun framesResponsesLiteToolsAndInstructionsInsideInput() {
        val profile = modelProfile(slug = "gpt-5.6-sol", useResponsesLite = true)
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = listOf("Base instructions"),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(
                    reasoning = AiReasoningConfig(effort = AiReasoningEffort.HIGH),
                    autoCompactionThresholdTokens = 297_600,
                ),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
        )

        val transportRequest = mapper.toTransportRequest(logicalRequest, profile)

        assertNull(transportRequest.instructions)
        assertNull(transportRequest.tools)
        assertNull(transportRequest.contextManagement)
        assertFalse(transportRequest.parallelToolCalls)
        assertEquals("additional_tools", transportRequest.input[0].string("type"))
        assertEquals("developer", transportRequest.input[0].string("role"))
        assertEquals(1, transportRequest.input[0].jsonArray("tools").size)
        assertEquals("message", transportRequest.input[1].string("type"))
        assertEquals("developer", transportRequest.input[1].string("role"))
        assertEquals("all_turns", transportRequest.reasoning?.string("context"))
        assertEquals(listOf("reasoning.encrypted_content"), transportRequest.include)

        val websocketRequest = OpenAiSubscriptionResponsesWebSocketRequest.from(
            request = transportRequest,
            useResponsesLite = true,
        )
        assertEquals(
            "true",
            websocketRequest.clientMetadata
                ?.get("ws_request_header_x_openai_internal_codex_responses_lite"),
        )
    }

    @Test
    fun keepsNormalResponsesToolsAndInstructionsAtTopLevel() {
        val profile = modelProfile(slug = "gpt-5.5")
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = listOf("Base instructions"),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
        )

        val transportRequest = mapper.toTransportRequest(logicalRequest, profile)

        assertEquals("Base instructions", transportRequest.instructions)
        assertEquals(1, transportRequest.tools.orEmpty().size)
        assertTrue(transportRequest.parallelToolCalls)
        assertFalse(transportRequest.input.any { it.string("type") == "additional_tools" })
    }

    @Test
    fun normalizesStructuredOutputToThePersistedAssistantReplayProjection() {
        val structured = Conversation.Message.StructuredText(
            fullText = "Visible answer",
            ttsText = "Spoken answer",
            voiceTone = "warm",
        )
        val persistedMessage = Conversation.Message(
            id = Conversation.Message.Id("assistant"),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(structured = structured)
            ),
            providerMetadata = buildJsonObject { put("phase", "final_answer") },
            createdAt = createdAt,
        )
        val persistedReplayItem = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(persistedMessage),
            ),
            modelName = "gpt-5.6-luna",
            conversationKey = "test-conversation",
        ).input.single()
        val rawOutputItem = buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("phase", "final_answer")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", Json.encodeToString(structured))
                })
            })
        }

        val responseReplayItem = mapper.toReplayItems(
            outputItems = listOf(rawOutputItem),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
        ).single()

        assertEquals(persistedReplayItem, responseReplayItem)
        assertEquals("Visible answer", responseReplayItem.string("content"))
    }

    @Test
    fun normalizesToolArgumentsToThePersistedToolCallProjection() {
        val toolCall = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-1"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "test_tool",
                input = buildJsonObject {
                    put("first", 1)
                    put("second", "two")
                },
            ),
        )
        val persistedMessage = Conversation.Message(
            id = Conversation.Message.Id("assistant-tool"),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(toolCall),
            createdAt = createdAt,
        )
        val persistedReplayItem = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(persistedMessage),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        ).input.single()
        val rawOutputItem = buildJsonObject {
            put("type", "function_call")
            put("call_id", "call-1")
            put("name", "test_tool")
            put("arguments", "{ \"first\" : 1, \"second\" : \"two\" }")
        }

        val responseReplayItem = mapper.toReplayItems(
            outputItems = listOf(rawOutputItem),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ).single()

        assertEquals(persistedReplayItem, responseReplayItem)
        assertEquals("{\"first\":1,\"second\":\"two\"}", responseReplayItem.string("arguments"))
    }

    @Test
    fun mapsMaximumReasoningToHighestSupportedNonUltraEffort() {
        val request = AiRuntimeRequest(
            systemPrompts = emptyList(),
            messages = emptyList(),
            options = AiRuntimeOptions(
                reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX),
            ),
        )

        val gpt55 = mapper.toRequest(
            request = request,
            modelProfile = modelProfile(slug = "gpt-5.5"),
            conversationKey = "test-conversation",
        )
        val gpt56 = mapper.toRequest(
            request = request,
            modelProfile = modelProfile(
                slug = "gpt-5.6-sol",
                useResponsesLite = true,
                supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            ),
            conversationKey = "test-conversation",
        )

        assertEquals("xhigh", assertNotNull(gpt55.reasoning).string("effort"))
        assertEquals("max", assertNotNull(gpt56.reasoning).string("effort"))
    }

    private fun assistantCompactionMessage(
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id?,
    ): Conversation.Message {
        val content = buildList {
            add(
                Conversation.Message.ContentItem.ContextCompactionResult(
                    payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(
                        state = buildJsonObject {
                            put(
                                "replay_item",
                                buildJsonObject {
                                    put("type", "compaction")
                                    put("encrypted_content", "encrypted")
                                }
                            )
                        },
                    ),
                    origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                    providerScope = Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(
                        provider = AiConnection.Kind.OPENAI_SUBSCRIPTION.name,
                    ),
                )
            )
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

private fun OpenAiSubscriptionRequestMapper.toRequest(
    request: AiRuntimeRequest,
    modelName: String,
    conversationKey: String,
): OpenAiSubscriptionResponsesRequest = toRequest(
    request = request,
    modelProfile = modelProfile(slug = modelName),
    conversationKey = conversationKey,
)

private fun modelProfile(
    slug: String,
    useResponsesLite: Boolean = false,
    supportedReasoningEfforts: List<String> = listOf("low", "medium", "high", "xhigh"),
): OpenAiSubscriptionModelProfile = OpenAiSubscriptionModelProfile(
    slug = slug,
    useResponsesLite = useResponsesLite,
    supportsReasoningSummaries = true,
    supportedReasoningEfforts = supportedReasoningEfforts,
    supportsVerbosity = true,
    defaultVerbosity = "low",
    supportsParallelToolCalls = true,
)
