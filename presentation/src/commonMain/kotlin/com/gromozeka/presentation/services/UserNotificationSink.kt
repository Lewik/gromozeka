package com.gromozeka.presentation.services

import com.gromozeka.domain.model.UserNotification

fun interface UserNotificationSink {
    fun show(notification: UserNotification)
}

/** Platforms without native notification support still retain the complete in-app inbox. */
object NoOpUserNotificationSink : UserNotificationSink {
    override fun show(notification: UserNotification) = Unit
}
