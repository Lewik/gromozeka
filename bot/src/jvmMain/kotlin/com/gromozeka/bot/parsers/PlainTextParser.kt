package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage

/**
 * Parser for plain text responses (no structure expected)
 * Used for debugging or when other parsers fail
 */
class PlainTextParser : ResponseParser {
    
    override fun parse(text: String): ChatMessage.StructuredText? {
        val trimmedText = text.trim()
        
        if (trimmedText.isEmpty()) {
            return null
        }
        
        // Return as plain text with no TTS metadata
        return ChatMessage.StructuredText(
            fullText = trimmedText,
            ttsText = null,
            voiceTone = null,
            wasConverted = true // Mark as converted from plain text
        )
    }
}