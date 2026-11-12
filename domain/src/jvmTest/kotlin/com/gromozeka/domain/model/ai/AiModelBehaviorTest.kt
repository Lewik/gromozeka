package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiModelBehaviorTest {
    @Test
    fun `behavior survives catalog JSON persistence and copying to an exact gateway ID`() {
        val spec = spec().copy(behavior = AiModelBehavior(
            chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS,
            chatCompletionsReasoning = AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING,
            defaultReasoningMode = AiReasoningMode.ADAPTIVE,
            minimumToolReasoningEffort = AiReasoningEffort.LOW,
            maximumDisabledReasoningEffort = AiReasoningEffort.HIGH,
            supportsForcedToolChoice = false,
            anthropicThinkingPrefixDrop = true,
            chatSchemaEmptyToolCallsAsStop = true,
        ))
        val json = Json { encodeDefaults = true }
        assertEquals(spec, json.decodeFromString<AiModelSpec>(json.encodeToString(spec)))
        val gateway = spec.copy(id = "account/exact-model", provider = AiProvider.CUSTOM)
        assertEquals(spec.behavior, gateway.behavior)
        assertEquals("account/exact-model", json.decodeFromString<AiModelSpec>(json.encodeToString(gateway)).id)
    }

    @Test
    fun `old specs without behavior keep ordinary request defaults`() {
        val spec = Json.decodeFromString<AiModelSpec>("""{
            "id":"model","provider":"OPENAI","capabilities":["TEXT_GENERATION"],
            "limits":{"textGeneration":{"contextWindowTokens":10000}}
        }""")
        assertEquals(AiModelBehavior(), spec.behavior)
        assertEquals(false, spec.behavior.chatSchemaEmptyToolCallsAsStop)
    }

    @Test
    fun `invalid default mode and unreachable tool effort are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            spec().copy(reasoning = AiReasoningCapabilities(modes = setOf(AiReasoningMode.ADAPTIVE)),
                behavior = AiModelBehavior(defaultReasoningMode = AiReasoningMode.DISABLED))
        }
        assertFailsWith<IllegalArgumentException> {
            spec().copy(reasoning = AiReasoningCapabilities(efforts = setOf(AiReasoningEffort.LOW)),
                behavior = AiModelBehavior(minimumToolReasoningEffort = AiReasoningEffort.HIGH))
        }
    }

    @Test
    fun `generation behavior cannot be attached to an embedding-only model`() {
        assertFailsWith<IllegalArgumentException> {
            AiModelSpec("embedding", AiProvider.OPENAI, setOf(AiModelCapability.EMBEDDINGS),
                AiModelSpec.Limits(embeddings = AiModelSpec.Limits.Embeddings()),
                behavior = AiModelBehavior(defaultReasoningMode = AiReasoningMode.ADAPTIVE))
        }
    }

    private fun spec() = AiModelSpec("model", AiProvider.ANTHROPIC, setOf(AiModelCapability.TEXT_GENERATION),
        AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(10_000)))
}
