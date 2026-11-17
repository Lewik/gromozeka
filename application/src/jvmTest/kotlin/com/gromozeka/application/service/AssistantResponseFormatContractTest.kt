package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssistantResponseFormatContractTest {
    @Test
    fun jsonSchemaRequiresAttentionDecision() {
        val responseFormat = assertIs<AiResponseFormat.JsonSchema>(
            AssistantResponseFormatContract.runtimeResponseFormat(
                AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA
            )
        )

        assertTrue("attentionRequested" in responseFormat.schema.getValue("properties").jsonObject)
        assertTrue("suggestedReplies" in responseFormat.schema.getValue("properties").jsonObject)
        assertTrue(
            responseFormat.schema.getValue("required").jsonArray
                .map { it.jsonPrimitive.content }
                .contains("attentionRequested")
        )
        assertTrue(
            responseFormat.schema.getValue("required").jsonArray
                .map { it.jsonPrimitive.content }
                .contains("suggestedReplies")
        )
    }

    @Test
    fun everyTextFormatExplainsHowToRequestAttention() {
        AiModelConfiguration.AssistantResponseFormat.entries.forEach { format ->
            val instruction = requireNotNull(AssistantResponseFormatContract.instruction(format))
            assertContains(instruction.lowercase(), "attention")
        }
    }

    @Test
    fun everyTextFormatExplainsCopyableMarkdownBlocks() {
        AiModelConfiguration.AssistantResponseFormat.entries.forEach { format ->
            val instruction = requireNotNull(AssistantResponseFormatContract.instruction(format))
            assertContains(instruction, "gromozeka-copy")
        }
    }

    @Test
    fun everyTextFormatExplainsSuggestedReplies() {
        AiModelConfiguration.AssistantResponseFormat.entries.forEach { format ->
            val instruction = requireNotNull(AssistantResponseFormatContract.instruction(format))
            assertContains(instruction, "suggested")
        }
    }

    @Test
    fun customInstructionReplacesOnlyTheEditablePartInEveryFormat() {
        val custom = "CUSTOM_INSTRUCTION_WITHOUT_FIELD_NAME"
        for (format in AiModelConfiguration.AssistantResponseFormat.entries) {
            val instruction = requireNotNull(AssistantResponseFormatContract.instruction(format, inlinePrompt = custom))
            assertContains(instruction, custom)
            assertFalse(com.gromozeka.domain.model.UserProfile.SuggestedRepliesSettings.DEFAULT_PROMPT in instruction)
            if (format == AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA) {
                assertContains(instruction, "suggestedReplies: $custom")
            } else {
                assertContains(instruction, "suggested_replies: $custom")
                assertContains(instruction, "<suggested_replies>")
            }
            assertFalse(custom in requireNotNull(AssistantResponseFormatContract.instruction(format, false, custom)))
        }
        val schema = assertIs<AiResponseFormat.JsonSchema>(AssistantResponseFormatContract.runtimeResponseFormat(AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA))
        assertEquals("array", schema.schema.getValue("properties").jsonObject.getValue("suggestedReplies").jsonObject.getValue("type").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            AssistantResponseFormatContract.instruction(AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA, inlinePrompt = " ")
        }
    }

    @Test
    fun suggestedRepliesCanBeRemovedFromEveryResponseContract() {
        AiModelConfiguration.AssistantResponseFormat.entries.forEach { format ->
            val instruction = requireNotNull(
                AssistantResponseFormatContract.instruction(format, includeSuggestedReplies = false)
            )
            assertFalse("suggested" in instruction.lowercase())
        }

        val responseFormat = assertIs<AiResponseFormat.JsonSchema>(
            AssistantResponseFormatContract.runtimeResponseFormat(
                AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
                includeSuggestedReplies = false,
            )
        )
        assertFalse("suggestedReplies" in responseFormat.schema.getValue("properties").jsonObject)
        assertFalse(
            responseFormat.schema.getValue("required").jsonArray
                .map { it.jsonPrimitive.content }
                .contains("suggestedReplies")
        )
    }
}
