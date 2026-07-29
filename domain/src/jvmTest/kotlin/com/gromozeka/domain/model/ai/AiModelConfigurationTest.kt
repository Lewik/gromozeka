package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiModelConfigurationTest {
    @Test
    fun usesModelDefaultEmbeddingDimensionsWithoutRequestOverride() {
        val configuration = configuration()

        assertEquals(2_560, configuration.resolveEmbeddingDimensions(modelSpec()))
    }

    @Test
    fun usesRequestedEmbeddingDimensionsAsOutputOverride() {
        val configuration = configuration(requestedEmbeddingDimensions = 1_536)

        assertEquals(1_536, configuration.resolveEmbeddingDimensions(modelSpec()))
    }

    @Test
    fun rejectsNonPositiveRequestedEmbeddingDimensions() {
        assertFailsWith<IllegalArgumentException> {
            configuration(requestedEmbeddingDimensions = 0)
        }
    }

    private fun configuration(
        requestedEmbeddingDimensions: Int? = null,
    ) =
        AiModelConfiguration(
            id = AiModelConfiguration.Id("qwen-embedding"),
            connectionId = AiConnection.Id("qwen"),
            providerModelId = "qwen3-embedding-4b",
            displayName = "Qwen3 Embedding 4B",
            requestedEmbeddingDimensions = requestedEmbeddingDimensions,
        )

    private fun modelSpec() =
        AiModelSpec(
            id = "qwen3-embedding-4b",
            provider = AiProvider.CUSTOM,
            capabilities = setOf(AiModelCapability.EMBEDDINGS),
            limits = AiModelSpec.Limits(
                embeddings = AiModelSpec.Limits.Embeddings(
                    dimensions = 2_560,
                    maxInputTokens = 32_768,
                )
            ),
        )
}
