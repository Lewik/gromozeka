package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeCodeSessionEntryV1_0
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ClaudeCodeSessionParser {

    private val json = Json { 
        ignoreUnknownKeys = false // strict parsing - fail on unknown fields
        isLenient = false
    }
    
    /**
     * Parse a single JSONL line into a session entry
     * @throws IllegalArgumentException if version doesn't match or type is unknown
     * @throws SerializationException if JSON structure doesn't match expected format
     */
    fun parseJsonLine(line: String): ClaudeCodeSessionEntryV1_0? {
        if (line.isBlank()) return null
        
        val jsonObject = json.parseToJsonElement(line).jsonObject
        val type = jsonObject["type"]?.jsonPrimitive?.content
        
        return when (type) {
            "summary" -> {
                json.decodeFromString<ClaudeCodeSessionEntryV1_0.Summary>(line)
            }
            "user", "assistant" -> {
                json.decodeFromString<ClaudeCodeSessionEntryV1_0.Message>(line)
            }
            null -> throw IllegalArgumentException("Missing 'type' field in JSON")
            else -> throw IllegalArgumentException("Unknown entry type: $type")
        }
    }
    
    /**
     * Parse entire session file
     * @return List of successfully parsed entries
     * @throws Exception if file cannot be read
     */
    fun parseSessionFile(file: File): List<ClaudeCodeSessionEntryV1_0> {
        if (!file.exists()) {
            throw IllegalArgumentException("Session file does not exist: ${file.absolutePath}")
        }
        
        return file.readLines()
            .mapIndexedNotNull { index, line ->
                try {
                    parseJsonLine(line)
                } catch (e: Exception) {
                    println("Error parsing line ${index + 1}: ${e.message}")
                    // Log but continue parsing other lines
                    null
                }
            }
    }
    
    /**
     * Parse session file strictly - fail on any parsing error
     */
    fun parseSessionFileStrict(file: File): List<ClaudeCodeSessionEntryV1_0> {
        if (!file.exists()) {
            throw IllegalArgumentException("Session file does not exist: ${file.absolutePath}")
        }
        
        return file.readLines()
            .mapIndexed { index, line ->
                try {
                    parseJsonLine(line) ?: throw IllegalArgumentException("Empty line at ${index + 1}")
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Failed to parse line ${index + 1}: ${e.message}", e
                    )
                }
            }
    }
}