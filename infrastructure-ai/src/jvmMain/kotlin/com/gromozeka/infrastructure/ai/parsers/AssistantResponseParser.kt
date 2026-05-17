package com.gromozeka.infrastructure.ai.parsers

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelConfiguration

object AssistantResponseParser {
    private val json = JsonResponseParser()
    private val xmlStructured = XmlStructuredParser()
    private val xmlInline = XmlInlineParser()
    private val text = PlainTextParser()

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
