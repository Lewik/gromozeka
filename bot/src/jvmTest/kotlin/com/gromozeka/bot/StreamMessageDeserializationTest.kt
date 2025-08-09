package com.gromozeka.bot

import com.gromozeka.bot.model.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StreamJsonLineDeserializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `deserialize system init message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.systemInitMessage)

        assertTrue(message is StreamJsonLine.System)
        message as StreamJsonLine.System

        assertEquals("system", message.type)
        assertEquals("init", message.subtype)
        assertEquals("00f8f214-a5c5-40cf-a07e-4a3b383a94e9", message.sessionId)
        assertTrue(message.data?.containsKey("model") == true)
        assertEquals("claude-3-5-sonnet-20241022", message.data?.get("model")?.toString()?.removeSurrounding("\""))
    }

    @Test
    fun `deserialize system error message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.systemErrorMessage)

        assertTrue(message is StreamJsonLine.System)
        message as StreamJsonLine.System

        assertEquals("system", message.type)
        assertEquals("error", message.subtype)
        assertNull(message.sessionId)
        assertTrue(message.data?.containsKey("error") == true)
    }

    @Test
    fun `deserialize user message with string content`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.userStringMessage)

        assertTrue(message is StreamJsonLine.User)
        message as StreamJsonLine.User

        assertEquals("user", message.type)
        assertEquals("00f8f214-a5c5-40cf-a07e-4a3b383a94e9", message.sessionId)
        assertNull(message.parentToolUseId)

        assertTrue(message.message is StreamMessageContent.User)
        val userContent = message.message as StreamMessageContent.User
        assertTrue(userContent.content is ContentItemsUnion.StringContent)

        val stringContent = userContent.content as ContentItemsUnion.StringContent
        assertEquals("Hello Claude, how are you?", stringContent.content)
    }

    @Test
    fun `deserialize user message with array content`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.userArrayMessage)

        assertTrue(message is StreamJsonLine.User)
        message as StreamJsonLine.User

        assertEquals("tool_456", message.parentToolUseId)

        val userContent = message.message as StreamMessageContent.User
        assertTrue(userContent.content is ContentItemsUnion.ArrayContent)

        val arrayContent = userContent.content as ContentItemsUnion.ArrayContent
        assertEquals(2, arrayContent.items.size)

        val firstItem = arrayContent.items[0]
        assertTrue(firstItem is ContentBlock.TextBlock)
        assertEquals("Please run this command:", (firstItem as ContentBlock.TextBlock).text)

        val secondItem = arrayContent.items[1]
        assertTrue(secondItem is ContentBlock.ToolResultBlock)
        val toolResult = secondItem as ContentBlock.ToolResultBlock
        assertEquals("tool_123", toolResult.toolUseId)
        assertTrue(toolResult.content is ContentResultUnion.StringResult)
        assertEquals("Command executed successfully", (toolResult.content as ContentResultUnion.StringResult).content)
    }

    @Test
    fun `deserialize assistant text message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.assistantTextMessage)

        assertTrue(message is StreamJsonLine.Assistant)
        message as StreamJsonLine.Assistant

        val assistantContent = message.message as StreamMessageContent.Assistant
        assertEquals("msg_123", assistantContent.id)
        assertEquals("claude-3-5-sonnet-20241022", assistantContent.model)
        assertEquals("end_turn", assistantContent.stopReason)

        assertNotNull(assistantContent.usage)
        assertEquals(15, assistantContent.usage?.inputTokens)
        assertEquals(12, assistantContent.usage?.outputTokens)

        assertEquals(1, assistantContent.content.size)
        val textItem = assistantContent.content[0] as ContentBlock.TextBlock
        assertEquals("I'm doing well, thank you for asking!", textItem.text)
    }

    @Test
    fun `deserialize assistant tool use message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.assistantToolUseMessage)

        assertTrue(message is StreamJsonLine.Assistant)
        message as StreamJsonLine.Assistant

        val assistantContent = message.message as StreamMessageContent.Assistant
        assertEquals("tool_use", assistantContent.stopReason)
        assertEquals(2, assistantContent.content.size)

        val textItem = assistantContent.content[0] as ContentBlock.TextBlock
        assertEquals("I'll help you check the current directory.", textItem.text)

        val toolUseItem = assistantContent.content[1] as ContentBlock.ToolUseBlock
        assertEquals("tool_789", toolUseItem.id)
        assertEquals("Bash", toolUseItem.name)
        assertTrue(toolUseItem.input.toString().contains("pwd"))
    }

    @Test
    fun `deserialize assistant thinking message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.assistantThinkingMessage)

        assertTrue(message is StreamJsonLine.Assistant)
        message as StreamJsonLine.Assistant

        val assistantContent = message.message as StreamMessageContent.Assistant
        assertEquals(2, assistantContent.content.size)

        val thinkingItem = assistantContent.content[0] as ContentBlock.ThinkingItem
        assertTrue(thinkingItem.thinking.contains("The user is asking for help"))
        assertEquals("thinking_sig_123", thinkingItem.signature)

        val textItem = assistantContent.content[1] as ContentBlock.TextBlock
        assertEquals("Let me help you with that file operation.", textItem.text)

        // Check cache usage
        assertEquals(100, assistantContent.usage?.cacheReadInputTokens)
    }

    @Test
    fun `deserialize result success message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.resultSuccessMessage)

        assertTrue(message is StreamJsonLine.Result)
        message as StreamJsonLine.Result

        assertEquals("result", message.type)
        assertEquals("success", message.subtype)
        assertEquals(2500, message.durationMs)
        assertEquals(1800, message.durationApiMs)
        assertFalse(message.isError)
        assertEquals(3, message.numTurns)
        assertEquals(0.0042, message.totalCostUsd)

        assertNotNull(message.usage)
        assertEquals(150, message.usage?.inputTokens)
        assertEquals(85, message.usage?.outputTokens)
        assertEquals(50, message.usage?.cacheCreationInputTokens)
        assertEquals("standard", message.usage?.serviceTier)
    }

    @Test
    fun `deserialize result error message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.resultErrorMessage)

        assertTrue(message is StreamJsonLine.Result)
        message as StreamJsonLine.Result

        assertEquals("error", message.subtype)
        assertTrue(message.isError)
        assertEquals("API rate limit exceeded", message.result)
        assertEquals(0, message.usage?.outputTokens)
    }

    @Test
    fun `deserialize tool result with string content`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.toolResultStringContent)

        assertTrue(message is StreamJsonLine.User)
        message as StreamJsonLine.User

        val userContent = message.message as StreamMessageContent.User
        val arrayContent = userContent.content as ContentItemsUnion.ArrayContent

        val toolResultItem = arrayContent.items[0] as ContentBlock.ToolResultBlock
        assertEquals("tool_789", toolResultItem.toolUseId)
        assertTrue(toolResultItem.content is ContentResultUnion.StringResult)
        assertEquals(
            "/Users/lewik/code/gromozeka/dev",
            (toolResultItem.content as ContentResultUnion.StringResult).content
        )
    }

    @Test
    fun `deserialize tool result with array content`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.toolResultArrayContent)

        assertTrue(message is StreamJsonLine.User)
        message as StreamJsonLine.User

        val userContent = message.message as StreamMessageContent.User
        val arrayContent = userContent.content as ContentItemsUnion.ArrayContent

        val toolResultItem = arrayContent.items[0] as ContentBlock.ToolResultBlock
        assertEquals("tool_abc", toolResultItem.toolUseId)
        assertFalse(toolResultItem.isError ?: true)

        assertTrue(toolResultItem.content is ContentResultUnion.ArrayResult)
        val arrayResult = toolResultItem.content as ContentResultUnion.ArrayResult
        assertEquals(2, arrayResult.items.size)

        arrayResult.items.forEach { item ->
            assertTrue(item is ContentBlock.TextBlock)
        }
    }

    @Test
    fun `deserialize complex assistant message`() {
        val message = json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.complexAssistantMessage)

        assertTrue(message is StreamJsonLine.Assistant)
        message as StreamJsonLine.Assistant

        val assistantContent = message.message as StreamMessageContent.Assistant
        assertEquals(5, assistantContent.content.size)

        // Check thinking block
        val thinkingItem = assistantContent.content[0] as ContentBlock.ThinkingItem
        assertTrue(thinkingItem.thinking.contains("analyze this request"))

        // Check text blocks
        val textItem1 = assistantContent.content[1] as ContentBlock.TextBlock
        assertEquals("I'll help you with multiple operations:", textItem1.text)

        // Check tool use blocks
        val toolUse1 = assistantContent.content[2] as ContentBlock.ToolUseBlock
        assertEquals("Read", toolUse1.name)

        val toolUse2 = assistantContent.content[4] as ContentBlock.ToolUseBlock
        assertEquals("LS", toolUse2.name)

        // Check usage with service tier
        assertEquals("premium", assistantContent.usage?.serviceTier)
    }

    @Test
    fun `handle malformed JSON gracefully`() {
        assertThrows<SerializationException> {
            json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.malformedJson)
        }
    }

    @Test
    fun `handle unknown message type gracefully`() {
        assertThrows<SerializationException> {
            json.decodeFromString<StreamJsonLine>(StreamJsonLineTestData.unknownMessageType)
        }
    }

    @Test
    fun `test content items union serializer edge cases`() {
        // Test empty string content
        val emptyStringMessage = """{
            "type": "user",
            "message": {
                "role": "user",
                "content": ""
            },
            "session_id": "test"
        }"""

        val message = json.decodeFromString<StreamJsonLine>(emptyStringMessage)
        assertTrue(message is StreamJsonLine.User)
        val userContent = (message as StreamJsonLine.User).message as StreamMessageContent.User
        assertTrue(userContent.content is ContentItemsUnion.StringContent)
        assertEquals("", (userContent.content as ContentItemsUnion.StringContent).content)
    }

    @Test
    fun `test usage info with all optional fields`() {
        val fullUsageMessage = """{
            "type": "result",
            "subtype": "success",
            "duration_ms": 1000,
            "duration_api_ms": 800,
            "is_error": false,
            "num_turns": 1,
            "session_id": "test",
            "usage": {
                "input_tokens": 50,
                "output_tokens": 30,
                "cache_creation_input_tokens": 10,
                "cache_read_input_tokens": 20,
                "service_tier": "premium"
            }
        }"""

        val message = json.decodeFromString<StreamJsonLine>(fullUsageMessage)
        assertTrue(message is StreamJsonLine.Result)
        val resultMessage = message as StreamJsonLine.Result

        assertNotNull(resultMessage.usage)
        assertEquals(50, resultMessage.usage?.inputTokens)
        assertEquals(30, resultMessage.usage?.outputTokens)
        assertEquals(10, resultMessage.usage?.cacheCreationInputTokens)
        assertEquals(20, resultMessage.usage?.cacheReadInputTokens)
        assertEquals("premium", resultMessage.usage?.serviceTier)
    }
}