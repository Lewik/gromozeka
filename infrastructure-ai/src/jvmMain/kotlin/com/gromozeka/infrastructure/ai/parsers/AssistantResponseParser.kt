package com.gromozeka.infrastructure.ai.parsers

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AssistantResponseParser {
    private val json = JsonResponseParser()
    private val xmlStructured = XmlStructuredParser()
    private val xmlInline = XmlInlineParser()
    private val text = PlainTextParser()

    fun providerBlock(raw: JsonElement): Conversation.Message.ContentItem.System =
        Conversation.Message.ContentItem.System(
            level = Conversation.Message.ContentItem.System.SystemLevel.INFO,
            content = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), redactProviderSignatures(raw)),
        )

    private fun redactProviderSignatures(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, item) ->
            if (key in setOf("encrypted_content", "signature", "thoughtSignature", "reasoningOpaque")) JsonPrimitive("[opaque provider state]")
            else redactProviderSignatures(item)
        })
        is JsonArray -> JsonArray(value.map(::redactProviderSignatures))
        else -> value
    }

    fun parseProgress(
        rawText: String,
        format: AiModelConfiguration.AssistantResponseFormat,
    ): Conversation.Message.StructuredText =
        runCatching { parse(rawText, format) }.getOrElse { text.parse(rawText) }

    fun parse(
        rawText: String,
        format: AiModelConfiguration.AssistantResponseFormat,
    ): Conversation.Message.StructuredText =
        when (format) {
            AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA -> json.parse(rawText)
            AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED -> xmlStructured.parse(rawText)
            AiModelConfiguration.AssistantResponseFormat.XML_INLINE -> xmlInline.parse(rawText)
            AiModelConfiguration.AssistantResponseFormat.TEXT -> text.parse(rawText)
        }
}
