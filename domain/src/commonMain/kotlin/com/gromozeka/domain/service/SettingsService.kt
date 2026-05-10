package com.gromozeka.domain.service

import com.gromozeka.domain.model.Settings
import kotlinx.coroutines.flow.StateFlow

interface SettingsService : SettingsProvider {
    val settingsFlow: StateFlow<Settings>
    val settings: Settings

    fun saveSettings(settings: Settings)
    fun saveSettings(block: Settings.() -> Settings)
    fun reloadSettings()
}
