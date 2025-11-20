package com.gromozeka.infrastructure.ai.platform

import klog.KLoggers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class MacOSScreenCaptureController : ScreenCaptureController {
    private val log = KLoggers.logger(this)

    private fun getGromozekaScreenshotsDir(): File {
        val systemTempDir = System.getProperty("java.io.tmpdir")
        val gromozekaDir = File(systemTempDir, "gromozeka")

        if (!gromozekaDir.exists()) {
            gromozekaDir.mkdirs()
            log.debug("Created directory: ${gromozekaDir.absolutePath}")
        }

        return gromozekaDir
    }

    override suspend fun captureWindow(): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val screenshotsDir = getGromozekaScreenshotsDir()
            val screenshotFile = File(screenshotsDir, "window_$timestamp.png")
            val filePath = screenshotFile.absolutePath

            log.debug("Starting window capture to: $filePath")

            val process = ProcessBuilder("screencapture", "-w", "-o", filePath)
                .start()

            val exitCode = process.waitFor()

            if (exitCode == 0 && screenshotFile.exists()) {
                log.info("Window screenshot captured successfully: $filePath")
                filePath
            } else {
                log.error("Window screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            log.error("Exception during window screenshot: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override suspend fun captureFullScreen(): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val screenshotsDir = getGromozekaScreenshotsDir()
            val screenshotFile = File(screenshotsDir, "fullscreen_$timestamp.png")
            val filePath = screenshotFile.absolutePath

            log.debug("Starting fullscreen capture to: $filePath")

            val process = ProcessBuilder("screencapture", "-o", filePath)
                .start()

            val exitCode = process.waitFor()

            if (exitCode == 0 && screenshotFile.exists()) {
                log.info("Fullscreen screenshot captured successfully: $filePath")
                filePath
            } else {
                log.error("Fullscreen screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            log.error("Exception during fullscreen screenshot: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override suspend fun captureArea(): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val screenshotsDir = getGromozekaScreenshotsDir()
            val screenshotFile = File(screenshotsDir, "area_$timestamp.png")
            val filePath = screenshotFile.absolutePath

            log.debug("Starting area capture to: $filePath")

            val process = ProcessBuilder("screencapture", "-s", "-o", filePath)
                .start()

            val exitCode = process.waitFor()

            if (exitCode == 0 && screenshotFile.exists()) {
                log.info("Area screenshot captured successfully: $filePath")
                filePath
            } else {
                log.error("Area screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            log.error("Exception during area screenshot: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}