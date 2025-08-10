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

    test("mapper should handle SystemStreamJsonLine init") {
        val systemInit = StreamJsonLine.System(
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

    test("mapper should handle SystemStreamJsonLine error") {
        val systemError = StreamJsonLine.System(
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

    test("mapper should handle SystemStreamJsonLine other subtypes") {
        val systemOther = StreamJsonLine.System(
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

    test("mapper should handle UserStreamJsonLine with string content") {
        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent("Hello Claude!")
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.USER
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.UserMessage>()

        val userMessageContent = result.content[0] as ChatMessage.ContentItem.UserMessage
        userMessageContent.text shouldBe "Hello Claude!"
    }

    test("mapper should handle UserStreamJsonLine with array content") {
        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        ContentBlock.TextBlock("Please run:"),
                        ContentBlock.ToolResultBlock(
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

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.UserMessage>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[1] as ChatMessage.ContentItem.ToolResult
        toolResult.toolUseId shouldBe "tool_123"
    }

    test("mapper should handle AssistantStreamJsonLine with text") {
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                id = "msg_123",
                model = "claude-3-5-sonnet-20241022",
                content = listOf(
                    ContentBlock.TextBlock("I can help you with that!")
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
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()  // Now converts to StructuredText

        val messageContent = result.content[0] as ChatMessage.ContentItem.AssistantMessage
        messageContent.structured.fullText shouldBe "I can help you with that!"
        messageContent.structured.failedToParse shouldBe true  // Marked as auto-converted

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.model shouldBe "claude-3-5-sonnet-20241022"
        metadata.stopReason shouldBe "end_turn"
        metadata.usage shouldNotBe null
        metadata.usage!!.inputTokens shouldBe 10
        metadata.usage!!.outputTokens shouldBe 15
    }

    test("mapper should handle AssistantStreamJsonLine with tool use") {
        val toolInput = JsonObject(
            mapOf(
                "command" to JsonPrimitive("pwd"),
                "description" to JsonPrimitive("Get current directory")
            )
        )

        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.TextBlock("I'll check the current directory."),
                    ContentBlock.ToolUseBlock(
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

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()  // Now converts to StructuredText
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()

        val toolCall = result.content[1] as ChatMessage.ContentItem.ToolCall
        toolCall.id shouldBe "tool_789"
        toolCall.call should beInstanceOf<ClaudeCodeToolCallData.Bash>()

        val bashCall = toolCall.call as ClaudeCodeToolCallData.Bash
        bashCall.command shouldBe "pwd"
        bashCall.description shouldBe "Get current directory"
    }

    test("mapper should handle AssistantStreamJsonLine with thinking") {
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.ThinkingItem(
                        thinking = "The user wants me to analyze this code.",
                        signature = "thinking_123"
                    ),
                    ContentBlock.TextBlock("Let me analyze that for you.")
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Thinking>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()  // Now converts to StructuredText

        val thinking = result.content[0] as ChatMessage.ContentItem.Thinking
        thinking.thinking shouldBe "The user wants me to analyze this code."
        thinking.signature shouldBe "thinking_123"
    }

    test("mapper should handle ResultStreamJsonLine error") {
        val resultMessage = StreamJsonLine.Result(
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

    test("mapper should handle ResultStreamJsonLine success") {
        val resultMessage = StreamJsonLine.Result(
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

        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent(gromozekaJson)
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()

        val gromozekaContent = result.content[0] as ChatMessage.ContentItem.AssistantMessage
        gromozekaContent.structured?.fullText shouldBe "Привет от Громозеки!"
        gromozekaContent.structured?.ttsText shouldBe "Привет!"
        gromozekaContent.structured?.voiceTone shouldBe "friendly"
    }

    test("mapper should handle unknown JSON as UnknownJson") {
        val unknownJson = """{"weird_field": "strange_value", "nested": {"data": 42}}"""

        val userMessage = StreamJsonLine.User(
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

        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.StringContent(malformedJson)
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(userMessage)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.UserMessage>()

        val userMessageContent = result.content[0] as ChatMessage.ContentItem.UserMessage
        userMessageContent.text shouldBe malformedJson
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
            val assistantMessage = StreamJsonLine.Assistant(
                message = StreamMessageContent.Assistant(
                    content = listOf(
                        ContentBlock.ToolUseBlock(
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
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.ToolUseBlock(
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
        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        ContentBlock.ToolResultBlock(
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
        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        ContentBlock.ToolResultBlock(
                            toolUseId = "tool_456",
                            content = ContentResultUnion.ArrayResult(
                                listOf(
                                    ContentBlock.TextBlock("Line 1"),
                                    ContentBlock.TextBlock("Line 2")
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
        val userMessage = StreamJsonLine.User(
            message = StreamMessageContent.User(
                content = ContentItemsUnion.ArrayContent(
                    listOf(
                        ContentBlock.ToolResultBlock(
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
        toolResult.result should beInstanceOf<ClaudeCodeToolResultData.NullResult>()
    }

    test("mapper should generate unique UUIDs") {
        val userMessage = StreamJsonLine.User(
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

    // TODO: Fix test after parser logic changes
    /*
    test("mapper should preserve session metadata") {
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                id = "msg_test",
                model = "claude-3-5-sonnet-20241022",
                content = listOf(ContentBlock.TextBlock("Test")),
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
        
        // Check content was converted
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()
        val messageContent = result.content[0] as ChatMessage.ContentItem.AssistantMessage
        messageContent.structured.fullText shouldBe "Test"
        messageContent.structured.failedToParse shouldBe true

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.sessionId shouldBe "test-session-123"
        metadata.model shouldBe "claude-3-5-sonnet-20241022"
        metadata.stopReason shouldBe "end_turn"
        metadata.usage shouldNotBe null
        metadata.usage!!.inputTokens shouldBe 50
        metadata.usage!!.outputTokens shouldBe 30
        metadata.usage!!.cacheCreationTokens shouldBe 10
        metadata.usage!!.cacheReadTokens shouldBe 20
    }
    */

    test("mapper should auto-convert Assistant plain text to StructuredText with failedToParse flag") {
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.TextBlock("This is plain text response from Claude")
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.ASSISTANT
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.AssistantMessage
        messageContent.structured shouldNotBe null
        messageContent.structured.fullText shouldBe "This is plain text response from Claude"
        messageContent.structured.ttsText shouldBe null
        messageContent.structured.voiceTone shouldBe null
        messageContent.structured.failedToParse shouldBe true  // Should be marked as auto-converted
    }

    test("mapper should not mark already structured Assistant text as converted") {
        val structuredJson = """{"fullText": "Structured response", "ttsText": "Structured", "voiceTone": "calm"}"""
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.TextBlock(structuredJson)
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.AssistantMessage
        messageContent.structured shouldNotBe null
        messageContent.structured.fullText shouldBe "Structured response"
        messageContent.structured.ttsText shouldBe "Structured"
        messageContent.structured.voiceTone shouldBe "calm"
        messageContent.structured.failedToParse shouldBe false  // Should NOT be marked as converted
    }

    // TODO: Fix test after parser logic changes
    /*
    test("mapper should handle Assistant unknown JSON as StructuredText with pretty-print") {
        val unknownJson = """{"someField": "someValue", "anotherField": 42}"""
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.TextBlock(unknownJson)
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.AssistantMessage
        messageContent.structured.failedToParse shouldBe true
        messageContent.structured.fullText shouldContain "\"someField\""
        messageContent.structured.fullText shouldContain "\"anotherField\""
    }
    */

    test("mapper should auto-convert multiple Assistant plain text blocks") {
        val assistantMessage = StreamJsonLine.Assistant(
            message = StreamMessageContent.Assistant(
                content = listOf(
                    ContentBlock.TextBlock("First plain text"),
                    ContentBlock.TextBlock("Second plain text")
                )
            ),
            sessionId = "test-session"
        )

        val result = StreamToChatMessageMapper.mapToChatMessage(assistantMessage)
        result shouldNotBe null
        result.content shouldHaveSize 2
        
        // Both should be converted to StructuredText
        result.content.forEach { item ->
            item should beInstanceOf<ChatMessage.ContentItem.AssistantMessage>()
            val structured = (item as ChatMessage.ContentItem.AssistantMessage).structured
            structured.failedToParse shouldBe true
        }
    }
})