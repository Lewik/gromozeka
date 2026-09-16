package com.gromozeka.domain.service

interface DesktopScreenshotCapture {
    val available: Boolean

    val unavailableReason: String?

    fun capture(maxLongEdge: Int): ByteArray
}
