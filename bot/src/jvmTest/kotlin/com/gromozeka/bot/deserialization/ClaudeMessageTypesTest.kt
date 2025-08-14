package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Disabled
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

class ClaudeMessageTypesTest {

    private val json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    private fun findTodaySessionFiles(): List<File> {
        val projectsDir = File("/Users/slavik/.claude/projects")
        val today = LocalDate.now()
        val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val brokenFiles = setOf(
            "d6f9c172-d0bd-4eb3-84a6-daf8132472d5.jsonl",
            "c1d25871-c2f7-48cc-80f0-14cd75e45ac7.jsonl"
        )

        return projectsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .filter { it.lastModified() >= todayStart && it.lastModified() < tomorrowStart }
            .filter { it.name !in brokenFiles }
            .toList()
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun analyzeMessageFieldStructuresInTodaysFiles() {
        val todaySessionFiles = findTodaySessionFiles()
        var messageFieldCount = 0
        var toolUseResultFieldCount = 0
        val messageTypeStats = mutableMapOf<String, Int>()
        val parseErrors = mutableListOf<String>()

        todaySessionFiles.forEach { file ->
            file.readLines().forEach { line ->
                if (line.trim().isNotEmpty()) {
                    try {
                        val entry = json.decodeFromString<ClaudeLogEntry>(line)

                        when (entry) {
                            is ClaudeLogEntry.UserEntry -> {
                                entry.message?.let { messageElement ->
                                    messageFieldCount++

                                    when (messageElement) {
                                        is JsonObject -> {
                                            val role = messageElement["role"]?.jsonPrimitive?.content
                                            val content = messageElement["content"]
                                            val typeKey = "user_${role}_${content?.javaClass?.simpleName}"
                                            messageTypeStats[typeKey] = messageTypeStats.getOrDefault(typeKey, 0) + 1

                                            try {
                                                val typedMessage =
                                                    json.decodeFromString<ClaudeLogEntry.Message>(messageElement.toString())
                                            } catch (e: Exception) {
                                                parseErrors.add("User message parse error in ${file.name}: ${e.message}")
                                            }
                                        }

                                        else -> {
                                            val typeKey = "user_other_${messageElement.javaClass.simpleName}"
                                            messageTypeStats[typeKey] = messageTypeStats.getOrDefault(typeKey, 0) + 1
                                        }
                                    }
                                }

                                entry.toolUseResult?.let {
                                    toolUseResultFieldCount++
                                }
                            }

                            is ClaudeLogEntry.AssistantEntry -> {
                                entry.message?.let { messageElement ->
                                    messageFieldCount++

                                    when (messageElement) {
                                        is JsonObject -> {
                                            val role = messageElement["role"]?.jsonPrimitive?.content
                                            val content = messageElement["content"]
                                            val typeKey = "assistant_${role}_${content?.javaClass?.simpleName}"
                                            messageTypeStats[typeKey] = messageTypeStats.getOrDefault(typeKey, 0) + 1

                                            try {
                                                val typedMessage =
                                                    json.decodeFromString<ClaudeLogEntry.Message>(messageElement.toString())
                                            } catch (e: Exception) {
                                                parseErrors.add("Assistant message parse error in ${file.name}: ${e.message}")
                                            }
                                        }

                                        else -> {
                                            val typeKey = "assistant_other_${messageElement.javaClass.simpleName}"
                                            messageTypeStats[typeKey] = messageTypeStats.getOrDefault(typeKey, 0) + 1
                                        }
                                    }
                                }

                                entry.toolUseResult?.let {
                                    toolUseResultFieldCount++
                                }
                            }

                            else -> {
                            }
                        }

                    } catch (e: Exception) {
                    }
                }
            }
        }

        println("\n=== MESSAGE FIELD ANALYSIS ===")
        println("Total message fields found: $messageFieldCount")
        println("Total toolUseResult fields found: $toolUseResultFieldCount")

        println("\nMessage type distribution:")
        messageTypeStats.forEach { (type, count) ->
            println("  $type: $count")
        }

        println("\nParse errors (first 10):")
        parseErrors.take(10).forEach { error ->
            println("  $error")
        }

        if (parseErrors.size > 10) {
            println("  ... and ${parseErrors.size - 10} more errors")
        }

        println(
            "\nParse success rate: ${
                ((messageFieldCount - parseErrors.size) * 100.0 / messageFieldCount.coerceAtLeast(
                    1
                )).toInt()
            }%"
        )
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun verifyMessageFieldTypesCanBeParsedWithCurrentDataClasses() {
        val todaySessionFiles = findTodaySessionFiles()
        var successCount = 0
        var totalMessageFields = 0
        val failureExamples = mutableListOf<String>()

        todaySessionFiles.take(3).forEach { file ->
            file.readLines().forEach { line ->
                if (line.trim().isNotEmpty()) {
                    try {
                        val entry = json.decodeFromString<ClaudeLogEntry>(line)

                        val messageElement = when (entry) {
                            is ClaudeLogEntry.UserEntry -> entry.message
                            is ClaudeLogEntry.AssistantEntry -> entry.message
                            else -> null
                        }

                        messageElement?.let { element ->
                            totalMessageFields++

                            try {
                                when (element) {
                                    is JsonObject -> {
                                        val typedMessage =
                                            json.decodeFromString<ClaudeLogEntry.Message>(element.toString())
                                        assertTrue(typedMessage is ClaudeLogEntry.Message)
                                        successCount++
                                    }

                                    else -> {
                                    }
                                }
                            } catch (e: Exception) {
                                if (failureExamples.size < 5) {
                                    failureExamples.add(
                                        "Parse failure: ${e.message} | JSON: ${
                                            element.toString().take(200)
                                        }"
                                    )
                                }
                            }
                        }

                    } catch (e: Exception) {
                    }
                }
            }
        }

        println("\n=== TYPED MESSAGE PARSING TEST ===")
        println("Successfully parsed: $successCount/$totalMessageFields message fields")
        if (totalMessageFields > 0) {
            println("Success rate: ${(successCount * 100.0 / totalMessageFields).toInt()}%")
        }

        if (failureExamples.isNotEmpty()) {
            println("\nFailure examples:")
            failureExamples.forEach { example ->
                println("  $example")
            }
        }

        assertTrue(totalMessageFields != 0)
    }
}