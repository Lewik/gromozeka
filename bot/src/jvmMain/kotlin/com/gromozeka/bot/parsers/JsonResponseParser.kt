package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.serialization.json.Json

/**
 * Parser for JSON format responses
 * Format defined in: /resources/prompts/json-format.md
 */
class JsonResponseParser : ResponseParser {

    private val json = Json {
        isLenient = true
    }

    override fun parse(text: String) = json.decodeFromString<ChatMessage.StructuredText>(text)
}