package com.gromozeka.presentation.services

import com.sun.jna.WString
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsNativeNotificationPublisherTest {
    @Test
    fun `replaces a native notification with the same id`() {
        val native = RecordingNativeLibrary()
        val fallback = mutableListOf<DesktopNotification>()
        val publisher = WindowsNativeNotificationPublisher(
            fallbackPublisher = DesktopNotificationPublisher(fallback::add),
            nativeLibraryLoader = { native },
            executor = DirectExecutor,
        )

        publisher.publish(DesktopNotification("translate", "Gromozeka", "Translate started"))
        publisher.publish(DesktopNotification("translate", "Gromozeka", "Translate complete"))

        assertEquals(1, native.initializeCount)
        assertEquals(listOf(1L), native.hiddenIds)
        assertEquals(
            listOf(
                "Gromozeka" to "Translate started",
                "Gromozeka" to "Translate complete",
            ),
            native.shownNotifications,
        )
        assertEquals(emptyList(), fallback)
    }

    @Test
    fun `keeps unrelated native notifications independent`() {
        val native = RecordingNativeLibrary()
        val publisher = WindowsNativeNotificationPublisher(
            fallbackPublisher = DesktopNotificationPublisher {},
            nativeLibraryLoader = { native },
            executor = DirectExecutor,
        )

        publisher.publish(DesktopNotification("fix", "Gromozeka", "Fix started"))
        publisher.publish(DesktopNotification("translate", "Gromozeka", "Translate started"))

        assertEquals(emptyList(), native.hiddenIds)
    }

    @Test
    fun `uses fallback after native initialization failure`() {
        val native = RecordingNativeLibrary(initializeResult = -3)
        val fallback = mutableListOf<DesktopNotification>()
        val publisher = WindowsNativeNotificationPublisher(
            fallbackPublisher = DesktopNotificationPublisher(fallback::add),
            nativeLibraryLoader = { native },
            executor = DirectExecutor,
        )
        val notification = DesktopNotification("fix", "Gromozeka", "Fix started")

        publisher.publish(notification)
        publisher.publish(notification.copy(message = "Fix complete"))

        assertEquals(1, native.initializeCount)
        assertEquals(
            listOf(notification, notification.copy(message = "Fix complete")),
            fallback,
        )
    }

    private class RecordingNativeLibrary(
        private val initializeResult: Int = 1,
    ) : WindowsNotificationNativeLibrary {
        var initializeCount = 0
        val shownNotifications = mutableListOf<Pair<String, String>>()
        val hiddenIds = mutableListOf<Long>()

        override fun gromozeka_notifications_initialize(): Int {
            initializeCount += 1
            return initializeResult
        }

        override fun gromozeka_notifications_show(title: WString, message: WString): Long {
            shownNotifications += title.toString() to message.toString()
            return shownNotifications.size.toLong()
        }

        override fun gromozeka_notifications_hide(notificationId: Long): Int {
            hiddenIds += notificationId
            return 1
        }
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
