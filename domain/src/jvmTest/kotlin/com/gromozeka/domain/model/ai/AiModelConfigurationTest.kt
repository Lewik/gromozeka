package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val json = Json { encodeDefaults = true }
        val configured = configuration().copy(compaction = AiModelConfiguration.Compaction(75_000, compactionModelConfigurationId = AiModelConfiguration.Id("compactor")))
        val encoded = json.encodeToString(AiModelConfiguration.serializer(), configured)
        assertEquals(configured, json.decodeFromString(AiModelConfiguration.serializer(), encoded))
        kotlin.test.assertFalse(encoded.contains("percent"))
        val reset = kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(encoded)
            .let { it as kotlinx.serialization.json.JsonObject } + ("compaction" to kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals(AiModelConfiguration.Compaction(), json.decodeFromString(AiModelConfiguration.serializer(), reset.toString()).compaction)
    }

    @Test
    fun compactionWritesOnlyCurrentModelKey() {
        val compaction = AiModelConfiguration.Compaction(
            compactionModelConfigurationId = AiModelConfiguration.Id("compactor"),
        )

        val encoded = Json.encodeToString(compaction)
        assertTrue(encoded.contains("\"compactionModelConfigurationId\":\"compactor\""))
        assertFalse(encoded.contains("summaryModelConfigurationId"))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<AiModelConfiguration.Compaction>(
                """{"summaryModelConfigurationId":"legacy-compactor"}""",
            )
        }
    }

    @Test
    fun tokenCountingDefaultsAndAlternateReferenceAreExplicit() {
        val id = AiModelConfiguration.Id("counter")
        assertEquals(AiModelConfiguration.TokenCounting.Mode.CURRENT, configuration().compaction.tokenCounting.mode)
        assertFailsWith<IllegalArgumentException> { AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.MODEL_CONFIGURATION) }
        assertFailsWith<IllegalArgumentException> { AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.LOCAL, id) }
        assertFailsWith<IllegalArgumentException> { AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.CURRENT, id) }
        val json = Json { encodeDefaults = true }
        val configured = configuration().copy(compaction = AiModelConfiguration.Compaction(tokenCounting =
            AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.MODEL_CONFIGURATION, id)))
        assertEquals(configured, json.decodeFromString(AiModelConfiguration.serializer(), json.encodeToString(AiModelConfiguration.serializer(), configured)))
    }

    @Test fun proactiveThresholdIsFixedOptionalAndStrictlyBelowTheBlockingThreshold() {
        for (early in listOf(-1, 0, 100, 101)) assertFailsWith<IllegalArgumentException> {
            AiModelConfiguration.Compaction(thresholdTokens = 100, proactiveThresholdTokens = early)
        }
        assertFailsWith<IllegalArgumentException> { AiModelConfiguration.Compaction(proactiveThresholdTokens = 50) }
        val json = Json
        val policy = AiModelConfiguration.Compaction(thresholdTokens = 140_000, proactiveThresholdTokens = 100_000)
        assertEquals(policy, json.decodeFromString(AiModelConfiguration.Compaction.serializer(), json.encodeToString(AiModelConfiguration.Compaction.serializer(), policy)))
        assertEquals(null, json.decodeFromString(AiModelConfiguration.Compaction.serializer(), """{"thresholdTokens":140000}""").proactiveThresholdTokens)
        assertEquals(null, json.decodeFromString(AiModelConfiguration.Compaction.serializer(), "{}").proactiveThresholdTokens)
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
