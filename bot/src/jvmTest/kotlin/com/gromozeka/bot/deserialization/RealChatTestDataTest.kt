package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.services.ClaudeLogEntryMapper
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Disabled
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RealChatTestDataTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun shouldParseRealChattestSessionWithoutErrors() {

        val chatTestFile =
            File("/Users/slavik/.claude/projects/-Users-slavik-code-chattest/f278cf1c-fd5c-4a50-a266-8d4ca95231ee.jsonl")

        if (!chatTestFile.exists()) {
            println("Chattest file not found, skipping test")
            return
        }

        var successCount = 0
        var totalLines = 0
        var mappedCount = 0
        val errors = mutableListOf<String>()

        chatTestFile.readLines().forEach { line ->
            if (line.trim().isNotEmpty()) {
                totalLines++
                try {
                    val entry = json.decodeFromString<ClaudeLogEntry>(line.trim())
                    successCount++

                    val chatMessage = ClaudeLogEntryMapper.mapToChatMessage(entry)
                    if (chatMessage != null) {
                        mappedCount++

                        chatMessage.content.forEach { contentItem ->
                            when (contentItem) {
                                is ChatMessage.ContentItem.UserMessage -> {
                                    if (contentItem.text.contains("{") && contentItem.text.contains("fullText")) {
                                        println("Found potential Gromozeka JSON in message: ${contentItem.text.take(100)}...")
                                    }
                                }

                                is ChatMessage.ContentItem.AssistantMessage -> {
                                    println("Successfully parsed Gromozeka message: ${contentItem.structured.fullText}")
                                }

                                is ChatMessage.ContentItem.UnknownJson -> {
                                    println("Unknown JSON content: ${contentItem.json}")
                                }

                                else -> {
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    errors.add("Line $totalLines: ${e.message} - ${line.take(100)}...")
                }
            }
        }

        println("\nReal ChatTest Data Parsing Results:")
        println("Total lines: $totalLines")
        println("Successfully parsed: $successCount (${(successCount * 100.0 / totalLines).toInt()}%)")
        println("Successfully mapped: $mappedCount (${(mappedCount * 100.0 / totalLines).toInt()}%)")
        println("Errors: ${errors.size}")

        if (errors.isNotEmpty()) {
            println("\nFirst few errors:")
            errors.take(3).forEach { println("  $it") }
        }

        val successRate = successCount * 100.0 / totalLines
        assertTrue(successRate >= 80.0)
    }
}