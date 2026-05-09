package com.gromozeka.domain.model.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiModelSpecTest {
    @Test
    fun resolvesConfiguredOpenAiSubscriptionModelSpec() {
        val spec = AiModelSpecs.byProviderAndId[AiModelSpec.Provider.OPEN_AI_SUBSCRIPTION to "gpt-5.4"]

        assertEquals(1_050_000, spec?.contextWindowTokens)
        assertEquals(840_000, spec?.autoCompactionThresholdTokens)
    }

    @Test
    fun supportsAbsoluteAutoCompactionThreshold() {
        val spec = AiModelSpec(
            id = "custom-model",
            provider = AiModelSpec.Provider.OPEN_AI,
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
                provider = AiModelSpec.Provider.OPEN_AI,
                contextWindowTokens = 100_000,
                autoCompaction = AiModelSpec.AutoCompaction.Absolute(100_001),
            )
        }
    }
}
