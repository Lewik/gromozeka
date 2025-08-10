package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage


/**
 * Parser for inline XML/TTS tags in responses
 * Format defined in: /resources/prompts/xml-inline.md
 */
class XmlInlineParser : ResponseParser {

    private val ttsPattern = Regex("""<tts(?:\s+tone="([^"]*)")?\s*>(.*?)</tts>""", RegexOption.DOT_MATCHES_ALL)

    override fun parse(text: String): ChatMessage.StructuredText {
        val trimmedText = text.trim()

        val matches = ttsPattern.findAll(trimmedText).toList()

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

        // Remove TTS tags from full text for visual display, or use original text if no tags
        val fullText = if (matches.isNotEmpty()) {
            trimmedText.replace(ttsPattern, "$2")
        } else {
            trimmedText
        }

        return ChatMessage.StructuredText(
            fullText = fullText,
            ttsText = ttsTexts.joinToString(" ").takeIf { it.isNotEmpty() },
            voiceTone = lastTone
        )
    }
}