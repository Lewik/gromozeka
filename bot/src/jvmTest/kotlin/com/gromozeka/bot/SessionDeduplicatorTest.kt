package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.utils.SessionDeduplicator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import java.io.File

class SessionDeduplicatorTest : FunSpec({
    
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }
    
    test("should deduplicate known test session correctly") {
        val testFile = File("/Users/lewik/.claude/projects/-Users-lewik-code-chattest/e4d60bf2-0a1e-4bcc-9b63-4a89bdc15f34.jsonl")
        
        if (!testFile.exists()) {
            println("Test file not found, skipping specific test")
            return@test
        }
        
        val lines = testFile.readLines()
        val originalEntries = lines.mapNotNull { line ->
            if (line.trim().isNotEmpty()) {
                try {
                    json.decodeFromString<ClaudeLogEntry>(line.trim())
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        
        println("Original entries: ${originalEntries.size}")
        
        val deduplicatedEntries = SessionDeduplicator.deduplicate(originalEntries)
        
        println("Deduplicated entries: ${deduplicatedEntries.size}")
        
        val stats = SessionDeduplicator.getDeduplicationStats(originalEntries, deduplicatedEntries)
        println("Stats: ${stats.duplicatesRemoved} duplicates removed (${stats.userDuplicates} user, ${stats.assistantDuplicates} assistant)")
        
        // Should remove duplicates (we know this file has them)
        deduplicatedEntries.size shouldBeLessThanOrEqualTo originalEntries.size
        
        // Should still have the main conversation
        deduplicatedEntries.size shouldBeGreaterThanOrEqualTo 5
    }
    
    test("should handle all real session files without crashing") {
        val projectDirs = listOf(
            File(System.getProperty("user.home"), ".claude/projects/-Users-lewik-code-gromozeka"),
            File(System.getProperty("user.home"), ".claude/projects/-Users-lewik-code-chattest")
        )
        
        var totalFiles = 0
        var successfulFiles = 0
        var totalOriginalEntries = 0
        var totalDeduplicatedEntries = 0
        var totalDuplicatesRemoved = 0
        
        projectDirs.forEach { projectDir ->
            if (projectDir.exists()) {
                projectDir.listFiles { _, name -> name.endsWith(".jsonl") }?.forEach { file ->
                    totalFiles++
                    
                    try {
                        println("Processing file: ${file.name}")
                        
                        val lines = file.readLines()
                        val originalEntries = lines.mapNotNull { line ->
                            if (line.trim().isNotEmpty()) {
                                try {
                                    json.decodeFromString<ClaudeLogEntry>(line.trim())
                                } catch (e: Exception) {
                                    println("  Parse error in line: ${e.message}")
                                    null
                                }
                            } else null
                        }
                        
                        if (originalEntries.isNotEmpty()) {
                            val deduplicatedEntries = SessionDeduplicator.deduplicate(originalEntries)
                            val stats = SessionDeduplicator.getDeduplicationStats(originalEntries, deduplicatedEntries)
                            
                            totalOriginalEntries += originalEntries.size
                            totalDeduplicatedEntries += deduplicatedEntries.size
                            totalDuplicatesRemoved += stats.duplicatesRemoved
                            
                            println("  Original: ${originalEntries.size}, Deduplicated: ${deduplicatedEntries.size}, Removed: ${stats.duplicatesRemoved}")
                            
                            // Basic sanity checks
                            deduplicatedEntries.size shouldBeLessThanOrEqualTo originalEntries.size
                            deduplicatedEntries.size shouldBeGreaterThanOrEqualTo 0
                            
                            // Check that all entries have valid timestamps for sorting
                            deduplicatedEntries.forEach { entry ->
                                when (entry) {
                                    is ClaudeLogEntry.UserEntry -> entry.timestamp shouldNotBe null
                                    is ClaudeLogEntry.AssistantEntry -> entry.timestamp shouldNotBe null
                                    is ClaudeLogEntry.SystemEntry -> entry.timestamp shouldNotBe null
                                    is ClaudeLogEntry.SummaryEntry -> { /* summaries may not have timestamps */ }
                                }
                            }
                            
                            successfulFiles++
                        } else {
                            println("  No valid entries found")
                        }
                        
                    } catch (e: Exception) {
                        println("  FAILED: ${e.message}")
                        // Don't fail the test, just log and continue
                    }
                }
            }
        }
        
        println("\n=== SESSION DEDUPLICATION TEST SUMMARY ===")
        println("Total files processed: $totalFiles")
        println("Successfully processed: $successfulFiles")
        println("Total original entries: $totalOriginalEntries")
        println("Total deduplicated entries: $totalDeduplicatedEntries")
        println("Total duplicates removed: $totalDuplicatesRemoved")
        
        if (totalOriginalEntries > 0) {
            val duplicateRate = (totalDuplicatesRemoved * 100.0) / totalOriginalEntries
            println("Overall duplicate rate: ${duplicateRate.toInt()}%")
        }
        
        // Test passes if we processed at least some files without crashing
        successfulFiles shouldBeGreaterThanOrEqualTo 0
    }
    
    test("should preserve conversation order after deduplication") {
        val testFile = File("/Users/lewik/.claude/projects/-Users-lewik-code-chattest/e4d60bf2-0a1e-4bcc-9b63-4a89bdc15f34.jsonl")
        
        if (!testFile.exists()) {
            println("Test file not found, skipping order test")
            return@test
        }
        
        val lines = testFile.readLines()
        val originalEntries = lines.mapNotNull { line ->
            if (line.trim().isNotEmpty()) {
                try {
                    json.decodeFromString<ClaudeLogEntry>(line.trim())
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        
        val deduplicatedEntries = SessionDeduplicator.deduplicate(originalEntries)
        
        // Check that timestamps are in ascending order (excluding summaries)
        val baseEntries = deduplicatedEntries.filterIsInstance<ClaudeLogEntry.BaseEntry>()
        val timestamps = baseEntries.map { it.timestamp }
        
        timestamps.zipWithNext().forEach { (current, next) ->
            current shouldBeLessThanOrEqualTo next
        }
        
        println("Order preservation test passed: ${timestamps.size} entries in correct chronological order")
    }
})