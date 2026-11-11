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

    @Test
    fun compactionDefaultsToDisabledAndUsesTheCurrentModel() {
        assertEquals(AiModelConfiguration.Compaction(), configuration().compaction)
        assertFailsWith<IllegalArgumentException> { AiModelConfiguration.Compaction(thresholdTokens = 0) }
        assertFailsWith<IllegalArgumentException> { AiModelConfiguration.Compaction(thresholdTokens = -1) }
    }

    @Test
    fun compactionRoundTripsOnlyAbsoluteTokensAndExplicitReset() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val configured = configuration().copy(compaction = AiModelConfiguration.Compaction(75_000, AiModelConfiguration.Id("summary")))
        val encoded = json.encodeToString(AiModelConfiguration.serializer(), configured)
        assertEquals(configured, json.decodeFromString(AiModelConfiguration.serializer(), encoded))
        kotlin.test.assertFalse(encoded.contains("percent"))
        val reset = kotlinx.serialization.json.JsonObject(kotlinx.serialization.json.Json.parseToJsonElement(encoded)
            .let { it as kotlinx.serialization.json.JsonObject } + ("compaction" to kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals(AiModelConfiguration.Compaction(), json.decodeFromString(AiModelConfiguration.serializer(), reset.toString()).compaction)
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
