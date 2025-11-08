package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Disabled
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ClaudeLogEntryDeserializationTest {

    private val json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    private fun findAllSessionFiles(): List<File> {
        val projectsDir = File("/Users/slavik/.claude/projects")

        val brokenFiles = setOf(
            "d6f9c172-d0bd-4eb3-84a6-daf8132472d5.jsonl",
            "c1d25871-c2f7-48cc-80f0-14cd75e45ac7.jsonl"
        )

        return projectsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .filter { it.name !in brokenFiles }
            .toList()
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun overviewOfAllSessionFiles() {
        val allSessionFiles = findAllSessionFiles()
        println("Found ${allSessionFiles.size} session files:")
        allSessionFiles.groupBy { it.parentFile.name }.forEach { (project, files) ->
            println("  $project: ${files.size} files")
        }
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun statisticsOfParsedEntryTypes() {
        val allSessionFiles = findAllSessionFiles()
        val typeStats = mutableMapOf<String, Int>()
        var totalLines = 0
        var successfullyParsed = 0

        allSessionFiles.forEach { file ->
            file.readLines().forEach { line ->
                if (line.trim().isNotEmpty()) {
                    totalLines++
                    try {
                        val entry = json.decodeFromString<ClaudeLogEntry>(line)
                        successfullyParsed++
                        val typeName = when (entry) {
                            is ClaudeLogEntry.UserEntry -> "user"
                            is ClaudeLogEntry.AssistantEntry -> "assistant"
                            is ClaudeLogEntry.SystemEntry -> "system"
                            is ClaudeLogEntry.SummaryEntry -> "summary"
                            is ClaudeLogEntry.FileHistorySnapshotEntry -> "file-history-snapshot"
                            is ClaudeLogEntry.QueueOperationEntry -> "queue-operation"
                        }
                        typeStats[typeName] = typeStats.getOrDefault(typeName, 0) + 1
                    } catch (e: Exception) {
                    }
                }
            }
        }

        println("\nParsing Statistics:")
        println("Total lines: $totalLines")
        println("Successfully parsed: $successfullyParsed (${(successfullyParsed * 100.0 / totalLines).toInt()}%)")
        println("\nEntry types distribution:")
        typeStats.forEach { (type, count) ->
            println("  $type: $count (${(count * 100.0 / successfullyParsed).toInt()}%)")
        }
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun deserializeAllSessionFiles() {
        val allSessionFiles = findAllSessionFiles()
        allSessionFiles.forEach { file ->
            if (!file.exists()) {
                println("File not found: ${file.absolutePath}")
                return@forEach
            }

            val lines = file.readLines()
            var successCount = 0
            var totalLines = 0

            lines.forEach { line ->
                if (line.trim().isNotEmpty()) {
                    totalLines++
                    try {
                        val entry = json.decodeFromString<ClaudeLogEntry>(line)
                        successCount++

                        assertTrue(entry is ClaudeLogEntry)

                        when (entry) {
                            is ClaudeLogEntry.UserEntry -> {
                                assertTrue(entry.uuid.isNotEmpty())
                                assertTrue(entry.sessionId?.value?.isNotEmpty() ?: true)
                            }

                            is ClaudeLogEntry.AssistantEntry -> {
                                assertTrue(entry.requestId?.isNotEmpty() ?: true)
                            }

                            is ClaudeLogEntry.SystemEntry -> {
                                assertTrue(entry.content.isNotEmpty())
                            }

                            is ClaudeLogEntry.SummaryEntry -> {
                                assertTrue(entry.summary.isNotEmpty())
                            }

                            is ClaudeLogEntry.FileHistorySnapshotEntry -> {
                                assertTrue(entry.messageId.isNotEmpty())
                                assertTrue(entry.snapshot.timestamp.isNotEmpty())
                            }

                            is ClaudeLogEntry.QueueOperationEntry -> {
                                assertTrue(entry.operation.isNotEmpty())
                                assertTrue(entry.timestamp.isNotEmpty())
                            }
                        }

                    } catch (e: Exception) {
                        println("Failed to parse line in ${file.name}: ${e.message}")
                        println("Line: $line")
                    }
                }
            }

            println("Successfully parsed $successCount/$totalLines lines from ${file.parentFile.name}/${file.name}")
        }
    }
}