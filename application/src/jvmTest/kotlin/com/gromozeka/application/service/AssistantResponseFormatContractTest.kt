package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
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
