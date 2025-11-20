package com.gromozeka.infrastructure.ai.parsers

import com.gromozeka.domain.model.Conversation

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
    fun parse(text: String): Conversation.Message.StructuredText
}