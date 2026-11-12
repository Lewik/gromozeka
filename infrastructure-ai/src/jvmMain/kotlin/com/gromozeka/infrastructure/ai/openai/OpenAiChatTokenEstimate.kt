package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiRequestTokenEstimate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Estimate the mapped Chat input, not the persisted display/history representation. */
internal object OpenAiChatTokenEstimate {
    fun input(body: JsonObject): Int = checked(
        (body.getValue("messages") as JsonArray).sumOf { message(it.jsonObject).toLong() } +
            listOf("tools", "tool_choice", "response_format").sumOf { key ->
                body[key]?.takeUnless { it == JsonNull }?.let { AiRequestTokenEstimate.text(it.toString()).toLong() } ?: 0L
            } + 8
    )

    fun output(messages: List<JsonObject>): Int = checked(messages.sumOf { message(it).toLong() })

    private fun message(message: JsonObject): Int {
        val blocks = message["thinking_blocks"]
        val estimated = if (blocks == null || blocks == JsonNull) message else {
            require(blocks is JsonArray) { "Chat thinking_blocks must be an array" }
            // Ciphertext/signatures are not tokenizer text. Compatible hidden reasoning is
            // accounted for by measured usage calibration, never by encrypted byte length.
            val readable = blocks.mapNotNull { value ->
                val block = value.jsonObject
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "thinking" -> JsonObject(mapOf(
                        "type" to JsonPrimitive("thinking"),
                        "thinking" to block.getValue("thinking"),
                    ))
                    "reasoning", "redacted_thinking" -> null
                    else -> error("Unsupported Chat thinking block type")
                }
            }
            JsonObject(message - "thinking_blocks" + if (readable.isEmpty()) emptyMap()
                else mapOf("thinking_blocks" to JsonArray(readable)))
        }
        return checked(AiRequestTokenEstimate.text(estimated.toString()).toLong() + 4)
    }

    private fun checked(tokens: Long): Int {
        require(tokens in 0..Int.MAX_VALUE.toLong()) { "Chat request is too large to estimate" }
        return tokens.toInt()
    }
}
