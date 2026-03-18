package com.gromozeka.infrastructure.ai.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.gromozeka.domain.model.Step

/**
 * Jackson serializer for Step.Type enum.
 * 
 * Serializes to lowercase for LLM tool schema:
 * - COMMAND → "command"
 * - QUERY → "query"
 * etc.
 * 
 * This keeps domain layer clean (no Jackson dependencies).
 */
class StepTypeSerializer : JsonSerializer<Step.Type>() {
    override fun serialize(value: Step.Type, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.name.lowercase())
    }
}

/**
 * Jackson deserializer for Step.Type enum.
 * 
 * Deserializes from lowercase strings (case-insensitive):
 * - "command" → COMMAND
 * - "COMMAND" → COMMAND
 * - "Command" → COMMAND
 * 
 * Throws descriptive error if type is invalid.
 */
class StepTypeDeserializer : JsonDeserializer<Step.Type>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Step.Type {
        val value = p.text
        return try {
            Step.Type.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            val validTypes = Step.Type.entries.joinToString { it.name.lowercase() }
            throw IllegalArgumentException(
                "Invalid step type: '$value'. Valid types: $validTypes"
            )
        }
    }
}
