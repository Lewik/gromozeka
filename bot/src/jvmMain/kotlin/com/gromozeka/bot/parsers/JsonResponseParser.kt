package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

/**
 * Parser for JSON format responses
 * Expected format: {"fullText": "...", "ttsText": "...", "voiceTone": "..."}
 */
class JsonResponseParser : ResponseParser {
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true 
    }
    
    override fun parse(text: String): ChatMessage.StructuredText? {
        return try {
            // Try to parse as StructuredText directly
            json.decodeFromString<ChatMessage.StructuredText>(text)
        } catch (e: SerializationException) {
            println("[JsonResponseParser] Failed to parse JSON: ${e.message}")
            println("  Text: ${text.take(100)}${if (text.length > 100) "..." else ""}")
            null
        } catch (e: Exception) {
            println("[JsonResponseParser] Unexpected error: ${e.message}")
            null
        }
    }
}