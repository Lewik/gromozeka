package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

/**
 * Local settings document for one running Gromozeka installation.
 *
 * [userProfile] is portable user data. [userDeviceSettings] belongs to the
 * current device and should not be treated as shared profile data.
 */
@Serializable
data class Settings(
    val userProfile: UserProfile = UserProfile(),
    val userDeviceSettings: UserDeviceSettings = UserDeviceSettings.Desktop(),
)
