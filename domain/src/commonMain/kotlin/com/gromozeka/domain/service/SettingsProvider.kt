package com.gromozeka.domain.service

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection

/**
 * Provides runtime environment settings and the current user/device settings.
 *
 * User preferences live in [userProfile]. Local machine or platform preferences
 * live in [userDeviceSettings]. Environment values such as [mode] and
 * [homeDirectory] stay here because they are not user profile data.
 */
interface SettingsProvider {
    val userProfile: UserProfile

    val userDeviceSettings: UserDeviceSettings

    /**
     * Application operating mode.
     *
     * Determines UI layout and feature availability (CHAT, VOICE, AGENT, etc.).
     * See [AppMode] for available modes.
     */
    val mode: AppMode

    /**
     * Absolute path to Gromozeka home directory.
     *
     * Contains configuration files, logs, temporary files, database.
     * Typically: ~/.gromozeka/ or user-configured location.
     */
    val homeDirectory: String

    fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        val modelConfiguration = userProfile.aiSettings.modelConfigurations.firstOrNull {
            it.id == selection.modelConfigurationId
        } ?: error("AI model configuration not found: ${selection.modelConfigurationId.value}")
        val connection = userProfile.aiSettings.connections.firstOrNull { it.id == modelConfiguration.connectionId }
            ?: error("AI connection not found: ${modelConfiguration.connectionId.value}")
        require(connection.enabled) { "AI connection is disabled: ${connection.id.value}" }
        require(modelConfiguration.enabled) { "AI model configuration is disabled: ${modelConfiguration.id.value}" }
        return ResolvedAiRuntime(connection, modelConfiguration)
    }

    fun resolveSecret(secretRef: SecretRef?): String? = null

    fun findFirstModelConfiguration(
        role: AiModelConfiguration.Role,
        provider: AiProvider? = null,
        connectionKind: AiConnection.Kind? = null,
    ): ResolvedAiRuntime? {
        val configuration = userProfile.aiSettings.modelConfigurations.firstOrNull { configuration ->
            configuration.enabled &&
                role in configuration.roles &&
                userProfile.aiSettings.connections.any { connection ->
                    connection.id == configuration.connectionId &&
                        connection.enabled &&
                        (provider == null || connection.kind.provider == provider) &&
                        (connectionKind == null || connection.kind == connectionKind)
                }
        } ?: return null
        return resolveAiRuntime(AiRuntimeSelection(configuration.id))
    }
}

data class ResolvedAiRuntime(
    val connection: AiConnection,
    val modelConfiguration: AiModelConfiguration,
)
