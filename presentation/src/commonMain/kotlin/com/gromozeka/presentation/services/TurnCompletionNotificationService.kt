package com.gromozeka.presentation.services

import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.flow.MutableStateFlow

fun interface TurnCompletionNotificationSink {
    fun showTurnCompleted(conversationTitle: String)
}

object NoOpTurnCompletionNotificationSink : TurnCompletionNotificationSink {
    override fun showTurnCompleted(conversationTitle: String) = Unit
}

class TurnCompletionNotificationService(
    private val settingsService: SettingsService,
    private val sink: TurnCompletionNotificationSink,
) {
    private val windowFocused = MutableStateFlow(true)

    fun reportWindowFocus(focused: Boolean) {
        windowFocused.value = focused
    }

    fun notifyTurnCompleted(conversationTitle: String) {
        val desktopSettings = settingsService.userDeviceSettings as? UserDeviceSettings.Desktop ?: return
        if (!desktopSettings.turnCompletionNotificationsEnabled || windowFocused.value) return
        sink.showTurnCompleted(conversationTitle)
    }
}
