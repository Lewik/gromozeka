package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage

/**
 * Parser for inline XML/TTS tags in responses
 * Expected format: Regular text with <tts tone="...">TTS content</tts> tags
 */
class XmlInlineParser : ResponseParser {
    
    private val ttsPattern = Regex("""<tts(?:\s+tone="([^"]*)")?\s*>(.*?)</tts>""", RegexOption.DOT_MATCHES_ALL)
    
    override fun parse(text: String): ChatMessage.StructuredText? {
        return try {
            val trimmedText = text.trim()
            
            // Find all TTS tags
            val matches = ttsPattern.findAll(trimmedText).toList()
            
            if (matches.isEmpty()) {
                // No TTS tags found, return as plain text
                return ChatMessage.StructuredText(
                    fullText = trimmedText,
                    ttsText = null,
                    voiceTone = null
                )
            }
            
            // Extract TTS content and tone
            val ttsTexts = mutableListOf<String>()
            var lastTone: String? = null
            
            for (match in matches) {
                val tone = match.groupValues[1].takeIf { it.isNotEmpty() }
                val ttsContent = match.groupValues[2].trim()
                
                if (ttsContent.isNotEmpty()) {
                    ttsTexts.add(ttsContent)
                    if (tone != null) {
                        lastTone = tone
                    }
                }
            }
            
            // Remove TTS tags from full text for visual display
            val fullText = trimmedText.replace(ttsPattern, "$2")
            
            ChatMessage.StructuredText(
                fullText = fullText,
                ttsText = ttsTexts.joinToString(" ").takeIf { it.isNotEmpty() },
                voiceTone = lastTone
            )
            
        } catch (e: Exception) {
            println("[XmlInlineParser] Failed to parse inline XML: ${e.message}")
            println("  Text: ${text.take(100)}${if (text.length > 100) "..." else ""}")
            null
        }
    }
}