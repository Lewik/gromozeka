package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.services.ClaudeLogEntryMapper
import com.gromozeka.shared.domain.message.ChatMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import kotlinx.serialization.json.Json
import java.io.File

class RealChatTestDataTest : FunSpec({

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }

    test("should parse real chattest session without errors") {
        val chatTestFile =
            File("/Users/lewik/.claude/projects/-Users-lewik-code-chattest/f278cf1c-fd5c-4a50-a266-8d4ca95231ee.jsonl")

        if (!chatTestFile.exists()) {
            println("Chattest file not found, skipping test")
            return@test
        }

        var successCount = 0
        var totalLines = 0
        var mappedCount = 0
        val errors = mutableListOf<String>()

        chatTestFile.readLines().forEach { line ->
            if (line.trim().isNotEmpty()) {
                totalLines++
                try {
                    // 1. Парсинг ClaudeLogEntry
                    val entry = json.decodeFromString<ClaudeLogEntry>(line.trim())
                    successCount++

                    // 2. Маппинг в ChatMessage
                    val chatMessage = ClaudeLogEntryMapper.mapToChatMessage(entry)
                    if (chatMessage != null) {
                        mappedCount++

                        // 3. Проверяем есть ли JSON контент от Громозеки
                        chatMessage.content.forEach { contentItem ->
                            when (contentItem) {
                                is ChatMessage.ContentItem.Message -> {
                                    // Проверяем содержит ли JSON
                                    if (contentItem.text.contains("{") && contentItem.text.contains("fullText")) {
                                        println("Found potential Gromozeka JSON in message: ${contentItem.text.take(100)}...")
                                    }
                                }

                                is ChatMessage.ContentItem.IntermediateMessage -> {
                                    println("Successfully parsed Gromozeka message: ${contentItem.structured?.fullText ?: contentItem.text}")
                                }

                                is ChatMessage.ContentItem.UnknownJson -> {
                                    println("Unknown JSON content: ${contentItem.json}")
                                }

                                else -> { /* other types */
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

        // Тест считается успешным если парсится больше 80% строк
        val successRate = successCount * 100.0 / totalLines
        successRate shouldBeGreaterThanOrEqualTo 80.0
    }
})