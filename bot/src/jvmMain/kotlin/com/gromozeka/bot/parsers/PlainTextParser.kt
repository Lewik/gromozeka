package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage


/**
 * Parser for plain text responses (no structure expected)
 * Format defined in: /resources/prompts/plain-text.md
 */
class PlainTextParser : ResponseParser {

    override fun parse(text: String) = ChatMessage.StructuredText(
        fullText = text,
        ttsText = null,
        voiceTone = null,
        failedToParse = false
    )
}