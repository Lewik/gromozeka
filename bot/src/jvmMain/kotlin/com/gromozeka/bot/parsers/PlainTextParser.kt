package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.Conversation


/**
 * Parser for plain text responses (no structure expected)
 * Format defined in: /resources/prompts/plain-text.md
 */
class PlainTextParser : ResponseParser {

    override fun parse(text: String) = Conversation.Message.StructuredText(
        fullText = text,
        ttsText = null,
        voiceTone = null,
        failedToParse = false
    )
}