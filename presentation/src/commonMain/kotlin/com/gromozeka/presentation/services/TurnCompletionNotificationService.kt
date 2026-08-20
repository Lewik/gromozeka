package com.gromozeka.presentation.services

import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.flow.MutableStateFlow

fun interface TurnCompletionNotificationSink {
    fun showTurnCompleted()
}

object NoOpTurnCompletionNotificationSink : TurnCompletionNotificationSink {
    override fun showTurnCompleted() = Unit
}

class TurnCompletionNotificationService(
    private val settingsService: SettingsService,
    private val sink: TurnCompletionNotificationSink,
) {
    private val windowFocused = MutableStateFlow(true)

    fun reportWindowFocus(focused: Boolean) {
        windowFocused.value = focused
    }

    fun notifyTurnCompleted() {
        val desktopSettings = settingsService.userDeviceSettings as? UserDeviceSettings.Desktop ?: return
        if (!desktopSettings.turnCompletionNotificationsEnabled || windowFocused.value) return
        sink.showTurnCompleted()
    }
}
