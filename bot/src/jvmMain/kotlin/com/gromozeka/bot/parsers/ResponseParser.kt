package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage

/**
 * Base interface for parsing AI responses in different formats
 */
interface ResponseParser {
    /**
     * Parse AI response text into structured format
     * @param text Raw response text from AI
     * @return Structured text with TTS information
     * @throws Exception if parsing fails
     */
    fun parse(text: String): ChatMessage.StructuredText
}