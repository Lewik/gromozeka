package com.gromozeka.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

class NewClaudeLogEntryDeserializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testMessageContentDeserialization() {
        // Test text content
        val textJson = """{"type": "text", "text": "Hello world"}"""
        val textContent = json.decodeFromString<TestMessageContent>(textJson)
        assertEquals("text", textContent.type)
        assertEquals("Hello world", (textContent as TestMessageContent.Text).text)

        // Test tool use content
        val toolUseJson = """{"type": "tool_use", "id": "tool_123", "name": "bash", "input": {"command": "ls"}}"""
        val toolUseContent = json.decodeFromString<TestMessageContent>(toolUseJson)
        assertEquals("tool_use", toolUseContent.type)
        assertEquals("tool_123", (toolUseContent as TestMessageContent.ToolUse).id)
        assertEquals("bash", toolUseContent.name)

        // Test thinking content
        val thinkingJson = """{"type": "thinking", "thinking": "I need to...", "signature": "sig123"}"""
        val thinkingContent = json.decodeFromString<TestMessageContent>(thinkingJson)
        assertEquals("thinking", thinkingContent.type)
        assertEquals("I need to...", (thinkingContent as TestMessageContent.Thinking).thinking)
    }

    @Test
    fun testTestMessageDeserialization() {
        val messageJson = """{
            "role": "user",
            "content": "Test message"
        }"""
        val message = json.decodeFromString<TestMessage>(messageJson)
        assertEquals("user", message.role)
        assertEquals("\"Test message\"", message.content.toString())
    }

    @Test
    fun testCompleteLogEntryDeserialization() {
        val entryJson = """{
            "type": "user",
            "timestamp": "2025-08-01T10:00:00Z",
            "uuid": "test-uuid",
            "sessionId": "session-123",
            "message": {
                "role": "user",
                "content": "Hello Claude"
            },
            "toolUseResult": {"result": "success"}
        }"""
        
        val entry = json.decodeFromString<TestLogEntry>(entryJson)
        assertNotNull(entry)
        assertEquals("user", entry.type)
        assertEquals("test-uuid", entry.uuid)
        assertEquals("session-123", entry.sessionId)
        assertNotNull(entry.message)
        assertEquals("user", entry.message!!.role)
    }

    @Test
    fun testArrayContentDeserialization() {
        val entryJson = """{
            "type": "assistant",
            "timestamp": "2025-08-01T10:00:00Z",
            "uuid": "test-uuid",
            "sessionId": "session-123",
            "message": {
                "id": "msg-123",
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "I'll help you."},
                    {"type": "tool_use", "id": "tool_1", "name": "bash", "input": {"command": "ls"}}
                ],
                "model": "claude-3-5-sonnet-20241022"
            }
        }"""
        
        val entry = json.decodeFromString<TestLogEntry>(entryJson)
        assertNotNull(entry)
        assertEquals("assistant", entry.type)
        assertNotNull(entry.message)
        assertEquals("assistant", entry.message!!.role)
        assertEquals("msg-123", entry.message!!.id)
        assertEquals("claude-3-5-sonnet-20241022", entry.message!!.model)
    }
}