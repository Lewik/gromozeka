package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.openai.models.ReasoningEffort

internal fun AiReasoningConfig.openAiEffort(): ReasoningEffort? {
    require(mode != AiReasoningMode.TOKEN_BUDGET && budgetTokens == null) {
        "OpenAI effort encoding does not support reasoning token budgets"
    }
    // Omitting effort can leave provider-default reasoning enabled. An explicit off must reach the API.
    if (mode == AiReasoningMode.DISABLED) return ReasoningEffort.NONE
    require(mode != AiReasoningMode.ADAPTIVE || effort != null) {
        "OpenAI adaptive reasoning requires an explicit effort; no default effort was chosen automatically"
    }
    return effort?.let { ReasoningEffort.of(it.name.lowercase()) }
}
