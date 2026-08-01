package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object AssistantResponseFormatContract {
    fun runtimeResponseFormat(format: AiModelConfiguration.AssistantResponseFormat): AiResponseFormat =
        when (format) {
            AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA -> AiResponseFormat.JsonSchema(
                name = "gromozeka_assistant_response",
                description = "Structured assistant response for visual UI and optional text-to-speech.",
                schema = jsonSchema,
                strict = true,
            )

            AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED,
            AiModelConfiguration.AssistantResponseFormat.XML_INLINE,
            AiModelConfiguration.AssistantResponseFormat.TEXT -> AiResponseFormat.Text
        }

    fun instruction(format: AiModelConfiguration.AssistantResponseFormat): String? =
        when (format) {
            AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA -> """
                Return the assistant answer as the configured structured response.
                fullText: complete visible answer, markdown is allowed.
                ttsText: short speakable main answer, or empty string when nothing should be spoken.
                voiceTone: short English voice style hint for TTS, or empty string when ttsText is empty.
                attentionRequested: true only when the user should look now to answer, decide, act, or notice an important result or failure. Use false for ordinary progress, background work, monitor setup, and routine completion that needs no user attention.
            """.trimIndent()

            AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED -> """
                Return exactly one XML response:
                <response>
                  <visual>Complete visible answer, markdown is allowed.</visual>
                  <voice tone="short English voice style">Short speakable main answer, or empty when nothing should be spoken.</voice>
                  <attention>true when the user should look now; otherwise false</attention>
                </response>
                Request attention only for a needed answer, decision, action, important result, or failure. Use false for ordinary progress, background work, monitor setup, and routine completion.
            """.trimIndent()

            AiModelConfiguration.AssistantResponseFormat.XML_INLINE -> """
                Write the visible answer normally. Wrap only the short speakable voice part in inline <tts tone="short English voice style">...</tts> tags.
                If nothing should be spoken, omit <tts> tags.
                Add <attention/> anywhere when the user should look now to answer, decide, act, or notice an important result or failure. Omit it for ordinary progress, background work, monitor setup, and routine completion. The marker is removed before display.
            """.trimIndent()

            AiModelConfiguration.AssistantResponseFormat.TEXT -> """
                Write the visible answer normally.
                Add <attention/> anywhere when the user should look now to answer, decide, act, or notice an important result or failure. Omit it for ordinary progress, background work, monitor setup, and routine completion. The marker is removed before display.
            """.trimIndent()
        }

    private val jsonSchema = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("fullText") {
                put("type", "string")
                put("description", "Complete visible answer. Markdown is allowed.")
            }
            putJsonObject("ttsText") {
                put("type", "string")
                put("description", "Short speakable main answer. Empty string means no automatic TTS.")
            }
            putJsonObject("voiceTone") {
                put("type", "string")
                put("description", "Short English voice style hint. Empty string when ttsText is empty.")
            }
            putJsonObject("attentionRequested") {
                put("type", "boolean")
                put(
                    "description",
                    "True only when the user should look now to answer, decide, act, or notice an important result or failure. False for ordinary progress, background work, monitor setup, and routine completion.",
                )
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("fullText"))
            add(JsonPrimitive("ttsText"))
            add(JsonPrimitive("voiceTone"))
            add(JsonPrimitive("attentionRequested"))
        }
    }
}
