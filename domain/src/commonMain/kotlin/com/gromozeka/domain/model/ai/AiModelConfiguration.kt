package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * User-configurable model setup for one provider model through one connection.
 *
 * Agents should usually depend on configuration ids, not raw provider model
 * names. This allows the user to retarget a configuration to a different
 * endpoint or model without rewriting agent definitions.
 *
 * @property connectionId connection used to execute this model.
 * @property providerModelId exact provider-specific model id passed to the adapter.
 * @property roles intended usage categories. Role filtering is advisory but should
 * be respected by UI defaults and automatic runtime selection.
 * @property defaultParameters optional model-level defaults. Per-call options may
 * override them when the application workflow explicitly needs that.
 */
@Serializable
data class AiModelConfiguration(
    val id: Id,
    val connectionId: AiConnection.Id,
    val providerModelId: String,
    val displayName: String,
    val enabled: Boolean = true,
    val roles: Set<Role> = setOf(Role.CHAT),
    val defaultParameters: DefaultParameters = DefaultParameters(),
) {
    init {
        require(providerModelId.isNotBlank()) { "AI provider model id must not be blank" }
        require(displayName.isNotBlank()) { "AI model configuration display name must not be blank" }
        require(roles.isNotEmpty()) { "AI model configuration must have at least one role" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "AI model configuration id must not be blank" }
        }
    }

    /**
     * High-level usage category for this model configuration.
     *
     * Roles are not provider capabilities by themselves. They describe how
     * Gromozeka intends to use this configuration when selecting defaults or
     * filtering settings.
     */
    @Serializable
    enum class Role {
        CHAT,
        MEMORY,
        TRANSLATION,
        SPEECH_TO_TEXT,
        TEXT_TO_SPEECH,
        EMBEDDINGS,
    }

    /**
     * Default generation parameters attached to this model configuration.
     *
     * `null` means "do not constrain this value at the configuration level";
     * provider adapters may then use their normal defaults or per-request
     * options.
     */
    @Serializable
    data class DefaultParameters(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
        val reasoning: AiReasoningConfig? = null,
        val timeoutSeconds: Int? = null,
    ) {
        init {
            require(maxOutputTokens == null || maxOutputTokens > 0) {
                "AI model configuration max output tokens must be positive"
            }
            require(timeoutSeconds == null || timeoutSeconds > 0) {
                "AI model configuration timeout seconds must be positive"
            }
        }
    }
}

/**
 * Agent or workflow overrides applied on top of a selected model configuration.
 */
@Serializable
data class AiRuntimeOverrides(
    val maxOutputTokens: Int? = null,
    val reasoning: AiReasoningConfig? = null,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "AI runtime max output tokens must be positive" }
    }
}

/**
 * Runtime choice requested by an agent or workflow.
 *
 * It is deliberately a separate value object even though it currently contains
 * only [modelConfigurationId]. Future domain-safe selection data belongs here:
 * fallback configurations, capability requirements, routing policy, or
 * temporary parameter overrides. Implementations must resolve this into an
 * [AiConnection] and [AiModelConfiguration] before creating a runtime.
 */
@Serializable
data class AiRuntimeSelection(
    val modelConfigurationId: AiModelConfiguration.Id,
)
