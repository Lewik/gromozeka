package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.SecretRef
import kotlinx.serialization.Serializable

@Serializable
data class AiWebToolConfiguration(
    val braveSearch: BraveSearch = BraveSearch(),
    val jinaReader: JinaReader = JinaReader(),
    val claudeCode: ClaudeCode = ClaudeCode(),
) {
    @Serializable
    data class BraveSearch(
        val enabled: Boolean = false,
        val apiKey: SecretRef? = null,
    )

    @Serializable
    data class JinaReader(
        val enabled: Boolean = false,
        val apiKey: SecretRef? = null,
    )

    @Serializable
    data class ClaudeCode(
        val modelConfigurationId: AiModelConfiguration.Id? = null,
        val searchEnabled: Boolean = false,
        val fetchEnabled: Boolean = false,
    ) {
        init {
            require(!(searchEnabled || fetchEnabled) || modelConfigurationId != null) {
                "Claude Code web tools require a model configuration when enabled"
            }
        }
    }
}
