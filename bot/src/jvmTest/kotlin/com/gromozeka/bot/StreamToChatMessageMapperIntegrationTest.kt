package com.gromozeka.bot

import com.gromozeka.bot.model.StreamJsonLine
import com.gromozeka.bot.services.StreamToChatMessageMapper
import com.gromozeka.shared.domain.message.ChatMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.beInstanceOf
import kotlinx.serialization.json.Json

class StreamToChatMessageMapperIntegrationTest : FunSpec({

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    test("integration test with real StreamJsonLineTestData - system init") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.systemInitMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.SYSTEM
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.INFO
        systemContent.content.contains("init") shouldBe true
    }

    test("integration test with real StreamJsonLineTestData - system error") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.systemErrorMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.SYSTEM
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.ERROR
        systemContent.content.contains("error") shouldBe true
    }

    test("integration test with real StreamJsonLineTestData - user string message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.userStringMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.USER
        result.content.size shouldBe 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.Message
        messageContent.text shouldBe "Hello Claude, how are you?"
    }

    test("integration test with real StreamJsonLineTestData - user array message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.userArrayMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.USER
        result.content.size shouldBe 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.Message
        messageContent.text shouldBe "Please run this command:"

        val toolResult = result.content[1] as ChatMessage.ContentItem.ToolResult
        toolResult.toolUseId shouldBe "tool_123"
    }

    test("integration test with real StreamJsonLineTestData - assistant text message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.assistantTextMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.ASSISTANT
        result.content.size shouldBe 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()  // Now converts to StructuredText

        val messageContent = result.content[0] as ChatMessage.ContentItem.IntermediateMessage
        messageContent.structured.fullText shouldBe "I'm doing well, thank you for asking!"
        messageContent.structured.wasConverted shouldBe true

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.model shouldBe "claude-3-5-sonnet-20241022"
        metadata.stopReason shouldBe "end_turn"
        metadata.usage shouldNotBe null
        metadata.usage!!.inputTokens shouldBe 15
        metadata.usage!!.outputTokens shouldBe 12
    }

    test("integration test with real StreamJsonLineTestData - assistant tool use message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.assistantToolUseMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.ASSISTANT
        result.content.size shouldBe 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()  // Now converts to StructuredText
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()

        val messageContent = result.content[0] as ChatMessage.ContentItem.IntermediateMessage
        messageContent.structured.fullText shouldBe "I'll help you check the current directory."
        messageContent.structured.wasConverted shouldBe true

        val toolCall = result.content[1] as ChatMessage.ContentItem.ToolCall
        toolCall.id shouldBe "tool_789"

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.stopReason shouldBe "tool_use"
    }

    test("integration test with real StreamJsonLineTestData - assistant thinking message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.assistantThinkingMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.ASSISTANT
        result.content.size shouldBe 2

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Thinking>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()  // Now converts to StructuredText

        val thinking = result.content[0] as ChatMessage.ContentItem.Thinking
        thinking.signature shouldBe "thinking_sig_123"

        val messageContent = result.content[1] as ChatMessage.ContentItem.IntermediateMessage
        messageContent.structured.fullText shouldBe "Let me help you with that file operation."
        messageContent.structured.wasConverted shouldBe true

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.usage shouldNotBe null
        metadata.usage!!.cacheReadTokens shouldBe 100
    }

    test("integration test with real StreamJsonLineTestData - result success message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.resultSuccessMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result.role shouldBe ChatMessage.Role.SYSTEM
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.INFO
        systemContent.content.contains("turns") shouldBe true
    }

    test("integration test with real StreamJsonLineTestData - result error message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.resultErrorMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.SYSTEM
        result.content.size shouldBe 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.System>()

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.ERROR
        systemContent.content shouldBe "Error: API rate limit exceeded"
    }

    test("integration test with real StreamJsonLineTestData - tool result string content") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.toolResultStringContent)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.USER
        result.content.size shouldBe 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[0] as ChatMessage.ContentItem.ToolResult
        toolResult.toolUseId shouldBe "tool_789"
        toolResult.isError shouldBe false
    }

    test("integration test with real StreamJsonLineTestData - tool result array content") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.toolResultArrayContent)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.USER
        result.content.size shouldBe 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.ToolResult>()

        val toolResult = result.content[0] as ChatMessage.ContentItem.ToolResult
        toolResult.toolUseId shouldBe "tool_abc"
        toolResult.isError shouldBe false
    }

    test("integration test with real StreamJsonLineTestData - complex assistant message") {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.complexAssistantMessage)

        val result = StreamToChatMessageMapper.mapToChatMessage(message)
        result shouldNotBe null
        result!!.role shouldBe ChatMessage.Role.ASSISTANT
        result.content.size shouldBe 5

        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Thinking>()
        result.content[1] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()  // Now converts to StructuredText
        result.content[2] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()
        result.content[3] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()  // Now converts to StructuredText
        result.content[4] should beInstanceOf<ChatMessage.ContentItem.ToolCall>()

        val thinking = result.content[0] as ChatMessage.ContentItem.Thinking
        thinking.thinking.contains("analyze this request") shouldBe true

        val firstMessage = result.content[1] as ChatMessage.ContentItem.IntermediateMessage
        firstMessage.structured.fullText shouldBe "I'll help you with multiple operations:"

        val firstToolCall = result.content[2] as ChatMessage.ContentItem.ToolCall
        firstToolCall.call should beInstanceOf<com.gromozeka.shared.domain.message.ClaudeCodeToolCallData.Read>()

        val secondMessage = result.content[3] as ChatMessage.ContentItem.IntermediateMessage
        secondMessage.structured.fullText shouldBe "Let me also check the directory structure:"

        val secondToolCall = result.content[4] as ChatMessage.ContentItem.ToolCall
        // Note: LS tool is not in our predefined list, so it should be Generic
        secondToolCall.call should beInstanceOf<com.gromozeka.shared.domain.message.ToolCallData.Generic>()

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.usage shouldNotBe null
    }

    test("mapper handles all real test data without exceptions") {
        val allTestData = listOf(
            StreamJsonLineTestData.systemInitMessage,
            StreamJsonLineTestData.systemErrorMessage,
            StreamJsonLineTestData.userStringMessage,
            StreamJsonLineTestData.userArrayMessage,
            StreamJsonLineTestData.assistantTextMessage,
            StreamJsonLineTestData.assistantToolUseMessage,
            StreamJsonLineTestData.assistantThinkingMessage,
            StreamJsonLineTestData.resultSuccessMessage,
            StreamJsonLineTestData.resultErrorMessage,
            StreamJsonLineTestData.toolResultStringContent,
            StreamJsonLineTestData.toolResultArrayContent,
            StreamJsonLineTestData.complexAssistantMessage
        )

        var successfullyParsed = 0
        var successfullyMapped = 0

        allTestData.forEach { testData ->
            try {
                val streamMessage = json.decodeFromString<StreamJsonLine>(testData)
                successfullyParsed++

                val chatMessage = StreamToChatMessageMapper.mapToChatMessage(streamMessage)
                // All messages should be mapped now
                successfullyMapped++

            } catch (e: Exception) {
                println("Failed to process test data: ${e.message}")
                println("Test data preview: ${testData.take(200)}...")
            }
        }

        println("Integration test results:")
        println("Successfully parsed: $successfullyParsed/${allTestData.size}")
        println("Successfully mapped: $successfullyMapped/${allTestData.size}")

        // All test data should be processed without exceptions
        successfullyParsed shouldBe allTestData.size
        successfullyMapped shouldBe allTestData.size
    }
})