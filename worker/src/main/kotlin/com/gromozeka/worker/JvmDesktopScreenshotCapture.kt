package com.gromozeka.worker

import com.gromozeka.domain.service.DesktopScreenshotCapture
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot

internal class JvmDesktopScreenshotCapture(
    private val platformAccess: ComputerUsePlatformAccess,
) : DesktopScreenshotCapture {
    override val available: Boolean get() = unavailableReason == null

    override val unavailableReason: String?
        get() = when {
            GraphicsEnvironment.isHeadless() -> "Worker has no graphical desktop"
            isUnsupportedWaylandSession() -> "Wayland screen capture is not supported; use an X11 session"
            else -> platformAccess.screenCaptureUnavailableReason
        }

    override fun capture(maxLongEdge: Int): ByteArray {
        require(maxLongEdge in 1024..4096) { "maxLongEdge must be between 1024 and 4096" }
        check(available) { unavailableReason ?: "Desktop screen capture is unavailable" }
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduceOrNull(Rectangle::union)
            ?: error("Worker has no displays")
        return Robot().createScreenCapture(bounds)
            .fitLongEdge(maxLongEdge)
            .encodeBoundedPng(maxBytes = 8 * 1024 * 1024, minLongEdge = 1024)
            .bytes
    }
}
