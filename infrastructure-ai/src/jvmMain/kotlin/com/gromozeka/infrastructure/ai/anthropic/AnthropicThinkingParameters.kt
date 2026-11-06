package com.gromozeka.infrastructure.ai.anthropic

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.ThinkingConfigParam
import com.gromozeka.domain.model.ai.AiModelBehavior
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningMode

internal object AnthropicThinkingParameters {
    const val PREFIX_DROP_BETA = "thinking-binding-controls-2026-08-01"
    private const val DEFAULT_BUDGET_TOKENS = 16_000

    fun create(reasoning: AiReasoningConfig?, behavior: AiModelBehavior, maxOutputTokens: Int): ThinkingConfigParam? {
        require(!behavior.anthropicThinkingPrefixDrop || reasoning?.mode == AiReasoningMode.ADAPTIVE) {
            "Anthropic thinking prefix-drop behavior requires adaptive thinking"
        }
        return when (reasoning?.mode) {
            null -> null
            AiReasoningMode.DISABLED -> ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build())
            AiReasoningMode.ADAPTIVE -> ThinkingConfigParam.ofAdaptive(
                ThinkingConfigAdaptive.builder()
                    .display(if (reasoning.display == AiReasoningDisplay.OMITTED) ThinkingConfigAdaptive.Display.OMITTED else ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .apply {
                        if (behavior.anthropicThinkingPrefixDrop) {
                            // Compaction, edits and dynamic prompts change the bound prefix. This opt-in
                            // asks the provider to drop only invalidated thinking instead of rejecting the turn.
                            putAdditionalProperty("block_binding", JsonValue.from(mapOf("prefix_mismatch_behavior" to "drop_block")))
                        }
                    }.build()
            )
            AiReasoningMode.TOKEN_BUDGET -> {
                val budget = reasoning.budgetTokens ?: DEFAULT_BUDGET_TOKENS
                require(budget < maxOutputTokens) { "Anthropic thinking budget must be smaller than the output token limit" }
                ThinkingConfigParam.ofEnabled(
                    ThinkingConfigEnabled.builder().budgetTokens(budget.toLong())
                        .display(if (reasoning.display == AiReasoningDisplay.OMITTED) ThinkingConfigEnabled.Display.OMITTED else ThinkingConfigEnabled.Display.SUMMARIZED)
                        .build()
                )
            }
        }
    }
}
