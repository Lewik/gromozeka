package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiUsagePriceCatalogTest {
    @Test
    fun pricesDirectApiCallsAndLeavesSubscriptionsUnpriced() {
        val usage = AiUsage(promptTokens = 1_000_000, completionTokens = 1_000_000)

        val direct = AiUsagePriceCatalog.price(
            connectionKind = AiConnection.Kind.OPENAI_API,
            modelId = "gpt-5.6-sol",
            usage = usage,
            contextInputTokens = 100_000,
        )
        val subscription = AiUsagePriceCatalog.price(
            connectionKind = AiConnection.Kind.OPENAI_SUBSCRIPTION,
            modelId = "gpt-5.6-sol",
            usage = usage,
            contextInputTokens = 100_000,
        )

        assertEquals(35_000_000_000L, direct?.estimatedCostNanoUsd)
        assertNull(subscription)
    }

    @Test
    fun appliesOpenAiLongContextTierToTheRecordedSnapshot() {
        val price = AiUsagePriceCatalog.price(
            connectionKind = AiConnection.Kind.OPENAI_API,
            modelId = "gpt-5.6-sol",
            usage = AiUsage(promptTokens = 1_000_000, completionTokens = 1_000_000),
            contextInputTokens = 272_001,
        )

        assertEquals(10_000_000_000L, price?.inputNanoUsdPerMillion)
        assertEquals(45_000_000_000L, price?.outputNanoUsdPerMillion)
        assertEquals(55_000_000_000L, price?.estimatedCostNanoUsd)
    }
}
