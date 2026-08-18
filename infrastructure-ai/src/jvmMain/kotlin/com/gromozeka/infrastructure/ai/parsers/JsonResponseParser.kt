package com.gromozeka.infrastructure.ai.parsers

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.Json

/**
 * Parser for JSON format responses
 * Format defined in: /resources/prompts/json-format.md
 */
class JsonResponseParser : ResponseParser {

    private val json = Json {
        isLenient = true
    }

    override fun parse(text: String): Conversation.Message.StructuredText {
        val structured = json.decodeFromString<Conversation.Message.StructuredText>(text)
        return structured.copy(
            suggestedReplies = sanitizeSuggestedReplies(structured.suggestedReplies),
        )
    }
}
