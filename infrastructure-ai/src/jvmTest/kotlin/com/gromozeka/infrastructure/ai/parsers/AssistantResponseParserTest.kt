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

    @Test
    fun parsesSuggestedRepliesFromEveryResponseFormat() {
        val cases = listOf(
            AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA to
                """{"fullText":"Choose one","ttsText":"","voiceTone":"","attentionRequested":false,"suggestedReplies":["Yes","No"]}""",
            AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED to """
                <response>
                  <visual>Choose one</visual>
                  <voice></voice>
                  <attention>false</attention>
                  <suggested_replies><reply>Yes</reply><reply>No</reply></suggested_replies>
                </response>
            """.trimIndent(),
            AiModelConfiguration.AssistantResponseFormat.XML_INLINE to
                "Choose one. <suggested_replies><reply>Yes</reply><reply>No</reply></suggested_replies>",
            AiModelConfiguration.AssistantResponseFormat.TEXT to
                "Choose one. <suggested_replies><reply>Yes</reply><reply>No</reply></suggested_replies>",
        )

        cases.forEach { (format, rawText) ->
            val parsed = AssistantResponseParser.parse(rawText, format)
            val expectedText = when (format) {
                AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
                AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED -> "Choose one"

                AiModelConfiguration.AssistantResponseFormat.XML_INLINE,
                AiModelConfiguration.AssistantResponseFormat.TEXT -> "Choose one."
            }

            assertEquals(expectedText, parsed.fullText)
            assertEquals(listOf("Yes", "No"), parsed.suggestedReplies)
        }
    }

    @Test
    fun constrainsSuggestedRepliesWithoutAffectingVisibleText() {
        val parsed = AssistantResponseParser.parse(
            rawText = """
                Continue.
                <suggested_replies>
                  <reply>  Run
                  tests  </reply>
                  <reply>run tests</reply>
                  <reply>Commit</reply>
                  <reply>Push</reply>
                  <reply>Deploy</reply>
                  <reply>${"x".repeat(121)}</reply>
                </suggested_replies>
            """.trimIndent(),
            format = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

        assertEquals("Continue.", parsed.fullText)
        assertEquals(listOf("Run tests", "Commit", "Push", "Deploy"), parsed.suggestedReplies)
    }
}
