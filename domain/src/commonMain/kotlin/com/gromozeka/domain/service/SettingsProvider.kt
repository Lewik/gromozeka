package com.gromozeka.domain.service

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection

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

    val runtimeEnabledAiConnectionIds: Set<AiConnection.Id>
        get() = emptySet()

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

    fun resolveSecret(secretRef: SecretRef?): String? = null

}
