package com.gromozeka.presentation.services

import klog.KLoggers

internal interface DesktopNotificationService {
    fun show(replacementId: String, title: String, message: String)
}

internal fun interface DesktopNotificationPublisher {
    fun publish(notification: DesktopNotification)
}

internal data class DesktopNotification(
    val replacementId: String,
    val title: String,
    val message: String,
)

internal object NoOpDesktopNotificationService : DesktopNotificationService {
    override fun show(replacementId: String, title: String, message: String) = Unit
}

internal class MacOsDesktopNotificationService : DesktopNotificationService {
    private val log = KLoggers.logger(this)

    override fun show(replacementId: String, title: String, message: String) {
        runCatching {
            ProcessBuilder(
                "osascript",
                "-e",
                """display notification "${message.appleScriptEscaped()}" with title "${title.appleScriptEscaped()}"""",
            ).start()
        }.onFailure { error ->
            log.warn(error) { "Failed to show desktop notification: ${error.message}" }
        }
    }

    private fun String.appleScriptEscaped(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}

internal class WindowsDesktopNotificationService(
    private val publisher: DesktopNotificationPublisher,
) : DesktopNotificationService {
    private val log = KLoggers.logger(this)

    override fun show(replacementId: String, title: String, message: String) {
        runCatching {
            publisher.publish(DesktopNotification(replacementId, title, message))
        }.onFailure { error ->
            log.warn(error) { "Failed to show desktop notification: ${error.message}" }
        }
    }
}

internal fun defaultDesktopNotificationService(
    windowsFallbackPublisher: DesktopNotificationPublisher,
    osName: String = System.getProperty("os.name"),
): DesktopNotificationService = when {
    osName.contains("mac", ignoreCase = true) -> MacOsDesktopNotificationService()
    osName.contains("windows", ignoreCase = true) -> WindowsDesktopNotificationService(
        WindowsNativeNotificationPublisher(windowsFallbackPublisher),
    )
    else -> NoOpDesktopNotificationService
}
