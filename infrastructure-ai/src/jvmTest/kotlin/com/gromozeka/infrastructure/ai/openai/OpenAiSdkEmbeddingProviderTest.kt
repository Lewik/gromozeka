package com.gromozeka.infrastructure.ai.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiSdkEmbeddingProviderTest {
    @Test
    fun omitsDimensionsWhenUsingProviderDefault() {
        val params = embeddingCreateParams(
            modelId = "qwen3-embedding-4b",
            inputs = listOf("test"),
            requestedDimensions = null,
        )

        assertTrue(params.dimensions().isEmpty)
    }

    @Test
    fun sendsExplicitRequestedDimensions() {
        val params = embeddingCreateParams(
            modelId = "text-embedding-3-large",
            inputs = listOf("test"),
            requestedDimensions = 1_536,
        )

        assertFalse(params.dimensions().isEmpty)
        assertEquals(1_536L, params.dimensions().get())
    }
}
