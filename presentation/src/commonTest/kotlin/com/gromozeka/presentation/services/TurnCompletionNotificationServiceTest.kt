package com.gromozeka.presentation.services

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnCompletionNotificationServiceTest {
    @Test
    fun notifiesOnlyWhenEnabledAndWindowIsNotFocused() {
        val settingsService = TestSettingsService(desktopSettings(notificationsEnabled = true))
        var notificationCount = 0
        val service = TurnCompletionNotificationService(settingsService) { notificationCount += 1 }

        service.notifyTurnCompleted()
        service.reportWindowFocus(false)
        service.notifyTurnCompleted()
        service.reportWindowFocus(true)
        service.notifyTurnCompleted()

        assertEquals(1, notificationCount)
    }

    @Test
    fun observesSettingChangesWithoutRestart() {
        val settingsService = TestSettingsService(desktopSettings(notificationsEnabled = false))
        var notificationCount = 0
        val service = TurnCompletionNotificationService(settingsService) { notificationCount += 1 }
        service.reportWindowFocus(false)

        service.notifyTurnCompleted()
        settingsService.saveSettings(desktopSettings(notificationsEnabled = true))
        service.notifyTurnCompleted()

        assertEquals(1, notificationCount)
    }

    @Test
    fun ignoresNonDesktopClients() {
        val settingsService = TestSettingsService(
            Settings(userDeviceSettings = UserDeviceSettings.Web()),
        )
        var notificationCount = 0
        val service = TurnCompletionNotificationService(settingsService) { notificationCount += 1 }
        service.reportWindowFocus(false)

        service.notifyTurnCompleted()

        assertEquals(0, notificationCount)
    }

    private fun desktopSettings(notificationsEnabled: Boolean) = Settings(
        userDeviceSettings = UserDeviceSettings.Desktop(
            turnCompletionNotificationsEnabled = notificationsEnabled,
        ),
    )

    private class TestSettingsService(initial: Settings) : SettingsService {
        private val mutableSettings = MutableStateFlow(initial)

        override val settingsFlow: StateFlow<Settings> = mutableSettings
        override val settings: Settings get() = mutableSettings.value
        override val userProfile get() = settings.userProfile
        override val userDeviceSettings get() = settings.userDeviceSettings
        override val mode: AppMode = AppMode.PRODUCTION
        override val homeDirectory: String = "/tmp/gromozeka-test"

        override fun saveSettings(settings: Settings) {
            mutableSettings.value = settings
        }

        override fun saveSettings(block: Settings.() -> Settings) {
            saveSettings(settings.block())
        }

        override fun reloadSettings() = Unit
    }
}
