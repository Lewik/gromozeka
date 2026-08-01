package com.gromozeka.infrastructure.ai.parsers

import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssistantResponseParserTest {
    @Test
    fun parsesAttentionFromJson() {
        val parsed = AssistantResponseParser.parse(
            rawText = """{"fullText":"Choose one","ttsText":"","voiceTone":"","attentionRequested":true}""",
            format = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
        )

        assertEquals("Choose one", parsed.fullText)
        assertTrue(parsed.attentionRequested)
    }

    @Test
    fun parsesAttentionFromStructuredXml() {
        val parsed = AssistantResponseParser.parse(
            rawText = """
                <response>
                  <visual>Choose one</visual>
                  <voice tone="neutral"></voice>
                  <attention>true</attention>
                </response>
            """.trimIndent(),
            format = AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED,
        )

        assertEquals("Choose one", parsed.fullText)
        assertTrue(parsed.attentionRequested)
    }

    @Test
    fun removesAttentionMarkerFromInlineXml() {
        val parsed = AssistantResponseParser.parse(
            rawText = "Choose <attention/>one. <tts tone=\"neutral\">Please choose.</tts>",
            format = AiModelConfiguration.AssistantResponseFormat.XML_INLINE,
        )

        assertEquals("Choose one. Please choose.", parsed.fullText)
        assertEquals("Please choose.", parsed.ttsText)
        assertTrue(parsed.attentionRequested)
    }

    @Test
    fun removesAttentionMarkerFromPlainText() {
        val parsed = AssistantResponseParser.parse(
            rawText = "Progress update. <ATTENTION />",
            format = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

        assertEquals("Progress update.", parsed.fullText)
        assertTrue(parsed.attentionRequested)
    }

    @Test
    fun defaultsToNoAttentionWhenMarkerIsAbsent() {
        val parsed = AssistantResponseParser.parse(
            rawText = "Routine progress",
            format = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

        assertFalse(parsed.attentionRequested)
    }

    @Test
    fun rejectsInvalidStructuredAttentionValue() {
        assertFailsWith<IllegalArgumentException> {
            AssistantResponseParser.parse(
                rawText = "<response><visual>Wait</visual><attention>maybe</attention></response>",
                format = AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED,
            )
        }
    }
}
