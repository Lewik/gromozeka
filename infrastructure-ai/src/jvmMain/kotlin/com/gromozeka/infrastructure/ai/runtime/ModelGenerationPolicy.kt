package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiRequestTokenEstimate
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiModelBehavior
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiReasoningCapabilities
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiToolChoice

internal class ModelGenerationPolicy(
    val behavior: AiModelBehavior = AiModelBehavior(),
    private val reasoningCapabilities: AiReasoningCapabilities? = null,
    private val limits: AiModelSpec.Limits.TextGeneration? = null,
) {
    constructor(spec: AiModelSpec) : this(spec.behavior, spec.reasoning, spec.limits.textGeneration)

    fun outputTokenLimit(options: AiRuntimeOptions): Int = options.maxOutputTokens ?: requireNotNull(limits?.maxOutputTokens) {
        "Required output budget needs a declared model output token limit"
    }

    fun requiredOutputTokens(request: AiRuntimeRequest): Int {
        request.options.maxOutputTokens?.let { return it }
        val modelLimits = requireNotNull(limits) { "Required output budget needs explicit model limits" }
        val maximum = outputTokenLimit(request.options)
        val remaining = modelLimits.contextWindowTokens - AiRequestTokenEstimate.input(request)
        require(remaining > 0) { "Request leaves no context budget for output" }
        return minOf(maximum, remaining)
    }

    fun reasoning(options: AiRuntimeOptions, hasTools: Boolean): AiReasoningConfig? {
        require(behavior.supportsForcedToolChoice || options.toolChoice == AiToolChoice.Auto ||
            options.toolChoice == AiToolChoice.None) {
            "Model route does not support forced tool choice; use auto or none"
        }
        val configured = options.reasoning
        val mode = configured?.mode ?: behavior.defaultReasoningMode
        val reasoning = if (configured != null || mode != null) {
            (configured ?: AiReasoningConfig()).copy(mode = mode)
        } else null
        require(mode == null || reasoningCapabilities?.modes.isNullOrEmpty() || mode in reasoningCapabilities.modes) {
            "Model route does not support reasoning mode $mode"
        }
        require(reasoning?.budgetTokens == null || mode == AiReasoningMode.TOKEN_BUDGET) {
            "Reasoning token budget requires TOKEN_BUDGET mode"
        }
        reasoning?.effort?.let { effort ->
            require(reasoningCapabilities?.efforts.isNullOrEmpty() || effort in reasoningCapabilities.efforts) {
                "Model route does not support reasoning effort $effort"
            }
            val maximumDisabledEffort = behavior.maximumDisabledReasoningEffort
            require(mode != AiReasoningMode.DISABLED || maximumDisabledEffort == null ||
                effort <= maximumDisabledEffort) {
                "Model route cannot combine disabled thinking with effort $effort"
            }
        }
        reasoning?.display?.let { display ->
            require(reasoningCapabilities?.displays.isNullOrEmpty() || display in reasoningCapabilities.displays) {
                "Model route does not support reasoning display $display"
            }
        }
        if (hasTools && options.toolChoice != AiToolChoice.None) {
            behavior.minimumToolReasoningEffort?.let { minimum ->
                val effort = reasoning?.effort
                require(mode != AiReasoningMode.DISABLED && effort != null && effort >= minimum) {
                    "Model route requires explicit reasoning effort of at least $minimum for tools; reasoning was not enabled or raised automatically"
                }
            }
        }
        return reasoning
    }
}
