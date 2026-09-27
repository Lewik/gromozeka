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
    fun pricesOpus55IncludingPromptCacheAndLeavesClaudeCodeUnpriced() {
        val usage = AiUsage(promptTokens = 1_000_000, completionTokens = 1_000_000,
            cacheCreationTokens = 1_000_000, cacheReadTokens = 1_000_000)
        val price = AiUsagePriceCatalog.price(AiConnection.Kind.ANTHROPIC_API, "claude-opus-5-5", usage, 1_000_000)
        assertEquals(4_000_000_000L, price?.inputNanoUsdPerMillion)
        assertEquals(5_000_000_000L, price?.cacheCreationNanoUsdPerMillion)
        assertEquals(200_000_000L, price?.cacheReadNanoUsdPerMillion)
        assertEquals(20_000_000_000L, price?.outputNanoUsdPerMillion)
        assertEquals(29_200_000_000L, price?.estimatedCostNanoUsd)
        assertEquals("2026-09-22", price?.catalogVersion)
        assertNull(AiUsagePriceCatalog.price(AiConnection.Kind.CLAUDE_CODE, "claude-opus-5-5", usage, 1_000_000))
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
