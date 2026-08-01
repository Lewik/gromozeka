package com.gromozeka.infrastructure.ai.parsers

import com.gromozeka.domain.model.Conversation


/**
 * Parser for plain text responses (no structure expected)
 * Format defined in: /resources/prompts/plain-text.md
 */
class PlainTextParser : ResponseParser {

    override fun parse(text: String): Conversation.Message.StructuredText {
        val attentionRequested = ATTENTION_MARKER.containsMatchIn(text)
        return Conversation.Message.StructuredText(
            fullText = text.replace(ATTENTION_MARKER, "").trim(),
            ttsText = null,
            voiceTone = null,
            attentionRequested = attentionRequested,
            failedToParse = false,
        )
    }

    private companion object {
        val ATTENTION_MARKER = Regex("""<attention\s*/>""", RegexOption.IGNORE_CASE)
    }
}
