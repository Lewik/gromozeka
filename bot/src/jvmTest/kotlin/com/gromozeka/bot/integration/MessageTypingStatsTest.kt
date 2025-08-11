package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import org.junit.jupiter.api.Disabled

class MessageTypingStatsTest {

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
    fun checkTypedMessageFieldsCoverage() {
        
        val files = findTodaySessionFiles()
        var totalLogEntries = 0
        var entriesWithMessage = 0
        var typedSuccessfully = 0
        var typingFailed = 0

        files.forEach { file ->
            file.readLines().forEach { line ->
                if (line.trim().isNotEmpty()) {
                    try {
                        val entry = json.decodeFromString<ClaudeLogEntry>(line)
                        totalLogEntries++

                        val message = when (entry) {
                            is ClaudeLogEntry.UserEntry -> entry.message
                            is ClaudeLogEntry.AssistantEntry -> entry.message
                            else -> null
                        }

                        if (message != null) {
                            entriesWithMessage++
                            if (message is ClaudeLogEntry.Message) {
                                typedSuccessfully++
                            } else {
                                typingFailed++
                            }
                        }

                    } catch (e: Exception) {
                    }
                }
            }
        }

        println("\n=== MESSAGE TYPING STATISTICS ===")
        println("Files analyzed: ${files.size}")
        println("Total log entries: $totalLogEntries")
        println("Entries with message field: $entriesWithMessage")
        println("Successfully typed as Message: $typedSuccessfully")
        println("Failed to type (stayed as JsonElement): $typingFailed")

        if (entriesWithMessage > 0) {
            val typingSuccessRate = (typedSuccessfully * 100.0 / entriesWithMessage).toInt()
            println("Typing success rate: $typingSuccessRate%")
        }

        println("\nCurrent implementation: message fields are typed as Message when possible")
        println("Fallback: incompatible structures remain as null (graceful degradation)")
    }
}