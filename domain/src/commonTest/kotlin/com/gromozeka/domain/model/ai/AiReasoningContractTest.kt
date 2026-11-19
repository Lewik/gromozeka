package com.gromozeka.domain.model.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AiReasoningContractTest {
    @Test
    fun reasoningModesExposeOnlyAdaptiveAndDisabled() {
        assertEquals(setOf(AiReasoningMode.ADAPTIVE, AiReasoningMode.DISABLED), AiReasoningMode.entries.toSet())
    }

    @Test
    fun reasoningWireContainsOnlyModeEffortAndDisplay() {
        val json = Json { encodeDefaults = true }
        for (mode in AiReasoningMode.entries) {
            for (effort in AiReasoningEffort.entries) {
                for (display in AiReasoningDisplay.entries) {
                    val configuration = AiReasoningConfig(mode, effort, display)
                    val encoded = json.encodeToJsonElement(AiReasoningConfig.serializer(), configuration)
                    assertEquals(setOf("mode", "effort", "display"), encoded.jsonObject.keys)
                    assertEquals(configuration, json.decodeFromJsonElement(AiReasoningConfig.serializer(), encoded))
                }
            }
        }
    }
}
