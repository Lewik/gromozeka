package com.gromozeka.bot.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ScreenCaptureService {
    
    private fun getGromozekaScreenshotsDir(): File {
        val systemTempDir = System.getProperty("java.io.tmpdir")
        val gromozekaDir = File(systemTempDir, "gromozeka")
        
        if (!gromozekaDir.exists()) {
            gromozekaDir.mkdirs()
            println("[ScreenCaptureService] Created directory: ${gromozekaDir.absolutePath}")
        }
        
        return gromozekaDir
    }
    
    suspend fun captureWindowToTemp(): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val screenshotsDir = getGromozekaScreenshotsDir()
            val screenshotFile = File(screenshotsDir, "screenshot_$timestamp.png")
            val tempPath = screenshotFile.absolutePath
            
            println("[ScreenCaptureService] Starting window capture to: $tempPath")
            
            val process = ProcessBuilder("screencapture", "-w", "-o", tempPath)
                .start()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && screenshotFile.exists()) {
                println("[ScreenCaptureService] Screenshot captured successfully: $tempPath")
                tempPath
            } else {
                println("[ScreenCaptureService] Screenshot failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            println("[ScreenCaptureService] Exception during screenshot: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}