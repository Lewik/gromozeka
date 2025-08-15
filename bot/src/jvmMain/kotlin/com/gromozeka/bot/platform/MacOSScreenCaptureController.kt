package com.gromozeka.bot.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class MacOSScreenCaptureController : ScreenCaptureController {

    private fun getGromozekaScreenshotsDir(): File {
        val systemTempDir = System.getProperty("java.io.tmpdir")
        val gromozekaDir = File(systemTempDir, "gromozeka")
        
        if (!gromozekaDir.exists()) {
            gromozekaDir.mkdirs()
            println("[ScreenCapture] Created directory: ${gromozekaDir.absolutePath}")
        }
        
        return gromozekaDir
    }

    override suspend fun captureWindow(): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val screenshotsDir = getGromozekaScreenshotsDir()
            val screenshotFile = File(screenshotsDir, "window_$timestamp.png")
            val filePath = screenshotFile.absolutePath
            
            println("[ScreenCapture] Starting window capture to: $filePath")
            
            val process = ProcessBuilder("screencapture", "-w", "-o", filePath)
                .start()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && screenshotFile.exists()) {
                println("[ScreenCapture] Window screenshot captured successfully: $filePath")
                filePath
            } else {
                println("[ScreenCapture] Window screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            println("[ScreenCapture] Exception during window screenshot: ${e.message}")
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
            
            println("[ScreenCapture] Starting fullscreen capture to: $filePath")
            
            val process = ProcessBuilder("screencapture", "-o", filePath)
                .start()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && screenshotFile.exists()) {
                println("[ScreenCapture] Fullscreen screenshot captured successfully: $filePath")
                filePath
            } else {
                println("[ScreenCapture] Fullscreen screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            println("[ScreenCapture] Exception during fullscreen screenshot: ${e.message}")
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
            
            println("[ScreenCapture] Starting area capture to: $filePath")
            
            val process = ProcessBuilder("screencapture", "-s", "-o", filePath)
                .start()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && screenshotFile.exists()) {
                println("[ScreenCapture] Area screenshot captured successfully: $filePath")
                filePath
            } else {
                println("[ScreenCapture] Area screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            println("[ScreenCapture] Exception during area screenshot: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}