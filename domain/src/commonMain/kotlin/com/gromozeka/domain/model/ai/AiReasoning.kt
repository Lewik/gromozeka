package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable

/**
 * Static reasoning controls supported by a provider model.
 *
 * This belongs to [AiModelSpec] because allowed modes, efforts, and display
 * choices are model/provider capabilities, not agent behavior.
 */
@Serializable
data class AiReasoningCapabilities(
    val modes: Set<AiReasoningMode> = emptySet(),
    val efforts: Set<AiReasoningEffort> = emptySet(),
    val displays: Set<AiReasoningDisplay> = emptySet(),
) {
    init {
        require(modes.isNotEmpty() || efforts.isNotEmpty() || displays.isNotEmpty()) {
            "AI reasoning capabilities must declare at least one supported control"
        }
    }
}

/**
 * Runtime reasoning controls requested for one model call or configured model.
 *
 * Adapters must map this typed domain shape to provider-specific fields such as
 * Anthropic extended thinking, OpenAI reasoning effort, or Gemini thinking.
 */
@Serializable
data class AiReasoningConfig(
    val mode: AiReasoningMode? = null,
    val effort: AiReasoningEffort? = null,
    val display: AiReasoningDisplay? = null,
    val budgetTokens: Int? = null,
) {
    init {
        require(budgetTokens == null || budgetTokens > 0) { "AI reasoning budget tokens must be positive" }
    }
}

@Serializable
enum class AiReasoningMode {
    DISABLED,
    ADAPTIVE,
    TOKEN_BUDGET,
}

@Serializable
enum class AiReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
    MAX,
}

@Serializable
enum class AiReasoningDisplay {
    FULL,
    SUMMARIZED,
    OMITTED,
}
