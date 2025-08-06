package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import org.junit.jupiter.api.Test

class WhenExhaustiveTest {

    @Test
    fun `when expression should be exhaustive with sealed class hierarchy`() {
        val entries = listOf<ClaudeLogEntry>(
            ClaudeLogEntry.SummaryEntry("leaf123", "test summary"),
            ClaudeLogEntry.UserEntry(
                cwd = "/test",
                gitBranch = "main",
                sessionId = "session123",
                timestamp = "2025-01-01T00:00:00Z",
                userType = "human",
                uuid = "user123",
                version = "1.0",
                isSidechain = false,
                parentUuid = null,
                message = null
            ),
            ClaudeLogEntry.AssistantEntry(
                cwd = "/test",
                gitBranch = "main",
                sessionId = "session123",
                timestamp = "2025-01-01T00:00:01Z",
                userType = "ai",
                uuid = "assistant123",
                version = "1.0",
                isSidechain = false,
                parentUuid = "user123",
                requestId = "req123"
            ),
            ClaudeLogEntry.SystemEntry(
                cwd = "/test",
                gitBranch = "main",
                sessionId = "session123",
                timestamp = "2025-01-01T00:00:02Z",
                userType = "system",
                uuid = "system123",
                version = "1.0",
                isSidechain = false,
                parentUuid = null,
                content = "System message",
                isMeta = false,
                level = "info"
            )
        )

        entries.forEach { entry ->
            // Этот when должен компилироваться без ошибок exhaustiveness
            val result = when (entry) {
                is ClaudeLogEntry.SummaryEntry -> "summary: ${entry.summary}"
                is ClaudeLogEntry.UserEntry -> "user: ${entry.uuid}"
                is ClaudeLogEntry.AssistantEntry -> "assistant: ${entry.requestId}"
                is ClaudeLogEntry.SystemEntry -> "system: ${entry.content}"
            }

            println("Processed: $result")
        }
    }
}