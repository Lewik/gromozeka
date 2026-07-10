package com.gromozeka.infrastructure.ai.openai.subscription

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class OpenAiSubscriptionModelProfile(
    val slug: String,
    val useResponsesLite: Boolean,
    val supportsReasoningSummaries: Boolean,
    val supportedReasoningEfforts: List<String>,
    val supportsVerbosity: Boolean,
    val defaultVerbosity: String?,
    val supportsParallelToolCalls: Boolean,
)

@Serializable
internal data class OpenAiSubscriptionModelsResponse(
    val models: List<OpenAiSubscriptionRemoteModel>,
)

@Serializable
internal data class OpenAiSubscriptionRemoteModel(
    val slug: String,
    @SerialName("supported_reasoning_levels")
    val supportedReasoningLevels: List<OpenAiSubscriptionReasoningLevel>,
    @SerialName("supports_reasoning_summaries")
    val supportsReasoningSummaries: Boolean,
    @SerialName("support_verbosity")
    val supportsVerbosity: Boolean,
    @SerialName("default_verbosity")
    val defaultVerbosity: String? = null,
    @SerialName("supports_parallel_tool_calls")
    val supportsParallelToolCalls: Boolean,
    @SerialName("use_responses_lite")
    val useResponsesLite: Boolean,
) {
    fun toProfile(): OpenAiSubscriptionModelProfile = OpenAiSubscriptionModelProfile(
        slug = slug,
        useResponsesLite = useResponsesLite,
        supportsReasoningSummaries = supportsReasoningSummaries,
        supportedReasoningEfforts = supportedReasoningLevels.map { it.effort },
        supportsVerbosity = supportsVerbosity,
        defaultVerbosity = defaultVerbosity,
        supportsParallelToolCalls = supportsParallelToolCalls,
    )
}

@Serializable
internal data class OpenAiSubscriptionReasoningLevel(
    val effort: String,
)
