package com.gromozeka.infrastructure.ai.openai.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenAiSubscriptionKeyTest {
    @Test
    fun keepsKeysAtOrBelowOpenAiLimitUnchanged() {
        val key = "a".repeat(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH)

        assertEquals(key, key.toOpenAiSubscriptionKey())
    }

    @Test
    fun hashesKeysAboveOpenAiLimitToStableBoundedKey() {
        val key = "memory:memory_enrich_context:standalone:" + "019e235e-6569-7902-9701-c323b9feb118"

        val normalized = key.toOpenAiSubscriptionKey()

        assertEquals(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH, normalized.length)
        assertTrue(normalized.startsWith("gzk:"))
        assertEquals(normalized, key.toOpenAiSubscriptionKey())
        assertNotEquals(key, normalized)
    }
}
