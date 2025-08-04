package com.gromozeka.bot

import com.gromozeka.bot.model.StreamMessage
import com.gromozeka.bot.services.StreamToChatMessageMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Simple tests using real stream logs from ~/.gromozeka/streamlogs/
 * Tests both deserialization of StreamMessage and mapping to ChatMessage
 */
class RealStreamLogsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testStreamLogsDeserialization() {
        val streamLogsDir = File(System.getProperty("user.home"), ".gromozeka/streamlogs/Users-lewik-code-gromozeka-dev")
        
        if (!streamLogsDir.exists()) {
            println("Stream logs directory not found: ${streamLogsDir.absolutePath}")
            println("Run Gromozeka first to generate stream logs")
            return
        }
        
        val files = streamLogsDir.listFiles()
        if (files == null || files.isEmpty()) {
            println("No files found in stream logs directory")
            return
        }
        
        val jsonlFiles = files.filter { it.name.endsWith(".jsonl") }
        if (jsonlFiles.isEmpty()) {
            println("No .jsonl files found")
            return
        }
        
        // Test newest file
        val newestFile = jsonlFiles.maxByOrNull { it.lastModified() }!!
        println("Testing file: ${newestFile.name}")
        
        val lines = newestFile.readLines().filter { it.trim().isNotEmpty() }
        var successCount = 0
        var errorCount = 0
        
        for ((index, line) in lines.withIndex()) {
            try {
                val streamMessage = json.decodeFromString<StreamMessage>(line.trim())
                assertNotNull(streamMessage)
                successCount++
                
                println("Line $index: ${streamMessage::class.simpleName} - ${getMessageInfo(streamMessage)}")
                
            } catch (e: Exception) {
                errorCount++
                println("Line $index: ERROR - ${e.message}")
                println("  Content: ${line.take(100)}...")
            }
        }
        
        println("Results: $successCount successful, $errorCount errors out of ${lines.size} lines")
        
        // Expect at least 80% success rate
        val successRate = successCount.toDouble() / lines.size
        assertTrue(successRate >= 0.8, "Success rate too low: ${(successRate * 100).toInt()}%")
    }
    
    @Test
    fun testStreamLogsToChatMessageMapping() {
        val streamLogsDir = File(System.getProperty("user.home"), ".gromozeka/streamlogs/Users-lewik-code-gromozeka-dev")
        
        if (!streamLogsDir.exists()) {
            println("Stream logs directory not found")
            return
        }
        
        val files = streamLogsDir.listFiles()
        if (files == null || files.isEmpty()) {
            println("No files found in stream logs directory")
            return
        }
        
        val jsonlFiles = files.filter { it.name.endsWith(".jsonl") }
        if (jsonlFiles.isEmpty()) {
            println("No .jsonl files found")
            return
        }
        
        // Test newest file
        val newestFile = jsonlFiles.maxByOrNull { it.lastModified() }!!
        println("Testing mapping for file: ${newestFile.name}")
        
        val lines = newestFile.readLines().filter { it.trim().isNotEmpty() }
        var deserializeSuccess = 0
        var mappingSuccess = 0
        
        for ((index, line) in lines.withIndex()) {
            try {
                // Step 1: Deserialize StreamMessage
                val streamMessage = json.decodeFromString<StreamMessage>(line.trim())
                deserializeSuccess++
                
                // Step 2: Map to ChatMessage
                val chatMessage = StreamToChatMessageMapper.mapToChatMessage(streamMessage)
                mappingSuccess++
                
                assertNotNull(chatMessage)
                assertNotNull(chatMessage.uuid)
                assertTrue(chatMessage.content.isNotEmpty())
                
                println("Line $index: ${streamMessage::class.simpleName} -> ${chatMessage.messageType}")
                
            } catch (e: Exception) {
                println("Line $index: MAPPING ERROR - ${e.message}")
            }
        }
        
        println("Mapping results: $deserializeSuccess deserialized, $mappingSuccess mapped out of ${lines.size} lines")
        
        // Expect high mapping success rate
        val mappingRate = mappingSuccess.toDouble() / lines.size
        assertTrue(mappingRate >= 0.8, "Mapping rate too low: ${(mappingRate * 100).toInt()}%")
    }
    
    private fun getMessageInfo(streamMessage: StreamMessage): String {
        return when (streamMessage) {
            is StreamMessage.SystemStreamMessage -> "subtype=${streamMessage.subtype}"
            is StreamMessage.UserStreamMessage -> "sessionId=${streamMessage.sessionId}"
            is StreamMessage.AssistantStreamMessage -> "sessionId=${streamMessage.sessionId}"
            is StreamMessage.ResultStreamMessage -> "subtype=${streamMessage.subtype}, error=${streamMessage.isError}"
        }
    }
}