package com.gromozeka.bot

import com.gromozeka.bot.model.*
import com.gromozeka.bot.services.StreamToChatMessageMapper
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import com.gromozeka.shared.domain.message.ClaudeCodeToolResultData
import com.gromozeka.shared.domain.message.ToolCallData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.beInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class StreamToChatMessageMapperTest : FunSpec({

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    test("mapper should handle SystemStreamMessage init") {
        val systemInit = StreamMessage.System(
            subtype = "init",
            data = JsonObject(
                mapOf(
                    "model" to JsonPrimitive("claude-3-5-sonnet-20241022"),
                    "session_id" to JsonPrimitive("test-session")
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(systemInit)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.SYSTEM
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.INFO
        systemContent.content.contains("init") shouldBe true
    }

    test("mapper should handle SystemStreamMessage error") {
        val systemError = StreamMessage.System(
            subtype = "error",
            data = JsonObject(
                mapOf(
                    "error" to JsonPrimitive("API rate limit exceeded")
                )
            )
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(systemError)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.SYSTEM
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.ERROR
        systemContent.content.contains("error") shouldBe true
    }

    test("mapper should handle SystemStreamMessage other subtypes") {
        val systemOther = StreamMessage.System(
            subtype = "notification",
            data = JsonObject(
                mapOf(
                    "message" to JsonPrimitive("System notification")
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(systemOther)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.SYSTEM
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.INFO
    }

    test("mapper should handle UserStreamMessage with string content") {
        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent("Hello Claude!")
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.USER
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.Message
        messageContent.text shouldBe "Hello Claude!"
    }

    test("mapper should handle UserStreamMessage with array content") {
        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        StreamContentItem.TextItem("Please run:"),
                        StreamContentItem.ToolResultItem(
                            toolUseId = "tool_123",
                            content = ContentResultUnion.StringResult("Command output")
                        )
                    )
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[1] as ChatMessage.ContentItem.ToolResult
        toolResult.toolUseId shouldBe "tool_123"
    }

    test("mapper should handle AssistantStreamMessage with text") {
        val assistantMessage = StreamMessage.Assistant(
            message = StreamMessageContent.Assistant(
                id = "msg_123",
                model = "claude-3-5-sonnet-20241022",
                content = listOf(
                    StreamContentItem.TextItem("I can help you with that!")
                ),
                stopReason = "end_turn",
                usage = UsageInfo(
                    inputTokens = 10,
                    outputTokens = 15
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.ASSISTANT
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.model shouldBe "claude-3-5-sonnet-20241022"
        metadata.stopReason shouldBe "end_turn"
        metadata.usage shouldNotBe null
        metadata.usage!!.inputTokens shouldBe 10
        metadata.usage!!.outputTokens shouldBe 15
    }

    test("mapper should handle AssistantStreamMessage with tool use") {
        val toolInput = JsonObject(
            mapOf(
                "command" to JsonPrimitive("pwd"),
                "description" to JsonPrimitive("Get current directory")
            )
        )

        val assistantMessage = StreamMessage.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    StreamContentItem.TextItem("I'll check the current directory."),
                    StreamContentItem.ToolUseItem(
                        id = "tool_789",
                        name = "Bash",
                        input = toolInput
                    )
                ),
                stopReason = "tool_use"
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()

        val toolCall = result.content[1] as ChatMessage.ContentItem.ToolCall
        toolCall.id shouldBe "tool_789"
        toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Bash>()

        val bashCall = toolCall.call as ClaudeCodeToolCallData.Bash
        bashCall.command shouldBe "pwd"
        bashCall.description shouldBe "Get current directory"
    }

    test("mapper should handle AssistantStreamMessage with thinking") {
        val assistantMessage = StreamMessage.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    StreamContentItem.ThinkingItem(
                        thinking = "The user wants me to analyze this code.",
                        signature = "thinking_123"
                    ),
                    StreamContentItem.TextItem("Let me analyze that for you.")
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Thinking>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.Message>()

        val thinking = result.content[0] as ChatMessage.ContentItem.Thinking
        thinking.thinking shouldBe "The user wants me to analyze this code."
        thinking.signature shouldBe "thinking_123"
    }

    test("mapper should handle ResultStreamMessage error") {
        val resultMessage = StreamMessage.Result(
            subtype = "error",
            durationMs = 1000,
            durationApiMs = 800,
            isError = true,
            numTurns = 1,
            sessionId = "test-session",
            result = "API timeout occurred"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(resultMessage)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.SYSTEM
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.ERROR
        systemContent.content shouldBe "Error: API timeout occurred"
    }

    test("mapper should handle ResultStreamMessage success") {
        val resultMessage = StreamMessage.Result(
            subtype = "success",
            durationMs = 2500,
            durationApiMs = 1800,
            isError = false,
            numTurns = 3,
            sessionId = "test-session",
            totalCostUsd = 0.0042
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(resultMessage)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.SYSTEM
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.INFO
        systemContent.content.contains("3 turns") shouldBe true
        systemContent.content.contains("2500ms") shouldBe true
    }

    test("mapper should detect Gromozeka JSON in text content") {
        val gromozekaJson = """{"fullText": "Привет от Громозеки!", "ttsText": "Привет!", "voiceTone": "friendly"}"""

        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent(gromozekaJson)
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()

        val gromozekaContent = result.content[0] as ChatMessage.ContentItem.IntermediateMessage
        gromozekaContent.structured?.fullText shouldBe "Привет от Громозеки!"
        gromozekaContent.structured?.ttsText shouldBe "Привет!"
        gromozekaContent.structured?.voiceTone shouldBe "friendly"
    }

    test("mapper should handle unknown JSON as UnknownJson") {
        val unknownJson = """{"weird_field": "strange_value", "nested": {"data": 42}}"""

        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent(unknownJson)
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.UnknownJson>()

        val unknownContent = result.content[0] as ChatMessage.ContentItem.UnknownJson
        unknownContent.json should beInstanceOf<JsonObject>()
    }

    test("mapper should handle malformed JSON as regular text") {
        val malformedJson = """{"incomplete": "json"""

        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent(malformedJson)
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.Message
        messageContent.text shouldBe malformedJson
    }

    test("mapper should handle all Claude Code tool types") {
        val tools = listOf(
            "Read" to JsonObject(
                mapOf(
                    "file_path" to JsonPrimitive("/test/file.txt")
                )
            ),
            "Edit" to JsonObject(
                mapOf(
                    "file_path" to JsonPrimitive("/test/file.txt"),
                    "old_string" to JsonPrimitive("old"),
                    "new_string" to JsonPrimitive("new")
                )
            ),
            "Bash" to JsonObject(
                mapOf(
                    "command" to JsonPrimitive("ls -la")
                )
            ),
            "Grep" to JsonObject(
                mapOf(
                    "pattern" to JsonPrimitive("test.*pattern")
                )
            ),
            "TodoWrite" to JsonObject(
                mapOf(
                    "todos" to JsonArray(emptyList())
                )
            ),
            "WebSearch" to JsonObject(
                mapOf(
                    "query" to JsonPrimitive("kotlin multiplatform")
                )
            ),
            "WebFetch" to JsonObject(
                mapOf(
                    "url" to JsonPrimitive("https://example.com"),
                    "prompt" to JsonPrimitive("Extract main content")
                )
            ),
            "Task" to JsonObject(
                mapOf(
                    "description" to JsonPrimitive("Test task"),
                    "prompt" to JsonPrimitive("Do something"),
                    "subagent_type" to JsonPrimitive("general-purpose")
                )
            )
        )

        tools.forEach { (toolName, toolInput) ->
            val assistantMessage = StreamMessage.Assistant(
                message = StreamMessageContent.Assistant(
                    content = listOf(
                        StreamContentItem.ToolUseItem(
                            id = "tool_$toolName",
                            name = toolName,
                            input = toolInput
                        )
                    )
                ),
                sessionId = "test-session"
            )

            val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
            result shouldNotBe null
            result!!.content shouldHaveSize 1
            result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()

            val toolCall = result.content[0] as ChatMessage.ContentItem.ToolCall
            toolCall.id shouldBe "tool_$toolName"

            when (toolName) {
                "Read" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Read>()
                "Edit" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Edit>()
                "Bash" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Bash>()
                "Grep" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Grep>()
                "TodoWrite" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.TodoWrite>()
                "WebSearch" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.WebSearch>()
                "WebFetch" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.WebFetch>()
                "Task" -> toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Task>()
            }
        }
    }

    test("mapper should handle unknown tool as Generic") {
        val assistantMessage = StreamMessage.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    StreamContentItem.ToolUseItem(
                        id = "tool_unknown",
                        name = "UnknownTool",
                        input = JsonObject(
                            mapOf(
                                "param" to JsonPrimitive("value")
                            )
                        )
                    )
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()

        val toolCall = result.content[0] as ChatMessage.ContentItem.ToolCall
        toolCall.call should beInstanceOf<ToolCallData.Generic>()

        val genericCall = toolCall.call as ToolCallData.Generic
        genericCall.name shouldBe "UnknownTool"
    }

    test("mapper should handle tool result with string content") {
        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        StreamContentItem.ToolResultItem(
                            toolUseId = "tool_123",
                            content = ContentResultUnion.StringResult("/Users/test/current/dir"),
                            isError = false
                        )
                    )
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[0] as ChatMessage.ContentItem.ToolResult
        toolResult.toolUseId shouldBe "tool_123"
        toolResult.isError shouldBe false
        toolResult.result should beInstanceOf<ClaudeCodeToolResultData.Read>()

        val readResult = toolResult.result as ClaudeCodeToolResultData.Read
        readResult.content shouldBe "/Users/test/current/dir"
    }

    test("mapper should handle tool result with array content") {
        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        StreamContentItem.ToolResultItem(
                            toolUseId = "tool_456",
                            content = ContentResultUnion.ArrayResult(
                                listOf(
                                    StreamContentItem.TextItem("Line 1"),
                                    StreamContentItem.TextItem("Line 2")
                                )
                            )
                        )
                    )
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[0] as ChatMessage.ContentItem.ToolResult
        toolResult.result should beInstanceOf<ClaudeCodeToolResultData.Read>()

        val readResult = toolResult.result as ClaudeCodeToolResultData.Read
        readResult.content shouldBe "Line 1\nLine 2"
    }

    test("mapper should handle null tool result content") {
        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        StreamContentItem.ToolResultItem(
                            toolUseId = "tool_null",
                            content = null
                        )
                    )
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[0] as ChatMessage.ContentItem.ToolResult
        toolResult.result should beInstanceOf<ClaudeCodeToolResultData.Read>()

        val readResult = toolResult.result as ClaudeCodeToolResultData.Read
        readResult.content shouldBe ""
    }

    test("mapper should generate unique UUIDs") {
        val userMessage = StreamMessage.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent("Test message")
            ),
            sessionId = "test-session"
        )

        val result1 = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        val result2 = StreamToChatMessageMapper.mapToChatMessage(userMessage)

        result1 shouldNotBe null
        result2 shouldNotBe null
        result1!!.uuid shouldNotBe result2!!.uuid
    }

    test("mapper should preserve session metadata") {
        val assistantMessage = StreamMessage.Assistant(
            message = StreamMessageContent.Assistant(
                id = "msg_test",
                model = "claude-3-5-sonnet-20241022",
                content = listOf(StreamContentItem.TextItem("Test")),
                stopReason = "end_turn",
                usage = UsageInfo(
                    inputTokens = 50,
                    outputTokens = 30,
                    cacheCreationInputTokens = 10,
                    cacheReadInputTokens = 20,
                    serviceTier = "premium"
                )
            ),
            sessionId = "test-session-123"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null

        val metadata = result!!.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.sessionId shouldBe "test-session-123"
        metadata.model shouldBe "claude-3-5-sonnet-20241022"
        metadata.stopReason shouldBe "end_turn"
        metadata.usage shouldNotBe null
        metadata.usage!!.inputTokens shouldBe 50
        metadata.usage!!.outputTokens shouldBe 30
        metadata.usage!!.cacheCreationTokens shouldBe 10
        metadata.usage!!.cacheReadTokens shouldBe 20
    }
})