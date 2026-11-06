package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable

/** Explicit request rules for a model route, independent of its provider model ID. */
@Serializable
data class AiModelBehavior(
    val chatCompletionsTokenLimit: ChatCompletionsTokenLimit = ChatCompletionsTokenLimit.MAX_COMPLETION_TOKENS,
    val chatCompletionsReasoning: ChatCompletionsReasoning = ChatCompletionsReasoning.REASONING_EFFORT,
    val defaultReasoningMode: AiReasoningMode? = null,
    val minimumToolReasoningEffort: AiReasoningEffort? = null,
    val maximumDisabledReasoningEffort: AiReasoningEffort? = null,
    val supportsForcedToolChoice: Boolean = true,
    val anthropicThinkingPrefixDrop: Boolean = false,
) {
    @Serializable
    enum class ChatCompletionsTokenLimit {
        MAX_COMPLETION_TOKENS,
        MAX_TOKENS,
        OMIT,
    }

    @Serializable
    enum class ChatCompletionsReasoning {
        REASONING_EFFORT,
        ANTHROPIC_THINKING,
    }
}
