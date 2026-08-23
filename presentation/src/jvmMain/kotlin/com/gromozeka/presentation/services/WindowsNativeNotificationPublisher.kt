package com.gromozeka.presentation.services

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import klog.KLoggers
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

internal interface WindowsNotificationNativeLibrary : Library {
    fun gromozeka_notifications_initialize(): Int

    fun gromozeka_notifications_show(title: WString, message: WString): Long

    fun gromozeka_notifications_hide(notificationId: Long): Int
}

internal class WindowsNativeNotificationPublisher(
    private val fallbackPublisher: DesktopNotificationPublisher,
    private val nativeLibraryLoader: () -> WindowsNotificationNativeLibrary = {
        Native.load("gromozeka-notifications", WindowsNotificationNativeLibrary::class.java)
    },
    private val executor: Executor = Executors.newSingleThreadExecutor(NotificationThreadFactory),
) : DesktopNotificationPublisher {
    private val log = KLoggers.logger(this)
    private val notificationIds = mutableMapOf<String, Long>()
    private var nativeLibrary: WindowsNotificationNativeLibrary? = null
    private var nativeUnavailable = false

    override fun publish(notification: DesktopNotification) {
        executor.execute {
            runCatching {
                val library = initializedNativeLibrary() ?: return@runCatching false
                notificationIds.remove(notification.replacementId)?.let(library::gromozeka_notifications_hide)
                val notificationId = library.gromozeka_notifications_show(
                    WString(notification.title),
                    WString(notification.message),
                )
                if (notificationId < 0) {
                    false
                } else {
                    notificationIds[notification.replacementId] = notificationId
                    true
                }
            }.onFailure { error ->
                markNativeUnavailable(error)
            }.getOrDefault(false).takeIf { it } ?: fallbackPublisher.publish(notification)
        }
    }

    private fun initializedNativeLibrary(): WindowsNotificationNativeLibrary? {
        nativeLibrary?.let { return it }
        if (nativeUnavailable) return null

        return runCatching(nativeLibraryLoader)
            .mapCatching { library ->
                val result = library.gromozeka_notifications_initialize()
                check(result == 1) { "Native Windows notifications failed to initialize: $result" }
                library
            }
            .onSuccess { nativeLibrary = it }
            .onFailure(::markNativeUnavailable)
            .getOrNull()
    }

    private fun markNativeUnavailable(error: Throwable) {
        if (!nativeUnavailable) {
            log.warn(error) { "Native Windows notifications unavailable; using tray notifications: ${error.message}" }
        }
        nativeUnavailable = true
        nativeLibrary = null
        notificationIds.clear()
    }

    private object NotificationThreadFactory : ThreadFactory {
        override fun newThread(task: Runnable): Thread = Thread(task, "gromozeka-windows-notifications").apply {
            isDaemon = true
        }
    }
}
