package com.gromozeka.presentation.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DesktopNotificationServiceTest {
    @Test
    fun `selects macOS notification service`() {
        val service = defaultDesktopNotificationService(
            windowsPublisher = DesktopNotificationPublisher { _, _ -> },
            osName = "Mac OS X",
        )

        assertIs<MacOsDesktopNotificationService>(service)
    }

    @Test
    fun `selects Windows notification service`() {
        val service = defaultDesktopNotificationService(
            windowsPublisher = DesktopNotificationPublisher { _, _ -> },
            osName = "Windows 11",
        )

        assertIs<WindowsDesktopNotificationService>(service)
    }

    @Test
    fun `selects no-op notification service for unsupported platform`() {
        val service = defaultDesktopNotificationService(
            windowsPublisher = DesktopNotificationPublisher { _, _ -> },
            osName = "Linux",
        )

        assertSame(NoOpDesktopNotificationService, service)
    }

    @Test
    fun `passes Windows notification payload without serialization`() {
        val published = mutableListOf<Pair<String, String>>()
        val service = WindowsDesktopNotificationService(
            DesktopNotificationPublisher { title, message -> published += title to message },
        )
        val title = "Gromozeka — тест \"quoted\""
        val message = "First line\nC:\\Users\\Lev\\file.txt <&> 'готово'"

        service.show(title, message)

        assertEquals(listOf(title to message), published)
    }

    @Test
    fun `ignores Windows notification publisher failure`() {
        val service = WindowsDesktopNotificationService(
            DesktopNotificationPublisher { _, _ -> error("tray unavailable") },
        )

        service.show("Gromozeka", "Complete")
    }
}
