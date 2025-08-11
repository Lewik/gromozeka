package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Disabled

class RealDataDeserializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun testRealSessionDataDeserialization() {
        
        val sessionFiles = listOf(
            "8259ddd2-5761-41e6-ae94-2060663f3128.jsonl",
            "5eeceea8-df50-4031-813e-380cfe33be32.jsonl",
            "169a5857-47bd-4061-af0b-dd2e5dc2561e.jsonl"
        )

        val gromozekaProjectDir = File(System.getProperty("user.home"), ".claude/projects/-Users-lewik-code-gromozeka")

        sessionFiles.forEach { filename ->
            val file = File(gromozekaProjectDir, filename)
            if (file.exists()) {
                println("Testing file: $filename")

                val lines = file.readLines().take(10)
                var successCount = 0
                var errorCount = 0

                lines.forEachIndexed { index, line ->
                    if (line.trim().isNotEmpty()) {
                        try {
                            val entry = json.decodeFromString<ClaudeLogEntry>(line.trim())
                            assertNotNull(entry)
                            successCount++

                            when (entry) {
                                is ClaudeLogEntry.UserEntry -> {
                                    println("  Line $index: UserEntry - uuid=${entry.uuid}")
                                    if (entry.message != null) {
                                        println("    Has message (JsonElement)")
                                    }
                                    if (entry.toolUseResult != null) {
                                        println("    Has toolUseResult (JsonElement)")
                                    }
                                }

                                is ClaudeLogEntry.AssistantEntry -> {
                                    println("  Line $index: AssistantEntry - uuid=${entry.uuid}")
                                    if (entry.message != null) {
                                        println("    Has message (JsonElement)")
                                    }
                                }

                                is ClaudeLogEntry.SystemEntry -> {
                                    println("  Line $index: SystemEntry - level=${entry.level}")
                                }

                                is ClaudeLogEntry.SummaryEntry -> {
                                    println("  Line $index: SummaryEntry - summary=${entry.summary.take(50)}...")
                                }
                            }

                        } catch (e: Exception) {
                            errorCount++
                            println("  Line $index: ERROR - ${e.message}")
                        }
                    }
                }

                println("File $filename: $successCount successful, $errorCount errors")
                println()
            } else {
                println("File $filename not found, skipping")
            }
        }
    }
}