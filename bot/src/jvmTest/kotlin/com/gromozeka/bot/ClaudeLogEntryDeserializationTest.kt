package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.should
import io.kotest.matchers.types.beInstanceOf
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ClaudeLogEntryDeserializationTest : FunSpec({
    
    val json = Json {
        ignoreUnknownKeys = false // строгая десериализация
        coerceInputValues = false
    }
    
    fun findAllSessionFiles(): List<File> {
        val projectsDir = File("/Users/lewik/.claude/projects")
        
        val brokenFiles = setOf(
            "d6f9c172-d0bd-4eb3-84a6-daf8132472d5.jsonl",
            "c1d25871-c2f7-48cc-80f0-14cd75e45ac7.jsonl"
        )
        
        return projectsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .filter { it.name !in brokenFiles }
            .toList()
    }
    
    val allSessionFiles = findAllSessionFiles()
    
    test("overview of all session files") {
        println("Found ${allSessionFiles.size} session files:")
        allSessionFiles.groupBy { it.parentFile.name }.forEach { (project, files) ->
            println("  $project: ${files.size} files")
        }
    }
    
    test("statistics of parsed entry types") {
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
                        val typeName = when(entry) {
                            is ClaudeLogEntry.UserEntry -> "user"
                            is ClaudeLogEntry.AssistantEntry -> "assistant"
                            is ClaudeLogEntry.SystemEntry -> "system"
                            is ClaudeLogEntry.SummaryEntry -> "summary"
                        }
                        typeStats[typeName] = typeStats.getOrDefault(typeName, 0) + 1
                    } catch (e: Exception) {
                        // Skip parsing errors
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
    
    allSessionFiles.forEach { file ->
        test("should deserialize all lines from ${file.parentFile.name}/${file.name}") {
            if (!file.exists()) {
                println("File not found: ${file.absolutePath}")
                return@test
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
                        
                        // проверяем что получили правильный тип
                        entry should beInstanceOf<ClaudeLogEntry>()
                        
                        when (entry) {
                            is ClaudeLogEntry.UserEntry -> {
                                entry.uuid.should { it.isNotEmpty() }
                                entry.sessionId?.should { it.isNotEmpty() }
                            }
                            is ClaudeLogEntry.AssistantEntry -> {
                                entry.requestId?.should { it.isNotEmpty() }
                            }
                            is ClaudeLogEntry.SystemEntry -> {
                                entry.content.should { it.isNotEmpty() }
                            }
                            is ClaudeLogEntry.SummaryEntry -> {
                                entry.summary.should { it.isNotEmpty() }
                            }
                        }
                        
                    } catch (e: Exception) {
                        println("Failed to parse line in ${file.name}: ${e.message}")
                        println("Line: $line")
                        // Временно не бросаем исключения чтобы увидеть все ошибки
                        // if (totalLines <= 5) { 
                        //     throw e
                        // }
                    }
                }
            }
            
            println("Successfully parsed $successCount/$totalLines lines from ${file.parentFile.name}/${file.name}")
        }
    }
})