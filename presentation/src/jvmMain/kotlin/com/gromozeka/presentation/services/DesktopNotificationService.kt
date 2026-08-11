package com.gromozeka.presentation.services

import klog.KLoggers

internal interface DesktopNotificationService {
    fun show(title: String, message: String)
}

internal object NoOpDesktopNotificationService : DesktopNotificationService {
    override fun show(title: String, message: String) = Unit
}

internal class MacOsDesktopNotificationService : DesktopNotificationService {
    private val log = KLoggers.logger(this)

    override fun show(title: String, message: String) {
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

internal fun defaultDesktopNotificationService(): DesktopNotificationService =
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        MacOsDesktopNotificationService()
    } else {
        NoOpDesktopNotificationService
    }
