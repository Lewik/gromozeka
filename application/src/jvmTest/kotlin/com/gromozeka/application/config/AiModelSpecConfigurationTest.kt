package com.gromozeka.application.config

import com.gromozeka.domain.model.AiProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiModelSpecConfigurationTest {
    @Test
    fun loadsModelSpecsFromDefaultResource() = runBlocking {
        val provider = AiModelSpecConfiguration().aiModelSpecRepository()
        val spec = provider.find(AiProvider.OPENAI, "gpt-5.5")

        assertNotNull(spec)
        assertEquals(272_000, spec.contextWindowTokens)
        assertEquals(217_600, spec.autoCompactionThresholdTokens)
    }
}
