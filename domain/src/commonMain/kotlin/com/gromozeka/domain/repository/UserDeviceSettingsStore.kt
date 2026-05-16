package com.gromozeka.domain.repository

import com.gromozeka.domain.model.UserDeviceSettings

/**
 * Local persistence for settings that belong to this physical device.
 *
 * This is intentionally not a repository for synchronized user profile data.
 */
interface UserDeviceSettingsStore {
    suspend fun getCurrent(): UserDeviceSettings

    suspend fun save(settings: UserDeviceSettings)
}
