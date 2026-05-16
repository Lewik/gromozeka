package com.gromozeka.server

import com.gromozeka.domain.model.AiProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiModelSpecConfigurationTest {
    @Test
    fun loadsModelSpecsFromDefaultResource() = runBlocking {
        val provider = AiModelSpecConfiguration().aiModelSpecRepository()
        val spec = provider.find(AiProvider.OPENAI, "gpt-5.4")

        assertNotNull(spec)
        assertEquals(1_050_000, spec.contextWindowTokens)
        assertEquals(840_000, spec.autoCompactionThresholdTokens)
    }
}
