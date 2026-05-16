package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiModelSpecTest {
    @Test
    fun decodesModelSpecFromJson() {
        val spec = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }.decodeFromString<AiModelSpec>(
            """
            {
              "id": "gpt-5.5",
              "provider": "OPENAI",
              "contextWindowTokens": 1050000,
              "maxOutputTokens": 128000,
              "reasoning": {
                "modes": ["ADAPTIVE"],
                "efforts": ["LOW", "MEDIUM", "HIGH"],
                "displays": ["SUMMARIZED", "OMITTED"]
              },
              "autoCompaction": {
                "type": "percent",
                "value": 80
              }
            }
            """.trimIndent()
        )

        assertEquals(1_050_000, spec.contextWindowTokens)
        assertEquals(128_000, spec.maxOutputTokens)
        assertEquals(setOf(AiReasoningMode.ADAPTIVE), spec.reasoning?.modes)
        assertEquals(setOf(AiReasoningEffort.LOW, AiReasoningEffort.MEDIUM, AiReasoningEffort.HIGH), spec.reasoning?.efforts)
        assertEquals(setOf(AiReasoningDisplay.SUMMARIZED, AiReasoningDisplay.OMITTED), spec.reasoning?.displays)
        assertEquals(840_000, spec.autoCompactionThresholdTokens)
    }

    @Test
    fun supportsAbsoluteAutoCompactionThreshold() {
        val spec = AiModelSpec(
            id = "custom-model",
            provider = AiProvider.OPENAI,
            contextWindowTokens = 100_000,
            autoCompaction = AiModelSpec.AutoCompaction.Absolute(75_000),
        )

        assertEquals(75_000, spec.autoCompactionThresholdTokens)
    }

    @Test
    fun rejectsAutoCompactionThresholdOutsideContextWindow() {
        assertFailsWith<IllegalArgumentException> {
            AiModelSpec(
                id = "broken-model",
                provider = AiProvider.OPENAI,
                contextWindowTokens = 100_000,
                autoCompaction = AiModelSpec.AutoCompaction.Absolute(100_001),
            )
        }
    }

    @Test
    fun rejectsMaxOutputTokensOutsideContextWindow() {
        assertFailsWith<IllegalArgumentException> {
            AiModelSpec(
                id = "broken-model",
                provider = AiProvider.OPENAI,
                contextWindowTokens = 100_000,
                maxOutputTokens = 100_001,
            )
        }
    }
}
