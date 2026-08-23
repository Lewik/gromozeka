package com.gromozeka.presentation.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DesktopNotificationServiceTest {
    @Test
    fun `selects macOS notification service`() {
        val service = defaultDesktopNotificationService(
            windowsFallbackPublisher = DesktopNotificationPublisher {},
            osName = "Mac OS X",
        )

        assertIs<MacOsDesktopNotificationService>(service)
    }

    @Test
    fun `selects Windows notification service`() {
        val service = defaultDesktopNotificationService(
            windowsFallbackPublisher = DesktopNotificationPublisher {},
            osName = "Windows 11",
        )

        assertIs<WindowsDesktopNotificationService>(service)
    }

    @Test
    fun `selects no-op notification service for unsupported platform`() {
        val service = defaultDesktopNotificationService(
            windowsFallbackPublisher = DesktopNotificationPublisher {},
            osName = "Linux",
        )

        assertSame(NoOpDesktopNotificationService, service)
    }

    @Test
    fun `passes Windows notification payload without serialization`() {
        val published = mutableListOf<DesktopNotification>()
        val service = WindowsDesktopNotificationService(
            DesktopNotificationPublisher(published::add),
        )
        val replacementId = "quick-text-action:translate"
        val title = "Gromozeka — тест \"quoted\""
        val message = "First line\nC:\\Users\\Lev\\file.txt <&> 'готово'"

        service.show(replacementId, title, message)

        assertEquals(listOf(DesktopNotification(replacementId, title, message)), published)
    }

    @Test
    fun `ignores Windows notification publisher failure`() {
        val service = WindowsDesktopNotificationService(
            DesktopNotificationPublisher { error("tray unavailable") },
        )

        service.show("turn-completed", "Gromozeka", "Complete")
    }
}
